package pl.szymanski.wiktor.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The contract that makes payload-carrying NOTIFY safe.
 *
 * TO-2 discards the NOTIFY payload and coalesces a burst into one bounded drain pass. This branch
 * reads the payload, so it cannot coalesce: it submits one task per notification, which is the exact
 * shape commit 2185068 removed as an open loop — a commit burst became a delivery burst that drove
 * the order-worker pool into row contention. What replaces the coalescing is this pool: bounded
 * queue, and a submit onto a full queue BLOCKS rather than being rejected or run on the caller.
 *
 * If someone restores `Executors.newFixedThreadPool` here, the open loop is back and every test in
 * the suite still passes. This one does not.
 */
class EventDeliveryBackpressureTest {

    private lateinit var pool: ThreadPoolExecutor

    private fun newPool(threads: Int, capacity: Int): ThreadPoolExecutor =
        ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(capacity),
            { r -> Thread(r, "test-event-delivery").apply { isDaemon = true } },
            BlockingSubmitPolicy(),
        ).also { pool = it }

    @AfterEach
    fun tearDown() {
        if (::pool.isInitialized) pool.shutdownNow()
    }

    @Test
    fun `a submit onto a full queue blocks instead of being rejected`() {
        val executor = newPool(threads = 1, capacity = 1)
        val release = CountDownLatch(1)
        val occupied = CountDownLatch(1)

        // 1: occupies the only thread. 2: fills the only queue slot.
        executor.submit { occupied.countDown(); release.await() }
        assertTrue(occupied.await(5, TimeUnit.SECONDS), "worker never started")
        executor.submit { }

        // 3: nowhere to go. An unbounded queue would swallow it; the default AbortPolicy would
        // throw; CallerRunsPolicy would run it here and exceed the pool's width.
        val submitted = AtomicBoolean(false)
        val submitter = Thread { executor.submit { }; submitted.set(true) }.apply { isDaemon = true; start() }

        submitter.join(500)
        assertTrue(submitter.isAlive, "third submit did not block — the delivery bound is gone")
        assertFalse(submitted.get())

        release.countDown()
        submitter.join(5_000)
        assertFalse(submitter.isAlive, "submit never completed once the queue drained")
        assertTrue(submitted.get())
    }

    @Test
    fun `every submitted task runs exactly once despite the queue filling`() {
        val executor = newPool(threads = 2, capacity = 2)
        val tasks = 200
        val done = CountDownLatch(tasks)

        repeat(tasks) { executor.submit { done.countDown() } }

        assertTrue(done.await(10, TimeUnit.SECONDS), "${done.count} task(s) were dropped by the bound")
    }

    @Test
    fun `deliveries in flight never exceed the pool width`() {
        val width = 3
        val executor = newPool(threads = width, capacity = 2)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val done = CountDownLatch(60)

        repeat(60) {
            executor.submit {
                val now = inFlight.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(5)
                inFlight.decrementAndGet()
                done.countDown()
            }
        }

        assertTrue(done.await(10, TimeUnit.SECONDS))
        // The whole point of the bound: a burst of 60 cannot become 60 concurrent deliveries hitting
        // the order-worker pool. CallerRunsPolicy would show width + 1 here.
        assertTrue(peak.get() <= width, "peak concurrency was ${peak.get()}, expected at most $width")
    }

    @Test
    fun `a submit after shutdown is rejected rather than blocking forever`() {
        val executor = newPool(threads = 1, capacity = 1)
        val release = CountDownLatch(1)
        executor.submit { release.await() }
        executor.submit { }
        executor.shutdown()

        // Without the isShutdown guard this parks on a queue no worker will ever drain, and
        // @PreDestroy hangs behind it.
        assertThrows(RejectedExecutionException::class.java) { executor.submit { } }
        release.countDown()
    }

    @Test
    fun `the queue is bounded at the configured capacity`() {
        val capacity = 7
        val executor = newPool(threads = 1, capacity = capacity)

        assertEquals(capacity, executor.queue.size + executor.queue.remainingCapacity())
    }
}
