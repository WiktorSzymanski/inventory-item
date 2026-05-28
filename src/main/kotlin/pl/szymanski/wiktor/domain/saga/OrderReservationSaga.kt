package pl.szymanski.wiktor.domain.saga

import com.fasterxml.jackson.annotation.JsonAutoDetect
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.config.ProcessingGroup
import org.axonframework.modelling.saga.SagaEventHandler
import org.axonframework.modelling.saga.SagaLifecycle
import org.axonframework.modelling.saga.StartSaga
import org.axonframework.spring.stereotype.Saga
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
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

    companion object {
        private val log = LoggerFactory.getLogger(OrderReservationSaga::class.java)
    }

    private lateinit var orderId: String
    private lateinit var items: List<OrderItem>
    private lateinit var correlationId: UUID
    private var currentIndex: Int = 0
    private val reservedItems = mutableListOf<OrderItem>()

    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    fun on(event: OrderCreatedEvent) {
        orderId = event.orderId
        items = event.items
        correlationId = event.correlationId
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
        SagaLifecycle.end()
    }

    private fun sendNextReservation() {
        val item = items[currentIndex]
        log.debug("[SAGA] reserving itemId={} ({}/{}) orderId={}", item.itemId, currentIndex + 1, items.size, orderId)
        commandExecutor.execute {
            commandGateway.send<Any?>(SagaReserveItemCommand(item.itemId, item.quantity, correlationId))
        }
    }
}
