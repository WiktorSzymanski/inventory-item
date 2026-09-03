package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.axonframework.commandhandling.GenericCommandMessage
import org.axonframework.modelling.command.ConcurrencyException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The contract this branch exists for: the retry pool must SCHEDULE the retry, and the command
 * pool must RUN it.
 *
 * Asserted against a recording Executor rather than by thread name, because the property that
 * matters is "handed to the command executor", not "happened to run on a thread called X". On every
 * other ES branch the dispatch task runs on the retry pool itself, since Axon's RetryDispatch calls
 * commandBus.dispatch() inline and SimpleCommandBus handles on the calling thread.
 */
class RetryDispatchTargetTest {

    private val retryPool = Executors.newScheduledThreadPool(2)

    @AfterEach
    fun tearDown() {
        retryPool.shutdownNow()
    }

    private fun scheduler(commandExecutor: Executor) = ConcurrencyRetryScheduler(
        retryExecutor = retryPool,
        commandExecutor = commandExecutor,
        meterRegistry = SimpleMeterRegistry(),
    )

    private fun conflict() = ConcurrencyException("simulated 23505")

    @Test
    fun `the retried command is executed by the command executor, not on the retry thread`() {
        val ran = CountDownLatch(1)
        val executorThread = AtomicReference<String>()
        val dispatchThread = AtomicReference<String>()

        val commandExecutor = Executor { task ->
            executorThread.set(Thread.currentThread().name)
            Thread(task, "fake-command-pool").start()
        }

        val rescheduled = scheduler(commandExecutor).scheduleRetry(
            GenericCommandMessage.asCommandMessage<Any>("cmd"),
            conflict(),
            emptyList(),
        ) {
            dispatchThread.set(Thread.currentThread().name)
            ran.countDown()
        }

        assertTrue(rescheduled, "a ConcurrencyException below the attempt cap must be rescheduled")
        assertTrue(ran.await(5, TimeUnit.SECONDS), "the dispatch task never ran")
        assertEquals("fake-command-pool", dispatchThread.get(), "the retried command must run ON the command pool")
        assertNotEquals(
            dispatchThread.get(), executorThread.get(),
            "the retry pool must only hand the task over, never run it",
        )
    }

    /**
     * The command pool's queue is unbounded, so this only fires at shutdown — but losing the task
     * is the worst disposition available: the gateway callback never completes, the saga never sees
     * a failure, and the order stays PENDING forever.
     */
    @Test
    fun `a rejected hand-off still runs the command rather than dropping it`() {
        val ran = CountDownLatch(1)
        val registry = SimpleMeterRegistry()
        val rejecting = Executor { throw RejectedExecutionException("pool is shutting down") }

        val scheduler = ConcurrencyRetryScheduler(
            retryExecutor = retryPool,
            commandExecutor = rejecting,
            meterRegistry = registry,
        )
        scheduler.scheduleRetry(
            GenericCommandMessage.asCommandMessage<Any>("cmd"),
            conflict(),
            emptyList(),
        ) { ran.countDown() }

        assertTrue(ran.await(5, TimeUnit.SECONDS), "a rejected hand-off must not swallow the command")
        assertEquals(
            1.0, registry.counter("inventory.retry.handoff.rejected").count(),
            "the fallback must be counted — it means the command ran outside the budgeted lane",
        )
    }

    /**
     * The widths are spread across this file and application.yaml, and the pool size that has to
     * cover them is passed by docker-compose. Asserting the sum here is what stops the branch's
     * headline property — same connection budget as its parent — drifting silently.
     *
     * THE DEFAULT RUN ONLY. Both widths are now per-run overridable (`COMMAND_POOL`,
     * `AXON_SAGA_TOTAL_SEGMENTS`), so this asserts the shape the branch ships with, not the shape
     * every run has. AxonCustomizerConfig's [POOLS] line recomputes the same sum from what is
     * actually configured and warns when it exceeds the pool; that is the check that covers an
     * overridden run.
     */
    @Test
    fun `the executing lanes consume exactly the Axon pool docker-compose passes`() {
        val busyThreads = CommandGatewayConfig.DEFAULT_COMMAND_POOL_SIZE +
            CommandGatewayConfig.SAGA_SEGMENT_THREADS +
            CommandGatewayConfig.SINGLE_THREADED_PROJECTIONS
        val peakDemand = CommandGatewayConfig.CONNECTIONS_PER_BUSY_THREAD * busyThreads

        assertEquals(
            350, peakDemand,
            "peak demand changed; either AXON_JDBC_POOL_SIZE must follow or the parity with " +
                "ES-4-NullLock's 2 x (82 + 30 + 60 + 3) = 350 is broken and the pair stops being " +
                "a topology-only A/B",
        )
    }
}
