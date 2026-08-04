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
import java.util.concurrent.Executor

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Saga
@ProcessingGroup("order-saga")
class OrderReservationSaga {

    @Autowired @Transient
    private lateinit var commandGateway: CommandGateway

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
        sendNextReservation()
    }

    @SagaEventHandler(associationProperty = "correlationId")
    fun on(event: InventoryReservedEvent) {
        reservedItems.add(items[currentIndex])
        currentIndex++
        log.debug("[SAGA] reserved itemId={} ({}/{}) orderId={}", event.id, currentIndex, items.size, orderId)
        if (currentIndex < items.size) {
            sendNextReservation()
        } else {
            log.info("[SAGA] all items reserved, completing orderId={}", orderId)
            // reservedItems.add above already ran, so this snapshot is the WHOLE order, not a prefix.
            val orderIdCopy = orderId
            val toRelease = reservedItems.toList()
            commandExecutor.execute {
                commandGateway.send<Any?>(CompleteOrderCommand(orderIdCopy))
                    .whenComplete { _, ex -> if (ex != null) abandon(orderIdCopy, toRelease, "complete", ex) }
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

    private fun sendNextReservation() {
        val item = items[currentIndex]
        // Snapshotted on the saga processor thread. Safe because reservations are strictly
        // sequential — exactly one command is in flight per saga, and the only thing that mutates
        // reservedItems is the success handler for the very command being dispatched here.
        val orderIdCopy = orderId
        val toRelease = reservedItems.toList()
        log.debug("[SAGA] reserving itemId={} ({}/{}) orderId={}", item.itemId, currentIndex + 1, items.size, orderId)
        commandExecutor.execute {
            commandGateway.send<Any?>(SagaReserveItemCommand(item.itemId, item.quantity, correlationId))
                .whenComplete { _, ex -> if (ex != null) abandon(orderIdCopy, toRelease, "reserve", ex) }
        }
    }

    // Terminal disposition for a command that failed AFTER ConcurrencyRetryScheduler gave up.
    // Runs on a commandExecutor pool thread, outside saga scope, so it must not touch
    // SagaLifecycle: it sends FailOrderCommand instead and lets the resulting OrderFailedEvent
    // come back to on(OrderFailedEvent) on the saga processor thread.
    private fun abandon(orderId: String, toRelease: List<OrderItem>, stage: String, cause: Throwable) {
        log.error("[SAGA] {} command failed orderId={} — failing order", stage, orderId, cause)
        meterRegistry.counter("saga.command.failed", "stage", stage).increment()
        releaseAll(orderId, toRelease)
        sendFailOrder(orderId, "$stage command failed: ${cause.javaClass.simpleName}")
    }

    private fun releaseAll(orderId: String, toRelease: List<OrderItem>) {
        toRelease.forEach { item ->
            commandGateway.send<Any?>(ReleaseReservationCommand(item.itemId, item.quantity))
                .whenComplete { _, ex ->
                    if (ex != null) {
                        // Reserved stock stays held. Counted rather than retried: a release that
                        // cannot be applied has no second escape hatch either.
                        log.error("[SAGA] compensation failed itemId={} orderId={}", item.itemId, orderId, ex)
                        meterRegistry.counter("saga.command.failed", "stage", "release").increment()
                    }
                }
        }
    }

    private fun sendFailOrder(orderId: String, reason: String) {
        commandGateway.send<Any?>(FailOrderCommand(orderId, reason))
            .whenComplete { _, ex ->
                if (ex != null) {
                    // Residual dead end: the order stays PENDING. There is no further escape hatch
                    // that does not recurse, so this is made visible instead of handled.
                    log.error("[SAGA] FailOrderCommand failed orderId={} — order remains PENDING", orderId, ex)
                    meterRegistry.counter("saga.command.failed", "stage", "fail-order").increment()
                }
            }
    }
}
