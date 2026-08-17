package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.dao.OptimisticLockingFailureException
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.FailOrderCommandHandler
import pl.szymanski.wiktor.service.command.ReserveOrderItemsCommandHandler
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Retry POLICY, which TO-3-mod must not change: 5 total attempts with 25/50/100/200 ms between
 * them, and the same counter arithmetic TO-3 produced. Only the thread the waiting happens on is
 * different, and that is [OrderRetryUnblocksWorkerTest]'s job.
 *
 * No Spring context: with the retry loop explicit rather than an AOP interceptor, the service is
 * just a constructor call. The scheduler here records each delay and runs the retry INLINE, so the
 * whole loop is deterministic and nothing sleeps.
 */
class InventoryServiceRetryTest {

    private val reserveOrderItemsCommandHandler: ReserveOrderItemsCommandHandler = mockk()
    private val failOrderCommandHandler: FailOrderCommandHandler = mockk(relaxed = true)
    private val meterRegistry = SimpleMeterRegistry()
    private val scheduledDelaysMs = mutableListOf<Long>()

    private lateinit var inventoryService: InventoryService

    @BeforeEach
    fun setUp() {
        inventoryService = InventoryService(
            inventoryRepository = mockk<InventoryRepository>(),
            orderRepository = mockk<OrderRepository>(),
            createInventoryItemCommandHandler = mockk<CreateInventoryItemCommandHandler>(),
            createOrderCommandHandler = mockk<CreateOrderCommandHandler>(),
            reserveOrderItemsCommandHandler = reserveOrderItemsCommandHandler,
            failOrderCommandHandler = failOrderCommandHandler,
            orderWorkerExecutor = SyncTaskExecutor(),
            // Records the delay and runs the retry inline, so the loop is deterministic. Which pool
            // the real scheduler puts it on is OrderRetryPoolTopologyTest's job.
            retryScheduler = { delayMs, task ->
                scheduledDelaysMs += delayMs
                task.run()
            },
            meterRegistry = meterRegistry,
        )
    }

    private val event = OrderCreatedEvent(
        orderId = "ORDER-1",
        userId = "USER-1",
        items = listOf(ReservedItem("ITEM-001", 1)),
        correlationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
    )

    private fun retryCount() = meterRegistry.counter("inventory.optimistic.retry").count()
    private fun exhaustedCount() = meterRegistry.counter("inventory.optimistic.exhausted").count()
    private fun completedCount(outcome: String, reason: String) =
        meterRegistry.counter("orders.completed", "outcome", outcome, "reason", reason).count()

    @Test
    fun `a conflict is retried and the order confirms on the second attempt`() {
        every { reserveOrderItemsCommandHandler.handle(event) } throws
            OptimisticLockingFailureException("conflict") andThen Unit

        inventoryService.onOrderCreated(event)

        verify(exactly = 2) { reserveOrderItemsCommandHandler.handle(event) }
        assertEquals(listOf(25L), scheduledDelaysMs)
        verify(exactly = 0) { failOrderCommandHandler.handle(any()) }
        assertEquals(1.0, retryCount())
        assertEquals(0.0, exhaustedCount())
        assertEquals(1.0, completedCount("confirmed", "none"))
    }

    @Test
    fun `a persistent conflict is attempted five times with the unchanged backoff, then rejected`() {
        every { reserveOrderItemsCommandHandler.handle(event) } throws
            OptimisticLockingFailureException("conflict")

        inventoryService.onOrderCreated(event)

        verify(exactly = 5) { reserveOrderItemsCommandHandler.handle(event) }
        assertEquals(listOf(25L, 50L, 100L, 200L), scheduledDelaysMs)
        verify(exactly = 1) { failOrderCommandHandler.handle(any<FailOrderCommand>()) }
        // Identical to TO-3: every failed attempt counts as a retry, the last one included.
        assertEquals(5.0, retryCount())
        assertEquals(1.0, exhaustedCount())
        assertEquals(1.0, completedCount("rejected", "optimistic_exhausted"))
    }

    @Test
    fun `accumulated backoff is priced once, at the terminal outcome`() {
        every { reserveOrderItemsCommandHandler.handle(event) } throws
            OptimisticLockingFailureException("conflict")

        inventoryService.onOrderCreated(event)

        val backoff = meterRegistry.timer("order.retry.backoff.time", "outcome", "rejected")
        assertEquals(1L, backoff.count())
        assertEquals(375.0, backoff.totalTime(TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a business rejection is not retried`() {
        every { reserveOrderItemsCommandHandler.handle(event) } throws
            InsufficientStockException("out of stock")

        inventoryService.onOrderCreated(event)

        verify(exactly = 1) { reserveOrderItemsCommandHandler.handle(event) }
        assertEquals(emptyList<Long>(), scheduledDelaysMs)
        verify(exactly = 1) { failOrderCommandHandler.handle(any<FailOrderCommand>()) }
        assertEquals(0.0, retryCount())
        assertEquals(0.0, exhaustedCount())
        assertEquals(1.0, completedCount("rejected", "insufficient_stock"))
    }
}
