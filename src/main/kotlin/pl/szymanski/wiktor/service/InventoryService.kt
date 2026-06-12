package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.CreateOrderReservationCommand
import pl.szymanski.wiktor.service.command.CreateOrderReservationCommandHandler
import java.util.UUID

@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val orderRepository: OrderRepository,
    private val createInventoryItemCommandHandler: CreateInventoryItemCommandHandler,
    private val createOrderReservationCommandHandler: CreateOrderReservationCommandHandler,
    @Qualifier("orderWorkerExecutor") private val orderWorkerExecutor: TaskExecutor,
    // Self-proxy so processOrder() invoked from the worker task goes through the
    // @Retryable interceptor; a direct this-call would bypass it.
    private val self: ObjectProvider<InventoryService>,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val processingTimer: Timer = Timer.builder("order.processing.time")
        .register(meterRegistry)

    fun createItem(command: CreateItemCommand): InventoryItem =
        createInventoryItemCommandHandler.handle(command)

    fun getItem(itemId: String): InventoryItem? =
        inventoryRepository.findById(itemId).orElse(null)

    fun getItems(pageable: Pageable): Page<InventoryItem> =
        inventoryRepository.findAll(pageable)

    fun getOrder(orderId: String): Order? =
        orderRepository.findById(orderId).orElse(null)

    fun acceptOrder(command: CreateOrderReservationCommand): String {
        val orderId = UUID.randomUUID().toString()
        log.info("[ORDER] accepted orderId={} userId={} itemCount={} correlationId={}", orderId, command.userId, command.items.size, command.correlationId)
        // Committed before the task is submitted, so the worker always sees the row.
        orderRepository.save(Order(orderId = orderId, userId = command.userId))
        try {
            orderWorkerExecutor.execute {
                val sample = Timer.start()
                try {
                    self.getObject().processOrder(orderId, command)
                } catch (e: Exception) {
                    log.warn("[ORDER] rejected orderId={} reason={} correlationId={}", orderId, e.message, command.correlationId)
                    meterRegistry.counter("inventory.exception", "type", e.javaClass.simpleName).increment()
                    orderRepository.updateStatus(orderId, OrderStatus.REJECTED.name, e.message)
                } finally {
                    sample.stop(processingTimer)
                }
            }
        } catch (e: TaskRejectedException) {
            log.warn("[ORDER] worker queue full, rejecting orderId={} correlationId={}", orderId, command.correlationId)
            orderRepository.updateStatus(orderId, OrderStatus.REJECTED.name, "worker queue full")
            throw e
        }
        return orderId
    }

    @Retryable(
        includes = [OptimisticLockingFailureException::class, PessimisticLockingFailureException::class],
        maxRetries = 4,
        delay = 25,
        multiplier = 2.0,
        maxDelay = 500,
    )
    fun processOrder(orderId: String, command: CreateOrderReservationCommand) =
        createOrderReservationCommandHandler.handle(orderId, command)
}
