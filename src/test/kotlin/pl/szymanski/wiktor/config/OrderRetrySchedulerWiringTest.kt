package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * The retry hop is new infrastructure, and the two ways it can be wrong are both silent in a bench
 * run: a scheduler that runs the task on the calling thread quietly restores TO-3's blocking
 * behaviour while every other test still passes, and non-daemon threads leave the JVM unable to
 * exit — which the harness would report only as a health-check timeout.
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
    fun `the scheduler runs the retry on a daemon retry thread, never on the caller`() {
        val ranOn = AtomicReference<Thread>()
        val done = CountDownLatch(1)

        orderRetryScheduler.schedule(1L) {
            ranOn.set(Thread.currentThread())
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "retry task never ran")
        val retryThread = ranOn.get()
        assertNotEquals(Thread.currentThread(), retryThread, "retry ran inline — the worker was not released")
        assertTrue(retryThread.name.startsWith("order-retry-"), "unexpected retry thread: ${retryThread.name}")
        assertTrue(retryThread.isDaemon, "retry threads must be daemons so shutdown cannot hang")
    }

    @Test
    fun `the retry pool publishes its saturation gauges`() {
        // Micrometer holds the gauged object weakly, so a pool referenced only by the builder would
        // report NaN or vanish once collected. The bean keeps the executor alive; this proves it.
        System.gc()
        assertNotNull(meterRegistry.find("order.retry.pool.active").gauge(), "order.retry.pool.active missing")
        assertNotNull(meterRegistry.find("order.retry.pool.queued").gauge(), "order.retry.pool.queued missing")
        assertEquals(0.0, meterRegistry.find("order.retry.pool.active").gauge()!!.value())
    }

    @Test
    fun `the backoff curve matches the Retryable policy it replaced`() {
        // delay = 25, multiplier = 2.0, maxDelay = 500 — the waits Spring took before attempts 2..5.
        assertEquals(listOf(25L, 50L, 100L, 200L), (0 until OrderRetryPolicy.MAX_RETRIES).map { OrderRetryPolicy.delayMsFor(it) })
        // The cap binds rather than the curve running away, if MAX_RETRIES is ever raised.
        assertEquals(OrderRetryPolicy.MAX_DELAY_MS, OrderRetryPolicy.delayMsFor(20))
    }
}
