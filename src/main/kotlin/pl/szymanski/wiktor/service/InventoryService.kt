package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderSaga
import pl.szymanski.wiktor.domain.SagaStatus
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.OrderSagaRepository
import pl.szymanski.wiktor.service.command.CompleteOrderCommand
import pl.szymanski.wiktor.service.command.CompleteOrderCommandHandler
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.CreateOrderCommand
import pl.szymanski.wiktor.service.command.CreateOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.FailOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailReservationCommand
import pl.szymanski.wiktor.service.command.FailReservationCommandHandler
import pl.szymanski.wiktor.service.command.ReleaseReservationCommand
import pl.szymanski.wiktor.service.command.ReleaseReservationCommandHandler
import pl.szymanski.wiktor.service.command.ReserveOrderItemCommand
import pl.szymanski.wiktor.service.command.ReserveOrderItemCommandHandler
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Admission, and the execution of ONE saga step per call.
 *
 * The shape of this class is the same as TO-3's — accept on the HTTP thread, do the work on the
 * order-worker pool, retry conflicts off-thread through [OrderRetryScheduler] — and the unit it
 * applies that shape to is different. TO-3 runs an ORDER on the pool: one task reserves every line
 * and either confirms or rejects. Here a task runs exactly one step, and which step that is comes
 * from the saga row rather than from the caller. [OrderReservationSaga] does nothing but hand this
 * an order id every time one of that order's events comes back through the outbox.
 *
 * **Why "advance" takes no step argument.** Reading the saga is the only way to know what the next
 * step is, and it is the only reliable one: an event carries where the saga WAS, and by the time it
 * is delivered — possibly twice, possibly after a restart — the saga may be somewhere else entirely.
 * Deciding from the row means a redelivery re-runs the CURRENT step (which the cursor guard then
 * makes a no-op) rather than resurrecting an old one, and it collapses all four of the saga's
 * triggers into one code path.
 */
