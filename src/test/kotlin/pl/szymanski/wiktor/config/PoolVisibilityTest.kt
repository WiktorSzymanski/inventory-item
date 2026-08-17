package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.axonframework.commandhandling.GenericCommandMessage
import org.axonframework.modelling.command.ConcurrencyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Makes the two execution lanes observable, and pins the property that defines this branch: a
 * retried command runs ON the retry pool.
 *
 * That property is not configured anywhere — it falls out of Axon's `RetryingCallback.RetryDispatch`
 * calling `commandBus.dispatch()` inline while `SimpleCommandBus` handles on the calling thread. It
 * is therefore version-specific behaviour rather than a guarantee, which is exactly why it is worth
 * asserting: an Axon upgrade that made the dispatch asynchronous would silently turn the 30-thread
 * retry lane into something else, and the connection budget in [CommandGatewayConfig] would quietly
 * stop describing reality.
 *
 * ES-4-NullLock-oneExec asserts the mirror image of the first test here.
 */
class PoolVisibilityTest {

    private val config = CommandGatewayConfig()

    @Test
    fun `a retried command executes on the retry pool, not the command pool`() {
        val registry = SimpleMeterRegistry()
        val retryPool = config.retryCommandExecutor(registry)
        val thread = AtomicReference<String>()
        val ran = CountDownLatch(1)

        val scheduler = ConcurrencyRetryScheduler(
            retryExecutor = retryPool,
            meterRegistry = registry,
        )
        val rescheduled = scheduler.scheduleRetry(
            GenericCommandMessage.asCommandMessage<Any>("cmd"),
            ConcurrencyException("simulated 23505"),
            emptyList(),
        ) {
            thread.set(Thread.currentThread().name)
            ran.countDown()
        }

        assertTrue(rescheduled, "a ConcurrencyException below the attempt cap must be rescheduled")
        assertTrue(ran.await(5, TimeUnit.SECONDS), "the dispatch task never ran")
        assertTrue(
            thread.get().startsWith("retry-command-"),
            "the retried command must execute on the retry pool — if this fails, Axon no longer " +
                "dispatches inline and the connection budget needs rederiving. Was ${thread.get()}",
        )
        retryPool.shutdownNow()
    }

    @Test
    fun `both pools name their threads so a dump identifies them`() {
        val registry = SimpleMeterRegistry()
        val commandPool = config.sagaCommandExecutor(registry)
        val retryPool = config.retryCommandExecutor(registry)
        val commandThread = AtomicReference<String>()
        val retryThread = AtomicReference<String>()
        val ran = CountDownLatch(2)

        commandPool.execute { commandThread.set(Thread.currentThread().name); ran.countDown() }
        retryPool.schedule({ retryThread.set(Thread.currentThread().name); ran.countDown() }, 1, TimeUnit.MILLISECONDS)

        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertTrue(commandThread.get().startsWith("saga-command-"), "was ${commandThread.get()}")
        assertTrue(retryThread.get().startsWith("retry-command-"), "was ${retryThread.get()}")

        (commandPool as ExecutorService).shutdownNow()
        retryPool.shutdownNow()
    }

    @Test
    fun `both pools publish active, queued and size`() {
        val registry = SimpleMeterRegistry()
        val commandPool = config.sagaCommandExecutor(registry)
        val retryPool = config.retryCommandExecutor(registry)

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
        for (lane in listOf("saga-command", "retry-command")) {
            for (metric in listOf("executor.active", "executor.pool.size", "executor.queued")) {
                assertNotNull(
                    registry.find(metric).tag("name", lane).meter(),
                    "$metric{name=\"$lane\"} is missing — the pool will not appear in the " +
                        "\"Executor pools — threads & queue\" panel",
                )
            }
        }

        // `size` is threads ALIVE, not the configured width: a fixed pool fills lazily, so the
        // series ramps under load. A mid-run value below the width means only that many threads
        // were ever needed at once, NOT a misconfiguration.
        val size = registry.find("saga.pool.size").tag("pool", "command").gauge()!!
        assertEquals(0.0, size.value(), "a fresh pool has no threads yet")

        val ran = CountDownLatch(1)
        commandPool.execute { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertEquals(1.0, size.value(), "one task must bring exactly one thread to life")

        (commandPool as ExecutorService).shutdownNow()
        retryPool.shutdownNow()
    }
}
