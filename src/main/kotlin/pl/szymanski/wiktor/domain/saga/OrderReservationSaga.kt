package pl.szymanski.wiktor.domain.saga

import com.fasterxml.jackson.annotation.JsonAutoDetect
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.Timestamp
import org.axonframework.modelling.saga.EndSaga
import org.axonframework.modelling.saga.SagaEventHandler
import org.axonframework.modelling.saga.SagaLifecycle
import org.axonframework.modelling.saga.StartSaga
import org.axonframework.spring.stereotype.Saga
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import java.time.Duration
import java.time.Instant
import pl.szymanski.wiktor.domain.InventoryReservationFailedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderFailedEvent
import pl.szymanski.wiktor.domain.OrderItem
import pl.szymanski.wiktor.service.command.CompleteOrderCommand
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.ReleaseReservationCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Saga
@ProcessingGroup("order-saga")
class OrderReservationSaga {

    @Autowired @Transient
    private lateinit var commandGateway: CommandGateway

    // Commands are submitted here so the saga processor thread is never blocked waiting
    // for per-aggregate locks or JDBC writes. The lock wait happens on a pool thread;
    // the result event arrives in the event store when the command succeeds.
    //
    // ES-4-bounded: this is the UNGATED lane, and everything already admitted uses it —
    // continuations, completion, failure, compensation and abandon(). Its queue is unbounded, so
    // it never rejects except at shutdown, which is the contract abandon()'s own
    // RejectedExecutionException fallback is written against.
    @Autowired @Transient
    @Qualifier("sagaCommandExecutor")
    private lateinit var commandExecutor: Executor

    // ES-4-bounded: the front door, and the only gated submission in this class. Wraps the same
    // pool as commandExecutor behind a semaphore of AXON_SAGA_INTAKE_CAPACITY slots, so at most
    // that many not-yet-started orders can sit in front of an in-flight saga's next step. When it
    // is full THIS THREAD BLOCKS, which is the point: the order-saga processor stops reading
    // OrderCreatedEvents and the backlog waits in the durable event store instead of the heap.
    // See SagaIntakeGate for why the permit is returned at dequeue and why gating anything else
    // here would deadlock the processor.
    @Autowired @Transient
    @Qualifier("sagaIntakeExecutor")
    private lateinit var intakeExecutor: Executor

    @Autowired @Transient
    private lateinit var meterRegistry: MeterRegistry

    companion object {
        private val log = LoggerFactory.getLogger(OrderReservationSaga::class.java)
    }

    private lateinit var orderId: String
    private lateinit var items: List<OrderItem>
    private lateinit var correlationId: UUID
    private var currentIndex: Int = 0
    private val reservedItems = mutableListOf<OrderItem>()

    // Epoch millis of OrderCreatedEvent, carried in the saga's serialized state so
    // saga.lifetime can be measured at end() without touching the orders projection.
    private var createdAtMillis: Long = 0

    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    fun on(event: OrderCreatedEvent, @Timestamp timestamp: Instant) {
        orderId = event.orderId
        items = event.items
        correlationId = event.correlationId
        createdAtMillis = timestamp.toEpochMilli()
        SagaLifecycle.associateWith("correlationId", correlationId.toString())
        log.info("[SAGA] start orderId={} items={}", orderId, items.size)
        // The ONLY gated call. This is a new order arriving.
        sendNextReservation(admit = true)
    }

    @SagaEventHandler(associationProperty = "correlationId")
    fun on(event: InventoryReservedEvent) {
        reservedItems.add(items[currentIndex])
        currentIndex++
        log.debug("[SAGA] reserved itemId={} ({}/{}) orderId={}", event.id, currentIndex, items.size, orderId)
        if (currentIndex < items.size) {
            // Ungated: this saga was admitted when it started, and making it queue behind the
            // arrivals the gate is holding is precisely the starvation this branch removes.
            sendNextReservation(admit = false)
        } else {
            log.info("[SAGA] all items reserved, completing orderId={}", orderId)
            // reservedItems.add above already ran, so this snapshot is the WHOLE order, not a prefix.
            val orderIdCopy = orderId
            val toRelease = reservedItems.toList()
            commandExecutor.execute {
                dispatchOrAbandon(orderIdCopy, toRelease, "complete") {
                    commandGateway.send<Any?>(CompleteOrderCommand(orderIdCopy))
                }
            }
            // Recorded as "completed" before the command's verdict is known. If it later fails,
            // saga.command.failed{stage="complete"} is what makes that visible — the saga has
            // already ended by then and cannot be re-tagged. OrderAggregate is uncontended
            // (one writer per order), so this is an infrastructure-only path.
            recordSagaEnd("completed")
            SagaLifecycle.end()
        }
    }

