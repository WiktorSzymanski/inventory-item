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
 * WHICH pool runs a retried attempt — the branch, asserted directly.
 *
 * The two-pool topology gave this test two cases: the retried attempt landed on `order-retry-*` by
 * default, or back on `order-worker-*` with `execute-on-retry-pool=false`. There is one pool and no
 * setting now, so there is one case: both attempts run on `order-worker-*`, and no thread named
 * `order-retry-*` exists at all.
 *
 * Asserted by thread name, because that is the only thing that actually distinguishes the topologies
 * at runtime. A regression — someone reintroducing a scheduler with its own threads — would show up
 * in a bench run as an unexplained throughput change and nowhere else.
 */
class OrderRetryPoolTopologyTest {

    private val reserveOrderItemsCommandHandler: ReserveOrderItemsCommandHandler = mockk()

    // Two threads, the shipped topology in miniature: first attempts and retries share them.
    private val pool = OrderWorkerPool(threads = 2)

    @AfterEach
    fun tearDown() {
        pool.close()
    }

    private val service = InventoryService(
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

    private val event = OrderCreatedEvent(
        orderId = "ORDER-1",
        userId = "USER-1",
        items = listOf(ReservedItem("ITEM-001", 1)),
        correlationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
    )

    /** @return the thread-name prefixes the two attempts ran on, in order. */
    private fun attemptThreads(): List<String> {
        val threads = CopyOnWriteArrayList<String>()
        val bothAttempts = CountDownLatch(2)
        var hasFailedOnce = false

        every { reserveOrderItemsCommandHandler.handle(any()) } answers {
            threads += Thread.currentThread().name.substringBeforeLast('-')
            bothAttempts.countDown()
            if (!hasFailedOnce) {
                hasFailedOnce = true
                throw OptimisticLockingFailureException("conflict")
            }
        }

        service.onOrderCreated(event)

        assertTrue(bothAttempts.await(5, TimeUnit.SECONDS), "expected 2 attempts, saw $threads")
        return threads
    }

    @Test
    fun `the retried attempt runs on the worker pool, because there is no other pool`() {
        assertEquals(listOf("order-worker", "order-worker"), attemptThreads())
    }

    @Test
    fun `no retry-pool thread is ever created`() {
        attemptThreads()

        val retryThreads = Thread.getAllStackTraces().keys.map { it.name }.filter { it.startsWith("order-retry-") }
        assertTrue(
            retryThreads.isEmpty(),
            "there must be no second pool; found $retryThreads",
        )
    }
}
