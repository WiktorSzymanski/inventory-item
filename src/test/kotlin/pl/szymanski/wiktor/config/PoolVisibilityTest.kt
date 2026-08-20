package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Guards the two things that make "where does a retry actually execute?" answerable at runtime:
 * named threads and per-pool gauges.
 *
 * Both are load-bearing rather than cosmetic. The pools used `Executors`' default factory before,
 * which names every thread `pool-N-thread-M` and makes the command pool and the retry pool
 * indistinguishable in a `jcmd Thread.print` — on this branch that is exactly the distinction the
 * whole variant is about. And with no gauges, a run that got faster and a run that merely moved its
 * backlog from one queue to the next look identical.
 */
class PoolVisibilityTest {

    private val config = CommandGatewayConfig()

    @Test
    fun `command pool threads are named so a thread dump identifies them`() {
        val registry = SimpleMeterRegistry()
        val pool = config.sagaCommandExecutor(registry)
        val name = AtomicReference<String>()
        val ran = CountDownLatch(1)

        pool.execute {
            name.set(Thread.currentThread().name)
            ran.countDown()
        }

        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertTrue(
            name.get().startsWith("saga-command-"),
            "command-pool threads must be identifiable in a dump; was ${name.get()}",
        )
        (pool as ExecutorService).shutdownNow()
    }

    @Test
    fun `retry timer threads are named distinctly from the command pool`() {
        val registry = SimpleMeterRegistry()
        val timer = config.retryTimerExecutor(registry)
        val name = AtomicReference<String>()
        val ran = CountDownLatch(1)

        timer.schedule({ name.set(Thread.currentThread().name); ran.countDown() }, 1, TimeUnit.MILLISECONDS)

        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertTrue(
            name.get().startsWith("retry-timer-"),
            "retry-pool threads must be distinguishable from saga-command-*; was ${name.get()}",
        )
        timer.shutdownNow()
    }

    @Test
    fun `both pools publish active, queued and size`() {
        val registry = SimpleMeterRegistry()
        val pool = config.sagaCommandExecutor(registry)
        val timer = config.retryTimerExecutor(registry)

        for (lane in listOf("command", "retry")) {
            for (metric in listOf("saga.pool.active", "saga.pool.queued", "saga.pool.size")) {
                assertNotNull(
                    registry.find(metric).tag("pool", lane).gauge(),
                    "$metric{pool=\"$lane\"} is missing — the lane is invisible in a run",
                )
            }
        }

        // The "Executor pools — threads & queue" panel queries these three by {{name}}. Without
        // the ExecutorServiceMetrics binding the pools are absent from it entirely, which is how
        // this branch shipped before.
        for (lane in listOf("saga-command", "retry-timer")) {
            for (metric in listOf("executor.active", "executor.pool.size", "executor.queued")) {
                assertNotNull(
                    registry.find(metric).tag("name", lane).meter(),
                    "$metric{name=\"$lane\"} is missing — the pool will not appear in the " +
                        "\"Executor pools — threads & queue\" panel",
                )
            }
        }

        // `size` is threads ALIVE, not the configured width: a fixed ThreadPoolExecutor creates
        // its threads lazily, so the gauge starts at 0 and ramps to COMMAND_POOL_SIZE under load.
        // Reading a mid-run value of, say, 40 as "the pool is misconfigured" would be wrong — it
        // means only 40 threads were ever needed at once. Left lazy on purpose: prestarting would
        // change thread-creation timing against the parent branch and break the topology-only A/B.
        val size = registry.find("saga.pool.size").tag("pool", "command").gauge()!!
        assertEquals(0.0, size.value(), "a fresh pool has no threads yet")

        val ran = CountDownLatch(1)
        pool.execute { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertEquals(1.0, size.value(), "one task must bring exactly one thread to life")

        (pool as ExecutorService).shutdownNow()
        timer.shutdownNow()
    }
}