    @SagaEventHandler(associationProperty = "correlationId")
    fun on(event: InventoryReservationFailedEvent) {
        log.warn("[SAGA] reservation failed itemId={} orderId={} reason={}", event.id, orderId, event.reason)
        val toRelease = reservedItems.toList()
        val failReason = event.reason
        val orderIdCopy = orderId
        commandExecutor.execute {
            releaseAll(orderIdCopy, toRelease)
            sendFailOrder(orderIdCopy, failReason)
        }
        recordSagaEnd("failed")
        SagaLifecycle.end()
    }

    // The only legal place to end a saga that was abandoned off-thread. Reached via
    // abandon() -> FailOrderCommand -> OrderAggregate -> OrderFailedEvent -> this processor.
    // The out-of-stock path never arrives here: it calls SagaLifecycle.end() inline, so by the
    // time its OrderFailedEvent is read back the saga and its associations are already gone.
    @EndSaga
    @SagaEventHandler(associationProperty = "orderId")
    fun on(event: OrderFailedEvent) {
        log.warn("[SAGA] order failed outside the saga orderId={} reason={}", event.orderId, event.reason)
        recordSagaEnd("command_failed")
    }

    // Recorded where the saga's own lifecycle actually ends, which is strictly EARLIER
    // than the point es.events.processed{eventType=OrderCompletedEvent} fires: the
    // Complete/FailOrderCommand above is only *submitted* to commandExecutor, never
    // awaited, and OrderCompletedEvent then has to be appended by OrderAggregate and
    // picked up by the order-projection processor. Comparing saga.completed against that
    // counter therefore isolates saga throughput from the command + aggregate + projection
    // stages downstream of it.
    private fun recordSagaEnd(outcome: String) {
        meterRegistry.counter("saga.completed", "outcome", outcome).increment()
        if (createdAtMillis > 0) {
            Timer.builder("saga.lifetime")
                .tag("outcome", outcome)
                .publishPercentileHistogram(true)
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(meterRegistry)
                .record(Duration.ofMillis(System.currentTimeMillis() - createdAtMillis))
        }
    }

    private fun sendNextReservation(admit: Boolean) {
        val item = items[currentIndex]
        // Snapshotted on the saga processor thread. Safe because reservations are strictly
        // sequential — exactly one command is in flight per saga, and the only thing that mutates
        // reservedItems is the success handler for the very command being dispatched here.
        val orderIdCopy = orderId
        val correlationIdCopy = correlationId
        val toRelease = reservedItems.toList()
        log.debug("[SAGA] reserving itemId={} ({}/{}) orderId={}", item.itemId, currentIndex + 1, items.size, orderId)
        val executor = if (admit) intakeExecutor else commandExecutor
        executor.execute {
            dispatchOrAbandon(orderIdCopy, toRelease, "reserve") {
                commandGateway.send<Any?>(SagaReserveItemCommand(item.itemId, item.quantity, correlationIdCopy))
            }
        }
    }

    // `commandGateway.send` can fail two ways, and both must reach the same disposition:
    // asynchronously (the usual case — retries exhausted) or SYNCHRONOUSLY, before a future
    // exists at all, from a dispatch interceptor or a payload that will not serialize. A
    // synchronous throw here would otherwise escape into the executor's uncaught handler,
    // append no event, and leave the order PENDING forever — the exact failure this saga
    // exists to eliminate, and invisible because the executor discards it.
    private fun dispatchOrAbandon(
        orderId: String,
        toRelease: List<OrderItem>,
        stage: String,
        send: () -> CompletableFuture<*>,
    ) {
        try {
            send().whenComplete { _, ex -> if (ex != null) abandon(orderId, toRelease, stage, ex) }
        } catch (e: Exception) {
            abandon(orderId, toRelease, stage, e)
        }
    }

