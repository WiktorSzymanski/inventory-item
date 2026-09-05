package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
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
import java.util.concurrent.TimeUnit

/**
 * A worker thread parked in retry backoff is a worker thread not doing work — and on this branch
 * that pool is the ONLY pool, so the property matters more here than anywhere.
 *
 * ONE thread, two orders. The first conflicts once and must wait out its 50 ms backoff; the second is
 * queued behind it and needs nothing but a thread. Because the backoff is served in the pool's
 * DelayedWorkQueue rather than by sleeping, the thread is released and the second order runs during
 * the wait, so the reserve handler sees A, B, A.
 *
 * The single thread is what makes this a real assertion. If a retry ever held a thread for the length
 * of its backoff — a `Thread.sleep` in a decorator, a scheduler that runs inline — this branch would
 * have no worker left at all, where the two-pool topology would still have 150 of them.
 *
 * The ordering also pins the queue discipline: A's retry becomes due 50 ms after B was submitted, so
 * B goes first. A retry does not jump ahead of work already queued.
 */
class OrderRetryUnblocksWorkerTest {

    private val reserveOrderItemsCommandHandler: ReserveOrderItemsCommandHandler = mockk()

    // Exactly one thread, serving first attempts and retries alike: that is the whole experiment.
    private val pool = OrderWorkerPool(threads = 1)

    private val inventoryService = InventoryService(
        inventoryRepository = mockk<InventoryRepository>(),
        orderRepository = mockk<OrderRepository>(),
        createInventoryItemCommandHandler = mockk<CreateInventoryItemCommandHandler>(),
        createOrderCommandHandler = mockk<CreateOrderCommandHandler>(),
        reserveOrderItemsCommandHandler = reserveOrderItemsCommandHandler,
        failOrderCommandHandler = mockk(relaxed = true),
        orderWorkerExecutor = pool,
        retryScheduler = { delayMs, task -> pool.schedule(delayMs, task) },
        meterRegistry = SimpleMeterRegistry(),
    )

    @AfterEach
    fun tearDown() {
        pool.close()
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
