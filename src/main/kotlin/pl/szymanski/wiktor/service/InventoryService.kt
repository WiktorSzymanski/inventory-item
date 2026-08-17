package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.TaskExecutor
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.CreateOrderCommand
import pl.szymanski.wiktor.service.command.CreateOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.FailOrderCommandHandler
import pl.szymanski.wiktor.service.command.ReserveOrderItemsCommandHandler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val orderRepository: OrderRepository,
    private val createInventoryItemCommandHandler: CreateInventoryItemCommandHandler,
    private val createOrderCommandHandler: CreateOrderCommandHandler,
    private val reserveOrderItemsCommandHandler: ReserveOrderItemsCommandHandler,
    private val failOrderCommandHandler: FailOrderCommandHandler,
    @Qualifier("orderWorkerExecutor") private val orderWorkerExecutor: TaskExecutor,
    // The retry wait happens here instead of on the worker thread. The old path had an
    // ObjectProvider<InventoryService> self-proxy in this slot, needed only so processOrder() went
    // through the @Retryable interceptor; with the loop explicit, the proxy has nothing to do.
    private val retryScheduler: OrderRetryScheduler,
    // Whether a retried attempt RUNS on the retry pool (default, matching ES) or is handed back to
    // orderWorkerExecutor. Read as a @Value rather than from OrderRetryProperties so this package
    // does not have to import config — which already imports this one.
    @Value("\${app.order-retry.execute-on-retry-pool:true}")
    private val executeRetriesOnRetryPool: Boolean,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    // Admission timestamp handed from acceptOrder (HTTP thread) to the after-commit reservation
    // trigger, keyed by orderId. Populated before the OrderCreatedEvent can be delivered, removed
    // when the worker task is submitted. Absent only on backup-poller replay after a crash.
    private val acceptedAtByOrderId = ConcurrentHashMap<String, Long>()

    private val processingTimer: Timer = meterRegistry.timer("order.processing.time")
    private val queueWaitTimer: Timer = meterRegistry.timer("order.queue.wait")
    private val optimisticRetryCounter: Counter = meterRegistry.counter("inventory.optimistic.retry")
    private val optimisticExhaustedCounter: Counter = meterRegistry.counter("inventory.optimistic.exhausted")

    // The wait moved off the worker thread, so it can be priced. One
    // sample per RETRIED order (orders that never conflict record nothing), covering the whole
    // accumulated backoff rather than each individual wait.
    private fun backoffTimer(outcome: String): Timer =
        meterRegistry.timer("order.retry.backoff.time", "outcome", outcome)

    // The retry hop could not be scheduled, i.e. the pools are shutting down. Counted because the
    // alternative disposition — losing the task — would leave the order PENDING with no signal.
    private val retryRejectedCounter: Counter = meterRegistry.counter("order.retry.rejected")

    private fun e2eTimer(outcome: String): Timer =
        meterRegistry.timer("order.e2e.time", "outcome", outcome)

    private fun completedCounter(outcome: String, reason: String): Counter =
        meterRegistry.counter("orders.completed", "outcome", outcome, "reason", reason)

    private fun rejectionReason(e: Exception): String = when (e) {
        is InsufficientStockException -> "insufficient_stock"
        is NotFoundException -> "not_found"
        is OptimisticLockingFailureException, is PessimisticLockingFailureException -> "optimistic_exhausted"
        else -> "other"
    }

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
        // Record admission time before dispatch so it is available to the after-commit trigger,
        // which may fire on another thread as soon as the admission transaction commits.
        acceptedAtByOrderId[orderId] = System.nanoTime()
        log.info("[ORDER] accepted orderId={} userId={} itemCount={} correlationId={}", orderId, command.userId, command.items.size, command.correlationId)
        // Committed before the reservation is triggered, so the reservation and any concurrent
        // status query always see the row. Publishes OrderCreatedEvent as part of the same tx.
        createOrderCommandHandler.handle(orderId, command)
        return orderId
    }

    /**
     * Reservation trigger: consumes OrderCreatedEvent after the admission transaction commits and
     * hands the work to the unbounded worker pool (execute() never rejects, so there is no
     * queue-full load shedding — matches the ES branches' unbounded async executors).
     */
    @ApplicationModuleListener
    fun onOrderCreated(event: OrderCreatedEvent) {
        val acceptedAtNs = acceptedAtByOrderId.remove(event.orderId) ?: -1L
        submit(OrderAttempt(event, acceptedAtNs, attempt = 0, firstPickupNs = -1L, backoffNs = 0L))
    }

    private fun submit(state: OrderAttempt) {
        orderWorkerExecutor.execute { runOrderTask(state) }
    }

    /**
     * One attempt at reserving an order. A conflict re-submits a later attempt through
     * [retryScheduler] and RETURNS, so the worker thread is free for the whole backoff — the single
     * behavioural difference from the old path, where Spring's `@Retryable` interceptor
     * slept here instead.
     *
     * The attempt budget and the delays are unchanged ([OrderRetryPolicy]).
     */
    private fun runOrderTask(state: OrderAttempt) {
        val pickedUpNs = System.nanoTime()
        // Queue wait is a property of admission, so it is measured once, on the first pickup. A
        // re-submission's wait is backoff, not queueing, and is priced by backoffTimer instead.
        val firstPickupNs = if (state.firstPickupNs >= 0) {
            state.firstPickupNs
        } else {
            if (state.acceptedAtNs >= 0) {
                queueWaitTimer.record(pickedUpNs - state.acceptedAtNs, TimeUnit.NANOSECONDS)
            }
            pickedUpNs
        }

        try {
            reserveOrderItemsCommandHandler.handle(state.event)
        } catch (e: Exception) {
            val isConflict = e is OptimisticLockingFailureException || e is PessimisticLockingFailureException
            if (isConflict) {
                // Every failed attempt, the last one included — the old path counted it the same way (its
                // catch ran before the exception escaped the final attempt), and the pair is only
                // comparable if this stays identical.
                optimisticRetryCounter.increment()
            }
            if (isConflict && state.attempt < OrderRetryPolicy.MAX_RETRIES && scheduleRetry(state, firstPickupNs)) {
                return
            }
            rejectOrder(state, firstPickupNs, e, exhaustedConflict = isConflict)
            return
        }
        completedCounter("confirmed", "none").increment()
        recordTerminal(state, firstPickupNs, "confirmed")
    }

    /** @return true when the next attempt is safely queued and this thread may go. */
    private fun scheduleRetry(state: OrderAttempt, firstPickupNs: Long): Boolean {
        val delayMs = OrderRetryPolicy.delayMsFor(state.attempt)
        val next = state.copy(
            attempt = state.attempt + 1,
            firstPickupNs = firstPickupNs,
            backoffNs = state.backoffNs + TimeUnit.MILLISECONDS.toNanos(delayMs),
        )
        log.debug(
            "[ORDER] conflict orderId={} attempt={} retrying in {}ms correlationId={}",
            state.event.orderId, state.attempt + 1, delayMs, state.event.correlationId,
        )
        return try {
            retryScheduler.schedule(delayMs) {
                // Runs on a retry thread, where a thrown exception would be swallowed with the task
                // and leave the order PENDING for good.
                try {
                    if (executeRetriesOnRetryPool) {
                        // The retry pool is a lane of its own: this attempt runs HERE, so it neither
                        // competes with fresh orders for a worker nor queues behind them. Matches the
                        // ES branches, where RetryingCallback re-dispatches inline onto its retry pool.
                        runOrderTask(next)
                    } else {
                        // Hand back to the worker pool. Retries then run at full worker width, but at
                        // the TAIL of a FIFO queue, so under backlog they wait behind newer orders.
                        // The worker queue is unbounded, so this can only reject during shutdown.
                        submit(next)
                    }
                } catch (e: RejectedExecutionException) {
                    retryRejectedCounter.increment()
                    log.warn("[ORDER] worker pool rejected retry orderId={} — failing the order", next.event.orderId, e)
                    rejectOrder(next, firstPickupNs, e, exhaustedConflict = false)
                }
            }
            true
        } catch (e: RejectedExecutionException) {
            retryRejectedCounter.increment()
            log.warn("[ORDER] retry scheduler rejected orderId={} — failing the order", state.event.orderId, e)
            false
        }
    }

    private fun rejectOrder(state: OrderAttempt, firstPickupNs: Long, e: Exception, exhaustedConflict: Boolean) {
        val event = state.event
        log.warn("[ORDER] rejected orderId={} reason={} correlationId={}", event.orderId, e.message, event.correlationId)
        meterRegistry.counter("inventory.exception", "type", e.javaClass.simpleName).increment()
        if (exhaustedConflict) {
            optimisticExhaustedCounter.increment()
        }
        completedCounter("rejected", rejectionReason(e)).increment()
        // Rejection goes through the aggregate's command handler (own transaction, records
        // OrderFailedEvent to the outbox). Never let a failure here escape into the executor.
        try {
            failOrderCommandHandler.handle(
                FailOrderCommand(event.orderId, e.message ?: e.javaClass.simpleName, event.correlationId)
            )
        } catch (rejectError: Exception) {
            log.error("[ORDER] failed to reject orderId={} correlationId={}", event.orderId, event.correlationId, rejectError)
        }
        recordTerminal(state, firstPickupNs, "rejected")
    }

    /**
     * Recorded once per order, at its terminal outcome — not once per attempt. `processingTimer`
     * therefore still spans first pickup to final outcome with backoff included, which is what it
     * spanned when the backoff was a sleep inside a single task.
     */
    private fun recordTerminal(state: OrderAttempt, firstPickupNs: Long, outcome: String) {
        val nowNs = System.nanoTime()
        processingTimer.record(nowNs - firstPickupNs, TimeUnit.NANOSECONDS)
        if (state.backoffNs > 0) {
            backoffTimer(outcome).record(state.backoffNs, TimeUnit.NANOSECONDS)
        }
        if (state.acceptedAtNs >= 0) {
            e2eTimer(outcome).record(nowNs - state.acceptedAtNs, TimeUnit.NANOSECONDS)
        }
    }

    /**
     * Everything about one order's progress that has to survive being handed to another thread.
     * Before the rebuild all of this was thread-local to a single blocking task.
     */
    private data class OrderAttempt(
        val event: OrderCreatedEvent,
        val acceptedAtNs: Long,
        val attempt: Int,
        val firstPickupNs: Long,
        val backoffNs: Long,
    )
}