@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val orderRepository: OrderRepository,
    private val orderSagaRepository: OrderSagaRepository,
    private val createInventoryItemCommandHandler: CreateInventoryItemCommandHandler,
    private val createOrderCommandHandler: CreateOrderCommandHandler,
    private val reserveOrderItemCommandHandler: ReserveOrderItemCommandHandler,
    private val releaseReservationCommandHandler: ReleaseReservationCommandHandler,
    private val failReservationCommandHandler: FailReservationCommandHandler,
    private val completeOrderCommandHandler: CompleteOrderCommandHandler,
    private val failOrderCommandHandler: FailOrderCommandHandler,
    @Qualifier("orderWorkerExecutor") private val orderWorkerExecutor: TaskExecutor,
    private val retryScheduler: OrderRetryScheduler,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    // Admission timestamp handed from acceptOrder (HTTP thread) to the first step's pickup, keyed by
    // orderId. Only order.queue.wait uses it, and only once per order — every other span is measured
    // from order_saga.started_at, which holds the same instant durably and therefore survives the
    // hops this map cannot.
    private val acceptedAtByOrderId = ConcurrentHashMap<String, Long>()

    private val queueWaitTimer: Timer = meterRegistry.timer("order.queue.wait")
    private val optimisticRetryCounter: Counter = meterRegistry.counter("inventory.optimistic.retry")
    private val optimisticExhaustedCounter: Counter = meterRegistry.counter("inventory.optimistic.exhausted")
    private val retryRejectedCounter: Counter = meterRegistry.counter("order.retry.rejected")

    // The saga row's own read, timed here because this is where it happens: once per step, feeding
    // both the choice of step and the handler that runs it. `OrderSaga` has no counterpart on TO-3,
    // which has no saga to load, nor on the ES branches, where the framework loads saga state.
    private val sagaLoadTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .tag("aggregate", "OrderSaga")
        .register(meterRegistry)

    // ONE SAMPLE PER STEP, where TO-3 records one per order.
    //
    // The TO-3 span — first pickup of an order to its final outcome, backoff included — does not
    // exist on this branch: an order is not processed by one task, it is processed by N tasks that
    // do not know about each other and are separated by outbox round trips. What is preserved is
    // the MEANING of the span, which is "worker-side latency of one unit of work including its
    // retries"; the unit shrank from an order to a line. Divide by ITEMS_PER_ORDER before comparing
    // it with TO-3's, and use order_e2e_time, which is unchanged, when what is wanted is the order.
    private val processingTimer: Timer = meterRegistry.timer("order.processing.time")

    // Same name as TO-3, but its `outcome` values changed with the unit: TO-3 tags the accumulated
    // backoff of an ORDER `confirmed` or `rejected`, and what is retried here is a STEP, whose
    // outcome is `applied` or `failed`. Nothing queries the tag today; the rename is so that
    // something querying it later cannot read a step's success as an order's.
    private fun backoffTimer(outcome: String): Timer =
        meterRegistry.timer("order.retry.backoff.time", "outcome", outcome)

    private fun e2eTimer(outcome: String): Timer =
        meterRegistry.timer("order.e2e.time", "outcome", outcome)

    private fun completedCounter(outcome: String, reason: String): Counter =
        meterRegistry.counter("orders.completed", "outcome", outcome, "reason", reason)

    // The ES branch's saga metrics, by the same names and tags, so TO-3-Saga and ES-4 line up
    // panel-for-panel. `saga.lifetime` is measured from admission to the saga's END, which is later
    // than ES-4 measures it: there the saga ends when it SUBMITS the terminal command, here when
    // that command has committed. The difference is one transaction and is worth knowing before
    // reading the two histograms side by side.
    private fun sagaCompletedCounter(outcome: String): Counter =
        meterRegistry.counter("saga.completed", "outcome", outcome)

    private fun sagaLifetimeTimer(outcome: String): Timer =
        Timer.builder("saga.lifetime")
            .tag("outcome", outcome)
            .publishPercentileHistogram(true)
            .maximumExpectedValue(Duration.ofMinutes(10))
            .register(meterRegistry)

    private fun sagaCommandFailedCounter(stage: String): Counter =
        meterRegistry.counter("saga.command.failed", "stage", stage)

    private fun sagaStepTimer(step: String, outcome: String): Timer =
        meterRegistry.timer("saga.step.time", "step", step, "outcome", outcome)

    private fun rejectionReason(e: Exception): String = when (e) {
        is InsufficientStockException -> "insufficient_stock"
        is NotFoundException -> "not_found"
        is OptimisticLockingFailureException, is PessimisticLockingFailureException -> "optimistic_exhausted"
        else -> "other"
    }

    private fun isConflict(e: Exception): Boolean =
        e is OptimisticLockingFailureException || e is PessimisticLockingFailureException

    fun createItem(command: CreateItemCommand): InventoryItem =
        createInventoryItemCommandHandler.handle(command)

    fun getItem(itemId: String): InventoryItem? =
        inventoryRepository.findById(itemId).orElse(null)

    fun getItems(pageable: Pageable): Page<InventoryItem> =
        inventoryRepository.findAll(pageable)

    fun getOrder(orderId: String): Order? =
        orderRepository.findById(orderId).orElse(null)

    fun acceptOrder(command: CreateOrderCommand): String {
        val orderId = UUID.randomUUID().toString()
        val acceptedAt = clock.instant()
        // Recorded before dispatch so it is available to the first step, which may start on another
        // thread the instant the admission transaction commits.
        acceptedAtByOrderId[orderId] = System.nanoTime()
        log.info("[ORDER] accepted orderId={} userId={} itemCount={} correlationId={}", orderId, command.userId, command.items.size, command.correlationId)
        // Order, saga and OrderCreatedEvent commit together. The saga's first step is triggered by
        // that event coming back through the outbox, not from here.
        createOrderCommandHandler.handle(orderId, command, acceptedAt)
        return orderId
    }

    /**
     * Runs whatever step [orderId]'s saga is waiting for, off the calling thread.
     *
     * @param first true only for the `OrderCreatedEvent` trigger, which is the one delivery whose
     *        wait since admission is queueing rather than saga progress.
     * @return a future completing when the step reaches a terminal outcome — including after its
     *         retries. [OrderReservationSaga] returns it to Spring Modulith, which completes the
     *         triggering publication only when it completes, so a step that never ran leaves its
     *         event to be redelivered instead of silently disappearing.
     */
    fun submitAdvance(orderId: String, correlationId: UUID, first: Boolean = false): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        try {
            orderWorkerExecutor.execute { advance(orderId, correlationId, first, future) }
        } catch (e: RejectedExecutionException) {
            // Only reachable at shutdown: the pool's DelayedWorkQueue is unbounded. Failing the
            // future leaves the publication incomplete, so the republisher picks the step up after
            // a restart — which is strictly better than completing it and stranding the order.
            log.warn("[SAGA] worker pool rejected an advance orderId={} — leaving the event incomplete", orderId, e)
            future.completeExceptionally(e)
        }
        return future
    }

    private fun advance(orderId: String, correlationId: UUID, first: Boolean, future: CompletableFuture<Void>) {
        val pickedUpNs = System.nanoTime()
        if (first) {
            acceptedAtByOrderId.remove(orderId)?.let {
                queueWaitTimer.record(pickedUpNs - it, TimeUnit.NANOSECONDS)
            }
        }

        val saga = try {
            val startNs = System.nanoTime()
            val found = orderSagaRepository.findById(orderId)
            sagaLoadTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
            found.orElseThrow { NotFoundException("Saga for order $orderId not found") }
        } catch (e: Exception) {
            // Left incomplete deliberately. A saga that is missing now may simply not be visible
            // yet, and the republisher retrying beats dropping the order's only trigger.
            log.error("[SAGA] could not load saga orderId={} correlationId={}", orderId, correlationId, e)
            future.completeExceptionally(e)
            return
        }

        val kind = nextStep(saga)
        if (kind == null) {
            // ENDED. Every redelivery after the terminal step lands here, which is the common case
            // for the last event of every order, not an anomaly.
            future.complete(null)
            return
        }

        runStep(
            StepAttempt(
                saga = saga,
                kind = kind,
                lineIndex = if (kind == StepKind.RELEASE) saga.currentIndex - 1 else saga.currentIndex,
                correlationId = correlationId,
                attempt = 0,
                pickedUpNs = pickedUpNs,
                backoffNs = 0L,
                future = future,
            )
        )
    }

    /**
     * The saga's transition table, and the only place it lives.
     *
     * Reading it as a function of the ROW rather than of the incoming event is what makes
     * redelivery harmless: whatever arrives, the answer is "the step this saga is waiting for".
     */
    private fun nextStep(saga: OrderSaga): StepKind? = when {
        saga.status == SagaStatus.ENDED -> null
        saga.status == SagaStatus.RUNNING && saga.currentIndex < saga.lineCount -> StepKind.RESERVE
        saga.status == SagaStatus.RUNNING -> StepKind.COMPLETE
        saga.currentIndex > 0 -> StepKind.RELEASE
        else -> StepKind.FAIL_ORDER
    }

    /**
     * One attempt at one step. A conflict schedules a later attempt through [retryScheduler] and
     * RETURNS, so the worker thread is free for the whole backoff — identical to TO-3, except that
     * what gets retried is a line rather than an order.
     */
    private fun runStep(state: StepAttempt) {
        val startNs = System.nanoTime()
        val saga = state.saga
        val orderId = saga.orderId

        try {
            when (state.kind) {
                StepKind.RESERVE -> reserveOrderItemCommandHandler.handle(
                    saga, ReserveOrderItemCommand(orderId, state.lineIndex, state.correlationId),
                )

                StepKind.RELEASE -> releaseReservationCommandHandler.handle(
                    saga, ReleaseReservationCommand(orderId, state.lineIndex, state.correlationId),
                )

                StepKind.COMPLETE -> {
                    if (completeOrderCommandHandler.handle(
                            CompleteOrderCommand(orderId, saga.lineCount, state.correlationId)
                        )
                    ) {
                        recordTerminal(saga, "confirmed", "none")
                    }
                }

                StepKind.FAIL_ORDER -> {
                    val reason = saga.failureReason ?: "reservation failed"
                    if (failOrderCommandHandler.handle(
                            FailOrderCommand(orderId, reason, state.correlationId)
                        )
                    ) {
                        recordTerminal(saga, "rejected", saga.failureCode ?: "other")
                    }
                }
            }
        } catch (e: Exception) {
            val conflict = isConflict(e)
            if (conflict) {
                optimisticRetryCounter.increment()
            }
            if (conflict && state.attempt < OrderRetryPolicy.MAX_RETRIES && scheduleRetry(state)) {
                return
            }
            if (conflict) {
                optimisticExhaustedCounter.increment()
            }
            abandon(state, e, startNs)
            return
        }

        sagaStepTimer(state.kind.tag, "applied").record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
        recordStepLatency(state, "applied")
        state.future.complete(null)
    }

    /** @return true when the next attempt is safely queued and this thread may go. */
    private fun scheduleRetry(state: StepAttempt): Boolean {
        val delayMs = OrderRetryPolicy.delayMsFor(state.attempt)
        val next = state.copy(
            attempt = state.attempt + 1,
            backoffNs = state.backoffNs + TimeUnit.MILLISECONDS.toNanos(delayMs),
        )
        log.debug(
            "[SAGA] conflict orderId={} step={} line={} attempt={} retrying in {}ms correlationId={}",
            state.saga.orderId, state.kind, state.lineIndex, state.attempt + 1, delayMs, state.correlationId,
        )
        return try {
            retryScheduler.schedule(delayMs) { runStep(next) }
            true
        } catch (e: RejectedExecutionException) {
            retryRejectedCounter.increment()
            log.warn("[SAGA] retry scheduler rejected orderId={} step={}", state.saga.orderId, state.kind, e)
            false
        }
    }

    /**
     * Terminal disposition for a step that cannot be retried any further, and the point at which
     * this branch's failure handling stops resembling TO-3's.
     *
     * On TO-3 there is one disposition: the reservation transaction rolled back, so reject the order
     * and be done. Here it depends on WHICH step failed, because the three cases have genuinely
     * different escape hatches:
     *
     *  * **A reserve** turns into `FailReservationCommand`, which starts compensation. That is a
     *    normal, expected outcome — out of stock is a business answer, not a fault — so the
     *    publication is completed and the saga proceeds on the compensating path.
     *  * **A release, a confirm or a reject** has nowhere to go. There is no compensating a
     *    compensation, and abandoning one would leave stock held or an order PENDING for ever. So
     *    the future is failed instead, which leaves the triggering publication INCOMPLETE and hands
     *    the step to `IncompleteEventRepublisher` — a slow retry (its min-age is a minute), but an
     *    unbounded one, which is the right trade for the paths that must eventually succeed.
     */
    private fun abandon(state: StepAttempt, cause: Exception, startNs: Long) {
        val saga = state.saga
        val orderId = saga.orderId
        meterRegistry.counter("inventory.exception", "type", cause.javaClass.simpleName).increment()
        sagaStepTimer(state.kind.tag, "failed").record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
        recordStepLatency(state, "failed")

        if (state.kind != StepKind.RESERVE) {
            sagaCommandFailedCounter(state.kind.tag).increment()
            log.error(
                "[SAGA] {} step failed orderId={} line={} — leaving the event incomplete for the republisher",
                state.kind, orderId, state.lineIndex, cause,
            )
            state.future.completeExceptionally(cause)
            return
        }

        log.warn(
            "[SAGA] reserve failed orderId={} line={} reason={} correlationId={}",
            orderId, state.lineIndex, cause.message, state.correlationId,
        )
        try {
            failReservationCommandHandler.handle(
                saga,
                FailReservationCommand(
                    orderId = orderId,
                    lineIndex = state.lineIndex,
                    reason = cause.message ?: cause.javaClass.simpleName,
                    reasonCode = rejectionReason(cause),
                    correlationId = state.correlationId,
                ),
            )
            state.future.complete(null)
        } catch (e: Exception) {
            // The failure could not even be recorded. Left incomplete so it is tried again: an
            // order whose compensation never STARTS is the one stranded-PENDING shape this design
            // has no other defence against.
            sagaCommandFailedCounter("fail-reservation").increment()
            log.error("[SAGA] could not start compensation orderId={} line={}", orderId, state.lineIndex, e)
            state.future.completeExceptionally(e)
        }
    }

    /**
     * Recorded once per step, at its outcome — attempts and their backoff included, which is what
     * `order.processing.time` spanned on TO-3 for a whole order.
     */
    private fun recordStepLatency(state: StepAttempt, outcome: String) {
        processingTimer.record(System.nanoTime() - state.pickedUpNs, TimeUnit.NANOSECONDS)
        if (state.backoffNs > 0) {
            backoffTimer(outcome).record(state.backoffNs, TimeUnit.NANOSECONDS)
        }
    }

    /**
     * The order's own outcome, recorded once, where the saga ends.
     *
     * Measured from `order_saga.started_at` — the admission instant, written durably in the accept
     * transaction — rather than from a `System.nanoTime()` held in memory, because nothing in memory
     * survives the N deliveries between admission and here.
     */
    private fun recordTerminal(saga: OrderSaga, outcome: String, reason: String) {
        val elapsed = Duration.between(saga.startedAt.toInstant(), clock.instant())
        completedCounter(outcome, reason).increment()
        e2eTimer(outcome).record(elapsed)
        sagaCompletedCounter(if (outcome == "confirmed") "completed" else "failed").increment()
        sagaLifetimeTimer(if (outcome == "confirmed") "completed" else "failed").record(elapsed)
    }

    private enum class StepKind(val tag: String) {
        RESERVE("reserve"),
        RELEASE("release"),
        COMPLETE("complete"),
        FAIL_ORDER("fail-order"),
    }

    /**
     * Everything about one step's progress that has to survive being handed to another thread.
     *
     * [saga] is the snapshot the step was chosen from and is deliberately NOT re-read between
     * attempts: a conflict rolls the step back without touching the saga row, and the cursor guard
     * inside the write re-checks the only thing that could have changed anyway.
     */
    private data class StepAttempt(
        val saga: OrderSaga,
        val kind: StepKind,
        val lineIndex: Int,
        val correlationId: UUID,
        val attempt: Int,
        val pickedUpNs: Long,
        val backoffNs: Long,
        val future: CompletableFuture<Void>,
    )
}
