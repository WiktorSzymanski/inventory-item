package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import pl.szymanski.wiktor.service.OrderRetryPolicy
import pl.szymanski.wiktor.service.OrderRetryScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The retry hop's silent failure modes. A scheduler that runs the task on the calling thread quietly
 * restores blocking behaviour while every other test still passes, and on this branch a retry that
 * came back on some pool OTHER than the worker pool would undo the branch itself — both are
 * invisible in a bench run except as an unexplained throughput number.
 *
 * Nothing here needs a database, so this stays a plain context test over [OrderWorkerConfig].
 */
@SpringJUnitConfig(classes = [OrderWorkerConfig::class, OrderRetrySchedulerWiringTest.TestBeans::class])
class OrderRetrySchedulerWiringTest {

    @Autowired
    private lateinit var orderRetryScheduler: OrderRetryScheduler

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    /** Boot supplies this in production; the minimal context has to declare it. */
    @Configuration
    class TestBeans {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Test
    fun `the scheduler runs the retry on a worker thread, never on the caller`() {
        val ranOn = AtomicReference<Thread>()
        val done = CountDownLatch(1)

        orderRetryScheduler.schedule(1L) {
            ranOn.set(Thread.currentThread())
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "retry task never ran")
        val retryThread = ranOn.get()
        assertNotEquals(Thread.currentThread(), retryThread, "retry ran inline — the worker was not released")
        // THE assertion at the wiring level: the two-pool topology printed order-retry-N here.
        assertTrue(
            retryThread.name.startsWith("order-worker-"),
            "a retry must resume on the ONE pool; ran on ${retryThread.name}",
        )
        assertTrue(retryThread.isDaemon, "pool threads must be daemons so a pending retry cannot hang shutdown")
    }

    @Test
    fun `the merged pool publishes executor_ and the in-backoff gauge, and no retry-pool gauge`() {
        // Micrometer holds the gauged object weakly, so a pool referenced only by the builder would
        // report NaN or vanish once collected. The bean keeps the executor alive; this proves it.
        System.gc()

        // Boot auto-binds executor_* for ThreadPoolTaskExecutor beans only, and OrderWorkerPool is
        // not one — so the config binds it by hand. Without this the dashboards' "Busy threads by
        // pool" panel is empty for the branch and the run looks like it had no worker pool at all.
        assertNotNull(
            meterRegistry.find("executor.pool.size").tag("name", "orderWorkerExecutor").gauge(),
            "executor.pool.size{name=orderWorkerExecutor} missing — ExecutorServiceMetrics not bound",
        )
        assertNotNull(
            meterRegistry.find("executor.queued").tag("name", "orderWorkerExecutor").gauge(),
            "executor.queued{name=orderWorkerExecutor} missing",
        )

        assertNotNull(meterRegistry.find("order.retry.pool.queued").gauge(), "order.retry.pool.queued missing")
        // Not published here: there is no separate pool to be active on, and a constant 0 would read
        // as "the retry lane is idle" rather than "there is no retry lane".
        assertNull(
            meterRegistry.find("order.retry.pool.active").gauge(),
            "order.retry.pool.active must NOT exist on a branch with one pool",
        )
    }

    @Test
    fun `the in-backoff gauge counts a retry while it waits and releases it when it runs`() {
        val gauge = meterRegistry.find("order.retry.pool.queued").gauge()!!
        assertEquals(0.0, gauge.value(), "gauge should start empty")

        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        orderRetryScheduler.schedule(300L) {
            running.countDown()
            release.await()
        }

        // Still in backoff: queued on the DelayedWorkQueue, holding no thread.
        assertEquals(1.0, gauge.value(), "a retry serving out its backoff must be counted")

        assertTrue(running.await(5, TimeUnit.SECONDS), "retry never ran")
        // Started, so it is no longer waiting — it is now executor_active_threads, not backoff.
        assertEquals(0.0, gauge.value(), "a retry that has started is no longer in backoff")
        release.countDown()
    }

    @Test
    fun `the backoff curve matches the Retryable policy it replaced`() {
        // delay = 25, multiplier = 2.0, maxDelay = 500 — the waits Spring took before attempts 2..5.
        assertEquals(listOf(25L, 50L, 100L, 200L), (0 until OrderRetryPolicy.MAX_RETRIES).map { OrderRetryPolicy.delayMsFor(it) })
        // The cap binds rather than the curve running away, if MAX_RETRIES is ever raised.
        assertEquals(OrderRetryPolicy.MAX_DELAY_MS, OrderRetryPolicy.delayMsFor(20))
    }
}
