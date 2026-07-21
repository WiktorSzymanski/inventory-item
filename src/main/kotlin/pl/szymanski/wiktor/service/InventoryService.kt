package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.resilience.annotation.Retryable
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
    // Self-proxy so processOrder() invoked from the worker task goes through the
    // @Retryable interceptor; a direct this-call would bypass it.
    private val self: ObjectProvider<InventoryService>,
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
        orderWorkerExecutor.execute { runOrderTask(event, acceptedAtNs) }
        // TODO: Ponoć zrobienie tego przez Async odrazu zwraca i nie powtarza jeśli się nie powiedzie, sam handler też jest async więc czemu bezpośrednio w nim nie może być kod który jak zrobi fail to oznaczy event za nie dostarczony.
    }

    private fun runOrderTask(event: OrderCreatedEvent, acceptedAtNs: Long) {
        if (acceptedAtNs >= 0) {
            queueWaitTimer.record(System.nanoTime() - acceptedAtNs, TimeUnit.NANOSECONDS)
        }
        val sample = Timer.start()
        var outcome = "confirmed"
        try {
            self.getObject().processOrder(event)
            completedCounter("confirmed", "none").increment()
        } catch (e: Exception) {
            outcome = "rejected"
            log.warn("[ORDER] rejected orderId={} reason={} correlationId={}", event.orderId, e.message, event.correlationId)
            meterRegistry.counter("inventory.exception", "type", e.javaClass.simpleName).increment()
            if (e is OptimisticLockingFailureException || e is PessimisticLockingFailureException) {
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
        } finally {
            sample.stop(processingTimer)
            if (acceptedAtNs >= 0) {
                e2eTimer(outcome).record(System.nanoTime() - acceptedAtNs, TimeUnit.NANOSECONDS)
            }
        }
    }

    @Retryable(
        includes = [OptimisticLockingFailureException::class, PessimisticLockingFailureException::class],
        maxRetries = 4,
        delay = 25,
        multiplier = 2.0,
        maxDelay = 500,
    )
    fun processOrder(event: OrderCreatedEvent) =
        try {
            reserveOrderItemsCommandHandler.handle(event)
        } catch (e: Exception) {
            if (e is OptimisticLockingFailureException || e is PessimisticLockingFailureException) {
                optimisticRetryCounter.increment()
            }
            throw e
        }
}
