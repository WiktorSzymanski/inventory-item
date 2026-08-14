package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailOrderCommandHandler
import pl.szymanski.wiktor.service.command.ReserveOrderItemsCommandHandler
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The point of TO-3-mod: a worker thread parked in retry backoff is a worker thread not doing work.
 *
 * ONE worker thread, two orders. The first conflicts once and must wait out its 25 ms backoff; the
 * second is queued behind it and needs nothing but a thread. Because the backoff is non-blocking
 * the thread is released and the second order runs during the wait, so the reserve handler sees
 * A, B, A.
 *
 * On stock TO-3 this test fails with A, A, B — verified before the change was written. Spring's
 * `@Retryable` interceptor sleeps on the worker, and with one worker there is nobody left to pick
 * up order B until the retry has finished.
 */
class OrderRetryUnblocksWorkerTest {

    private val reserveOrderItemsCommandHandler: ReserveOrderItemsCommandHandler = mockk()

    private val workerExecutor = ThreadPoolTaskExecutor().apply {
        // Exactly one thread: that is the whole experiment.
        corePoolSize = 1
        maxPoolSize = 1
        setThreadNamePrefix("order-worker-")
        initialize()
    }
    private val retryExecutor = ScheduledThreadPoolExecutor(1)
    private val retryScheduler = DelayedOrderRetryScheduler(retryExecutor)

    private val inventoryService = InventoryService(
        inventoryRepository = mockk<InventoryRepository>(),
        orderRepository = mockk<OrderRepository>(),
        createInventoryItemCommandHandler = mockk<CreateInventoryItemCommandHandler>(),
        createOrderCommandHandler = mockk<CreateOrderCommandHandler>(),
        reserveOrderItemsCommandHandler = reserveOrderItemsCommandHandler,
        failOrderCommandHandler = mockk(relaxed = true),
        orderWorkerExecutor = workerExecutor,
        retryScheduler = retryScheduler,
        meterRegistry = SimpleMeterRegistry(),
    )

    @AfterEach
    fun tearDown() {
        retryScheduler.close()
        workerExecutor.shutdown()
    }

    private fun eventFor(orderId: String) = OrderCreatedEvent(
        orderId = orderId,
        userId = "USER-1",
        items = listOf(ReservedItem("ITEM-001", 1)),
        correlationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `an order waiting out its backoff does not hold the only worker thread`() {
        val attempts = CopyOnWriteArrayList<String>()
        val allAttemptsSeen = CountDownLatch(3)
        var firstOrderHasFailedOnce = false

        every { reserveOrderItemsCommandHandler.handle(any()) } answers {
            val event = firstArg<OrderCreatedEvent>()
            attempts += event.orderId
            allAttemptsSeen.countDown()
            if (event.orderId == "ORDER-A" && !firstOrderHasFailedOnce) {
                firstOrderHasFailedOnce = true
                throw OptimisticLockingFailureException("conflict")
            }
        }

        // Queued in this order, so the single worker necessarily picks A first.
        inventoryService.onOrderCreated(eventFor("ORDER-A"))
        inventoryService.onOrderCreated(eventFor("ORDER-B"))

        assertTrue(
            allAttemptsSeen.await(5, TimeUnit.SECONDS),
            "expected 3 reserve attempts, saw $attempts",
        )
        assertEquals(listOf("ORDER-A", "ORDER-B", "ORDER-A"), attempts)
    }
}