    // Terminal disposition for a command that failed AFTER ConcurrencyRetryScheduler gave up.
    // Runs on a commandExecutor pool thread, outside saga scope, so it must not touch
    // SagaLifecycle: it sends FailOrderCommand instead and lets the resulting OrderFailedEvent
    // come back to on(OrderFailedEvent) on the saga processor thread.
    private fun abandon(orderId: String, toRelease: List<OrderItem>, stage: String, cause: Throwable) {
        log.error("[SAGA] {} command failed orderId={} — failing order", stage, orderId, cause)
        meterRegistry.counter("saga.command.failed", "stage", stage).increment()
        // Handed back to the 64-thread saga pool rather than run inline. When retries are what
        // exhausted, Axon's RetryingCallback completes the future on the retryExecutor
        // (CommandGatewayConfig.RETRY_POOL_SIZE threads), and compensating an N-line order there
        // means N sequential aggregate loads + appends on 1 of those. Retries for every OTHER
        // in-flight command queue behind that, burn their attempts against the 500ms backoff cap,
        // and exhaust in turn — a contention spike amplifying itself into a rejection cascade. The
        // pool this resubmits to is the one already sized for blocking command dispatch. That
        // matters more since the retry timer became 1 thread wide, not less: anything that runs
        // there now stalls every other retry's backoff rather than one lane of thirty.
        val disposition = {
            releaseAll(orderId, toRelease)
            sendFailOrder(orderId, "$stage command failed: ${cause.javaClass.simpleName}")
        }
        try {
            commandExecutor.execute(disposition)
        } catch (e: RejectedExecutionException) {
            // abandon() is called from a whenComplete callback whose own future is discarded, so
            // an exception escaping here would be swallowed entirely and the order would stay
            // PENDING with no counter and no log — the very signature this saga exists to remove.
            // Running inline is strictly better than losing the disposition: the thread this
            // falls back to is the one that was going to run it before 5d011f1 moved it off.
            log.warn("[SAGA] saga pool rejected the terminal disposition orderId={} — running inline", orderId, e)
            meterRegistry.counter("saga.command.failed", "stage", "abandon-rejected").increment()
            disposition()
        }
    }

    // Total by construction: this must never throw at its caller. abandon() calls it BEFORE
    // sendFailOrder, so an escaping exception here would skip the terminal disposition entirely
    // and strand the order in PENDING — with no counter and no log, because abandon() itself
    // runs inside a whenComplete callback whose result future is discarded.
    private fun releaseAll(orderId: String, toRelease: List<OrderItem>) {
        toRelease.forEach { item ->
            try {
                commandGateway.send<Any?>(ReleaseReservationCommand(item.itemId, item.quantity))
                    .whenComplete { _, ex -> if (ex != null) releaseFailed(item, orderId, ex) }
            } catch (e: Exception) {
                releaseFailed(item, orderId, e)
            }
        }
    }

    private fun releaseFailed(item: OrderItem, orderId: String, cause: Throwable) {
        // Reserved stock stays held. Counted rather than retried: a release that cannot be
        // applied has no second escape hatch either.
        log.error("[SAGA] compensation failed itemId={} orderId={}", item.itemId, orderId, cause)
        meterRegistry.counter("saga.command.failed", "stage", "release").increment()
    }

    private fun sendFailOrder(orderId: String, reason: String) {
        try {
            commandGateway.send<Any?>(FailOrderCommand(orderId, reason))
                .whenComplete { applied, ex ->
                    when {
                        ex != null -> failOrderFailed(orderId, ex)
                        // The command succeeded but the aggregate ignored it, because the order
                        // was no longer PENDING. No OrderFailedEvent exists, so @EndSaga will not
                        // fire and this saga_entry row will sit there forever. Unreachable today
                        // — the saga is the only sender, the completion path has already ended
                        // its own saga, and the reserve path cannot run on a terminal order — but
                        // a second sender (a timeout reaper, an admin endpoint) makes it live
                        // immediately, and it would present exactly like the original defect.
                        applied == false -> {
                            log.error(
                                "[SAGA] FailOrderCommand ignored orderId={} — order was already terminal, " +
                                    "saga will not be ended by an OrderFailedEvent", orderId,
                            )
                            meterRegistry.counter("saga.command.failed", "stage", "fail-order-ignored").increment()
                        }
                    }
                }
        } catch (e: Exception) {
            failOrderFailed(orderId, e)
        }
    }

    private fun failOrderFailed(orderId: String, cause: Throwable) {
        // Residual dead end: the order stays PENDING. There is no further escape hatch
        // that does not recurse, so this is made visible instead of handled.
        log.error("[SAGA] FailOrderCommand failed orderId={} — order remains PENDING", orderId, cause)
        meterRegistry.counter("saga.command.failed", "stage", "fail-order").increment()
    }
}
