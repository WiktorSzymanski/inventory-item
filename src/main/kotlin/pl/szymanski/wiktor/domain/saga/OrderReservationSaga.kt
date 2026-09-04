package pl.szymanski.wiktor.domain.saga

import com.fasterxml.jackson.annotation.JsonAutoDetect
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.Timestamp
import org.axonframework.eventhandling.gateway.EventGateway
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
import pl.szymanski.wiktor.domain.SagaReserveAbandonedEvent
import pl.szymanski.wiktor.service.command.CompleteOrderCommand
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.ReleaseReservationCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * ES-2-parallel: the order's lines are reserved CONCURRENTLY. ES-2 dispatches line k+1 only after
 * line k's InventoryReservedEvent has come back through the processor, so an L-line order costs L
 * sequential round trips of (command dispatch -> aggregate load -> append -> processor read).
 * Here every line is submitted to the command pool from the start handler and they contend for
 * their aggregates at the same time.
 *
 * Two consequences drive everything below.
 *
 * 1. ARRIVAL ORDER CARRIES NO INFORMATION. ES-2 could write `items[currentIndex]` because exactly
 *    one command was ever in flight; the reply had to belong to the line just dispatched. With L
 *    in flight, the reply says which line it is and nothing else does — so compensation is built
 *    from the events that arrived, not from a position in [items].
 *
 * 2. THE SAGA CANNOT END ON THE FIRST FAILURE. ES-2 ends inline the moment a line is rejected,
 *    which is safe only because nothing else is outstanding. Doing that here would drop the
 *    correlationId association while L-1 reserves are still running; each one that then succeeded
 *    would append InventoryReservedEvent with no saga left to release it, and that stock would be
 *    held until the process was restarted. The saga therefore counts its lines down and takes its
 *    terminal decision once — in [settle] — when the last one has reported.
 *
 * The saga-store cost is what the branch exists to measure. The write COUNT per event is
 * unchanged: Axon commits a saga once per UnitOfWork, with no dirty check, so every event routed
 * here rewrites the row. What changes is that a saga's events can now co-occur — ES-2's chain
 * guarantees at most one of them per batch, so `andBatchSize(100)` on the order-saga processor is
 * structurally unusable there, while here several lines of one order can land in the same batch
 * and collapse into a single UPDATE. How often that happens depends on how the segment's stream
 * interleaves with other orders, which is a measurement, not a claim.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Saga
@ProcessingGroup("order-saga")
class OrderReservationSaga {

    @Autowired @Transient
    private lateinit var commandGateway: CommandGateway

    // Publishes the abandonment of a reserve that never reached its aggregate, so the saga can
    // settle that line when the event is read back. See SagaReserveAbandonedEvent.
    @Autowired @Transient
    private lateinit var eventGateway: EventGateway

    // Commands are submitted here so the saga processor thread is never blocked waiting
    // for per-aggregate locks or JDBC writes. The lock wait happens on a pool thread;
    // the result event arrives in the event store when the command succeeds.
    @Autowired @Transient
    @Qualifier("sagaCommandExecutor")
    private lateinit var commandExecutor: Executor

    @Autowired @Transient
    private lateinit var meterRegistry: MeterRegistry

    companion object {
        private val log = LoggerFactory.getLogger(OrderReservationSaga::class.java)
    }

    private lateinit var orderId: String

    // Read once, in the start handler, and never again — the countdown below is what the later
    // handlers use. Kept as state rather than a local so the serialized saga carries the same
    // fields ES-2's does: the row this branch is measured on has to differ in the dispatch shape
    // alone, not in its payload size.
    private lateinit var items: List<OrderItem>

    private lateinit var correlationId: UUID

    // Lines dispatched but not yet reported, counting down as each settles. Replaces ES-2's
    // `currentIndex`, which was both the cursor and the progress counter because the two could
    // not diverge when only one command was ever in flight.
    private var outstanding: Int = 0

