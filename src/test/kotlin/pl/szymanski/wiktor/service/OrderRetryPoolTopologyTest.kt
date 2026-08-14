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
 * WHICH pool runs a retried attempt. Invisible in every other test — the policy assertions and the
 * unblocking assertion both hold either way — yet it is the difference between "retries have their
 * own lane, as on ES" and "retries rejoin the worker queue behind fresh orders".
 *
 * Asserted by thread name, because that is the only thing that actually distinguishes the two at
 * runtime, and a silent regression here would show up in a bench run as an unexplained throughput
 * change.
 */
class OrderRetryPoolTopologyTest {

    private val reserveOrderItemsCommandHandler: ReserveOrderItemsCommandHandler = mockk()

    private val workerExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 2
        setThreadNamePrefix("order-worker-")
        initialize()
    }
    private val retryExecutor = ScheduledThreadPoolExecutor(2) { runnable ->
        Thread(runnable, "order-retry-1").apply { isDaemon = true }
    }
    private val retryScheduler = DelayedOrderRetryScheduler(retryExecutor)

    @AfterEach
    fun tearDown() {
        retryScheduler.close()
        workerExecutor.shutdown()
    }

    private fun serviceWith(executeRetriesOnRetryPool: Boolean) = InventoryService(
        inventoryRepository = mockk<InventoryRepository>(),
        orderRepository = mockk<OrderRepository>(),
        createInventoryItemCommandHandler = mockk<CreateInventoryItemCommandHandler>(),
        createOrderCommandHandler = mockk<CreateOrderCommandHandler>(),
        reserveOrderItemsCommandHandler = reserveOrderItemsCommandHandler,
        failOrderCommandHandler = mockk(relaxed = true),
        orderWorkerExecutor = workerExecutor,
        retryScheduler = retryScheduler,
        executeRetriesOnRetryPool = executeRetriesOnRetryPool,
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
    private fun attemptThreadsFor(executeRetriesOnRetryPool: Boolean): List<String> {
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

        serviceWith(executeRetriesOnRetryPool).onOrderCreated(event)

        assertTrue(bothAttempts.await(5, TimeUnit.SECONDS), "expected 2 attempts, saw $threads")
        return threads
    }

    @Test
    fun `by default the retried attempt runs on the retry pool`() {
        assertEquals(listOf("order-worker", "order-retry"), attemptThreadsFor(true))
    }

    @Test
    fun `with execute-on-retry-pool off the retried attempt goes back to the worker pool`() {
        assertEquals(listOf("order-worker", "order-worker"), attemptThreadsFor(false))
    }
}
