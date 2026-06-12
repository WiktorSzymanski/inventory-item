package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.Counter
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
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.CreateOrderReservationCommand
import pl.szymanski.wiktor.service.command.CreateOrderReservationCommandHandler
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    private val processingTimer: Timer = meterRegistry.timer("order.processing.time")
    private val queueWaitTimer: Timer = meterRegistry.timer("order.queue.wait")
    private val optimisticRetryCounter: Counter = meterRegistry.counter("inventory.optimistic.retry")
    private val optimisticExhaustedCounter: Counter = meterRegistry.counter("inventory.optimistic.exhausted")

    private fun e2eTimer(outcome: String): Timer =
        meterRegistry.timer("order.e2e.time", "outcome", outcome)

    private fun completedCounter(outcome: String, reason: String): Counter =
        meterRegistry.counter("orders.completed", "outcome", outcome, "reason", reason)

    private fun rejectionReason(e: Exception): String = when (e) {
        is InsufficientStockException -> "insufficient_stock"
        is NotFoundException -> "not_found"
        is OptimisticLockingFailureException, is PessimisticLockingFailureException -> "optimistic_exhausted"
        is TaskRejectedException -> "queue_full"
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

    fun acceptOrder(command: CreateOrderReservationCommand): String {
        val acceptedAtNs = System.nanoTime()
        val orderId = UUID.randomUUID().toString()
        log.info("[ORDER] accepted orderId={} userId={} itemCount={} correlationId={}", orderId, command.userId, command.items.size, command.correlationId)
        // Committed before the task is submitted, so the worker always sees the row.
        orderRepository.save(Order(orderId = orderId, userId = command.userId))
        try {
            orderWorkerExecutor.execute { runOrderTask(orderId, command, acceptedAtNs) }
        } catch (e: TaskRejectedException) {
            log.warn("[ORDER] worker queue full, rejecting orderId={} correlationId={}", orderId, command.correlationId)
            orderRepository.updateStatus(orderId, OrderStatus.REJECTED, "worker queue full")
            completedCounter("rejected", rejectionReason(e)).increment()
            e2eTimer("rejected").record(System.nanoTime() - acceptedAtNs, TimeUnit.NANOSECONDS)
            throw e
        }
        return orderId
    }

    private fun runOrderTask(orderId: String, command: CreateOrderReservationCommand, acceptedAtNs: Long) {
        queueWaitTimer.record(System.nanoTime() - acceptedAtNs, TimeUnit.NANOSECONDS)
        val sample = Timer.start()
        var outcome = "confirmed"
        try {
            self.getObject().processOrder(orderId, command)
            completedCounter("confirmed", "none").increment()
        } catch (e: Exception) {
            outcome = "rejected"
            log.warn("[ORDER] rejected orderId={} reason={} correlationId={}", orderId, e.message, command.correlationId)
            meterRegistry.counter("inventory.exception", "type", e.javaClass.simpleName).increment()
            if (e is OptimisticLockingFailureException || e is PessimisticLockingFailureException) {
                optimisticExhaustedCounter.increment()
            }
            completedCounter("rejected", rejectionReason(e)).increment()
            orderRepository.updateStatus(orderId, OrderStatus.REJECTED, e.message)
        } finally {
            sample.stop(processingTimer)
            e2eTimer(outcome).record(System.nanoTime() - acceptedAtNs, TimeUnit.NANOSECONDS)
        }
    }

    @Retryable(
        includes = [OptimisticLockingFailureException::class, PessimisticLockingFailureException::class],
        maxRetries = 4,
        delay = 25,
        multiplier = 2.0,
        maxDelay = 500,
    )
    fun processOrder(orderId: String, command: CreateOrderReservationCommand) =
        try {
            createOrderReservationCommandHandler.handle(orderId, command)
        } catch (e: Exception) {
            if (e is OptimisticLockingFailureException || e is PessimisticLockingFailureException) {
                optimisticRetryCounter.increment()
            }
            throw e
        }
}
