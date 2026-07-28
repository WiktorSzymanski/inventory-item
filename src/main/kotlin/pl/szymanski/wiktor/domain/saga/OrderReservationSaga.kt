package pl.szymanski.wiktor.domain.saga

import com.fasterxml.jackson.annotation.JsonAutoDetect
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.Timestamp
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
            commandExecutor.execute {
                commandGateway.send<Any?>(CompleteOrderCommand(orderId))
            }
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
            toRelease.forEach { item ->
                runCatching {
                    commandGateway.send<Any?>(ReleaseReservationCommand(item.itemId, item.quantity))
                }.onFailure { ex -> log.error("[SAGA] compensation failed itemId={} orderId={}", item.itemId, orderIdCopy, ex) }
            }
            commandGateway.send<Any?>(FailOrderCommand(orderIdCopy, failReason))
        }
        recordSagaEnd("failed")
        SagaLifecycle.end()
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
        log.debug("[SAGA] reserving itemId={} ({}/{}) orderId={}", item.itemId, currentIndex + 1, items.size, orderId)
        commandExecutor.execute {
            commandGateway.send<Any?>(SagaReserveItemCommand(item.itemId, item.quantity, correlationId))
        }
    }
}