    // Built from InventoryReservedEvent, not from [items] — see the class note.
    private val reservedItems = mutableListOf<OrderItem>()

    // First rejection wins; the rest are logged. Non-null means the order will be failed rather
    // than completed once the countdown reaches zero.
    private var failureReason: String? = null

    // Whether the failure that decided the order was a command that never ran, rather than an
    // out-of-stock rejection. Keeps outcome="command_failed" meaning the same thing it means on
    // ES-2, where that tag came from the OrderFailedEvent round trip.
    private var abandoned: Boolean = false

    // An OrderFailedEvent for this order already exists, so the order is decided and the only
    // thing left to do is release what the lines that did land are holding.
    private var orderTerminated: Boolean = false

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
        outstanding = items.size
        SagaLifecycle.associateWith("correlationId", correlationId.toString())
        log.info("[SAGA] start orderId={} items={}", orderId, items.size)
        dispatchAllReservations()
        // An order with no lines has nothing to report back, so the countdown is already at zero
        // and this settles it immediately. Unreachable through the API today — but where ES-2
        // walked off the end of `items` and failed loudly, a countdown that starts at zero and is
        // never checked leaves a saga sitting there forever, which is the quieter of the two.
        settle()
    }

    private fun dispatchAllReservations() {
        val orderIdCopy = orderId
        val correlationIdCopy = correlationId
        // One task per line: the lines wait for each other's aggregate locks in parallel on the
        // command pool rather than end to end. The saga processor thread only submits, so it is
        // free again after L cheap enqueues regardless of how long the reservations take.
        items.forEach { item ->
            try {
                commandExecutor.execute { dispatchReservation(orderIdCopy, item, correlationIdCopy) }
            } catch (e: RejectedExecutionException) {
                // The pool is unbounded, so this means shutdown. [outstanding] already counts this
                // line, and nothing else will ever report it — abandon it here or the saga waits
                // for a command that was never dispatched.
                abandonReservation(orderIdCopy, item, correlationIdCopy, e)
            }
        }
    }

    @SagaEventHandler(associationProperty = "correlationId")
    fun on(event: InventoryReservedEvent) {
        // From the event, never `items[currentIndex]`: with every line in flight the arrival order
        // says nothing about which line reported, and compensation has to release the quantity
        // that was actually taken.
        reservedItems.add(OrderItem(event.id, event.quantity))
        outstanding--
        log.debug("[SAGA] reserved itemId={} outstanding={} orderId={}", event.id, outstanding, orderId)
        settle()
    }

    @SagaEventHandler(associationProperty = "correlationId")
    fun on(event: InventoryReservationFailedEvent) {
        outstanding--
        log.warn(
            "[SAGA] reservation failed itemId={} orderId={} outstanding={} reason={}",
            event.id, orderId, outstanding, event.reason,
        )
        if (failureReason == null) failureReason = event.reason
        settle()
    }

    /**
     * A reserve that never reached its aggregate, reported by the pool thread that gave up on it.
     * Settles exactly like an out-of-stock rejection except for the outcome tag: this order was
     * lost to infrastructure, not to inventory, and the two must stay distinguishable in
     * `saga.completed`.
     */
    @SagaEventHandler(associationProperty = "correlationId")
    fun on(event: SagaReserveAbandonedEvent) {
        outstanding--
        log.warn(
            "[SAGA] reserve abandoned itemId={} orderId={} outstanding={} reason={}",
            event.id, orderId, outstanding, event.reason,
        )
        abandoned = true
        if (failureReason == null) failureReason = event.reason
        settle()
    }

    /**
     * The order was failed by something other than this saga's own terminal disposition — today
     * only the complete-stage abandon path, which fails the order from a pool thread after the
     * saga has ended, and a second sender (a timeout reaper, an admin endpoint) the moment one
     * exists.
     *
     * NOT @EndSaga, which is what ES-2 uses: ending on this event would strand every line still in
     * flight. The order is already decided, so this records that and lets the countdown finish;
     * [settle] then releases whatever landed. The saga's OWN failure path never arrives here — it
     * ends inline before sending FailOrderCommand, so by the time that event is read back the
     * associations are gone.
     */
    @SagaEventHandler(associationProperty = "orderId")
    fun on(event: OrderFailedEvent) {
        log.warn("[SAGA] order failed outside the saga orderId={} reason={}", event.orderId, event.reason)
        orderTerminated = true
        settle()
    }

    /**
     * The saga's single terminal decision point, taken once every dispatched line has reported.
     *
     * Ordering inside each branch matters: the disposition is SUBMITTED, never awaited, and the
     * lifecycle is recorded before the command's verdict is known. If a disposition later fails,
     * `saga.command.failed` is what makes that visible — the saga has already ended by then and
     * cannot be re-tagged.
     */
    private fun settle() {
        if (outstanding > 0) return

        val orderIdCopy = orderId
        val toRelease = reservedItems.toList()
        val reason = failureReason
        when {
            orderTerminated -> {
                // No FailOrderCommand: the order is already FAILED, so a second one would be
                // ignored by the aggregate and counted as fail-order-ignored for no reason.
                log.info("[SAGA] releasing {} lines of an externally failed orderId={}", toRelease.size, orderIdCopy)
                submitDisposition(orderIdCopy) { releaseAll(orderIdCopy, toRelease) }
                recordSagaEnd("command_failed")
            }
            reason != null -> {
                log.info("[SAGA] failing orderId={} releasing={} reason={}", orderIdCopy, toRelease.size, reason)
                submitDisposition(orderIdCopy) {
                    releaseAll(orderIdCopy, toRelease)
                    sendFailOrder(orderIdCopy, reason)
                }
                recordSagaEnd(if (abandoned) "command_failed" else "failed")
            }
            else -> {
                log.info("[SAGA] all {} items reserved, completing orderId={}", toRelease.size, orderIdCopy)
                submitDisposition(orderIdCopy) {
                    dispatchOrAbandon(orderIdCopy, toRelease, "complete") {
                        commandGateway.send<Any?>(CompleteOrderCommand(orderIdCopy))
                    }
                }
                recordSagaEnd("completed")
            }
        }
        SagaLifecycle.end()
    }

    /**
     * Terminal dispositions go to the command pool rather than running on the saga processor
     * thread: an L-line release is L aggregate loads and appends, and doing that here would stall
     * the segment — and every other saga in it — for the duration.
     *
     * The inline fallback is for the one case the unbounded pool can still reject, shutdown. It
     * blocks the segment, which is strictly better than losing the disposition: an order that was
     * decided but never told anyone stays PENDING with reserved stock behind it.
     */
    private fun submitDisposition(orderId: String, disposition: () -> Unit) {
        try {
            commandExecutor.execute(disposition)
        } catch (e: RejectedExecutionException) {
            log.warn("[SAGA] saga pool rejected the terminal disposition orderId={} — running inline", orderId, e)
            meterRegistry.counter("saga.command.failed", "stage", "abandon-rejected").increment()
            disposition()
        }
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

    private fun dispatchReservation(orderId: String, item: OrderItem, correlationId: UUID) {
        try {
            commandGateway.send<Any?>(SagaReserveItemCommand(item.itemId, item.quantity, correlationId))
                .whenComplete { _, ex -> if (ex != null) abandonReservation(orderId, item, correlationId, ex) }
        } catch (e: Exception) {
            // `send` can also fail SYNCHRONOUSLY, before a future exists at all, from a dispatch
            // interceptor or a payload that will not serialize. Left to escape, it would land in
            // the executor's uncaught handler, settle nothing, and leave this line outstanding
            // forever — invisible, because the executor discards it.
            abandonReservation(orderId, item, correlationId, e)
        }
    }

    /**
     * Terminal disposition for a reserve that failed AFTER ConcurrencyRetryScheduler gave up.
     *
     * Runs on a commandExecutor pool thread, outside saga scope, so it cannot touch SagaLifecycle
     * or the countdown. It also must NOT fail the order the way ES-2's abandon() does: other lines
     * of this order are still in flight, and failing it from here would end the saga underneath
     * them. So the line is settled the only way an off-thread failure can be — as an event
     * carrying the correlationId back into the saga, which then compensates whatever landed and
     * fails the order once.
     */
    private fun abandonReservation(orderId: String, item: OrderItem, correlationId: UUID, cause: Throwable) {
        log.error("[SAGA] reserve command failed orderId={} itemId={}", orderId, item.itemId, cause)
        meterRegistry.counter("saga.command.failed", "stage", "reserve").increment()
        try {
            eventGateway.publish(
                SagaReserveAbandonedEvent(
                    item.itemId,
                    correlationId,
                    "reserve command failed: ${cause.javaClass.simpleName}",
                ),
            )
        } catch (e: Exception) {
            // Residual dead end, and the only one this design has: the line stays outstanding, so
            // the saga waits for an event that will now never exist and its row survives the run.
            // There is no second escape hatch that does not recurse into the same append, so this
            // is made visible instead of handled.
            log.error(
                "[SAGA] could not publish the abandonment orderId={} itemId={} — the saga will not end",
                orderId, item.itemId, e,
            )
            meterRegistry.counter("saga.command.failed", "stage", "abandon-publish").increment()
        }
    }

    // `commandGateway.send` can fail two ways, and both must reach the same disposition:
    // asynchronously (the usual case — retries exhausted) or SYNCHRONOUSLY, before a future
    // exists at all. Used for the completion command only: by then the saga has ended, so its
    // failure has to be dispositioned off-thread rather than settled as a line.
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

    // Terminal disposition for a command that failed after the saga has already ended, which on
    // this branch is the completion command alone. Runs on a commandExecutor pool thread, outside
    // saga scope, so it must not touch SagaLifecycle: it releases what the order was holding and
    // sends FailOrderCommand. The resulting OrderFailedEvent finds no saga — this one ended
    // before the command was sent — which is why on(OrderFailedEvent) does not double-compensate.
    private fun abandon(orderId: String, toRelease: List<OrderItem>, stage: String, cause: Throwable) {
        log.error("[SAGA] {} command failed orderId={} — failing order", stage, orderId, cause)
        meterRegistry.counter("saga.command.failed", "stage", stage).increment()
        // Handed back to the command pool rather than run inline. When retries are what exhausted,
        // Axon's RetryingCallback completes the future on the retryExecutor
        // (CommandGatewayConfig.RETRY_POOL_SIZE threads), and compensating an N-line order there
        // means N sequential aggregate loads + appends on 1 of those. Retries for every OTHER
        // in-flight command queue behind that, burn their attempts against the 500ms backoff cap,
        // and exhaust in turn — a contention spike amplifying itself into a rejection cascade. The
        // pool this resubmits to is the one already sized for blocking command dispatch. That
        // matters more since the retry timer became 1 thread wide, not less: anything that runs
        // there now stalls every other retry's backoff rather than one lane of thirty.
        submitDisposition(orderId) {
            releaseAll(orderId, toRelease)
            sendFailOrder(orderId, "$stage command failed: ${cause.javaClass.simpleName}")
        }
    }

    // Total by construction: this must never throw at its caller. Every disposition calls it
    // BEFORE sendFailOrder, so an escaping exception here would skip the rest of the disposition
    // entirely and strand the order in PENDING — with no counter and no log, because the
    // disposition runs on a pool thread whose result nobody reads.
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
                        // was no longer PENDING. No OrderFailedEvent exists, so nothing else
                        // reports that this order never reached a terminal status. Unreachable
                        // today — the saga is the only sender and it decides an order once — but a
                        // second sender (a timeout reaper, an admin endpoint) makes it live
                        // immediately, and it would present exactly like the original defect.
                        applied == false -> {
                            log.error(
                                "[SAGA] FailOrderCommand ignored orderId={} — order was already terminal", orderId,
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
