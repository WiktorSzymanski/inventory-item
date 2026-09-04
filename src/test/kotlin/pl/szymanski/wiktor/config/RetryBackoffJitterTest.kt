package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.axonframework.commandhandling.GenericCommandMessage
import org.axonframework.modelling.command.ConcurrencyException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.random.RandomGenerator
import kotlin.math.abs

/**
 * The jitter this branch adds to the ES backoff curve.
 *
 * Two properties have to hold together, and dropping either one quietly ruins something. The delays
 * must actually SPREAD — that is the point, decorrelating orders that conflicted at the same
 * instant — and the retry BUDGET must stay where the un-jittered ES branches leave it, or a run
 * here stops being readable against ES-3 and ES-4 for a second reason on top of the correlation.
 */
class RetryBackoffJitterTest {

    private val retryPool = ScheduledThreadPoolExecutor(1)

    @AfterEach
    fun tearDown() {
        retryPool.shutdownNow()
    }

    /** A [RandomGenerator] returning exactly what it is told, for the boundary cases. */
    private fun fixed(vararg values: Double): RandomGenerator {
        val it = values.iterator()
        return object : RandomGenerator {
            override fun nextLong(): Long = throw UnsupportedOperationException()
            override fun nextDouble(): Double = it.next()
        }
    }

    private fun scheduler(
        random: RandomGenerator,
        retryExecutor: ScheduledThreadPoolExecutor = retryPool,
        commandExecutor: Executor = Executor { it.run() },
    ) = ConcurrencyRetryScheduler(
        retryExecutor = retryExecutor,
        commandExecutor = commandExecutor,
        meterRegistry = SimpleMeterRegistry(),
        random = random,
    )

    /** The last attempt's window is [200, 600) clipped at 500; see [ConcurrencyRetryScheduler.delayMsFor]. */
    private fun clippedWindowTop(base: Long) = (base * 3 / 2).coerceAtMost(ConcurrencyRetryScheduler.MAX_DELAY_MS)

    @Test
    fun `the underlying curve is still the one the un-jittered ES branches wait`() {
        val policy = scheduler(fixed())
        assertEquals(
            listOf(25L, 50L, 100L, 200L, 400L),
            (0 until 5).map { policy.baseDelayMsFor(it) },
        )
    }

    @Test
    fun `the window is base plus or minus half, at both ends`() {
        // nextDouble() == 0.0 is the bottom of the window, 1.0 the (exclusive) top. Attempt 2,
        // because its window is nowhere near the cap.
        assertEquals(50L, scheduler(fixed(0.0)).delayMsFor(2))   // 100 * 0.5
        assertEquals(100L, scheduler(fixed(0.5)).delayMsFor(2))  // 100 * 1.0 — the base
        assertEquals(150L, scheduler(fixed(1.0)).delayMsFor(2))  // 100 * 1.5
    }

    @Test
    fun `every draw for every attempt lands inside its window`() {
        val policy = scheduler(Random(20260904))
        for (attempt in 0 until 5) {
            val base = policy.baseDelayMsFor(attempt)
            repeat(2_000) {
                val delay = policy.delayMsFor(attempt)
                assertTrue(
                    delay in (base / 2)..clippedWindowTop(base),
                    "attempt $attempt drew ${delay}ms, outside [${base / 2}, ${clippedWindowTop(base)}]",
                )
            }
        }
    }

    @Test
    fun `the mean stays on the curve, so the retry budget is unchanged`() {
        // The reason this branch does not use AWS's full jitter: rand(0, base) would halve every
        // number here, and the retry budget would differ from the un-jittered ES branches as well
        // as the correlation.
        //
        // ATTEMPT 4 IS THE EXCEPTION, and it is arithmetic, not slop. Its window [200, 600) is
        // clipped at MAX_DELAY_MS = 500, so
        //     E = (1/400) * INTEGRAL(200..500) x dx + (100/400) * 500 = 262.5 + 125 = 387.5
        // TO's equivalent test asserts `base` for every attempt because TO runs 4 retries and its
        // cap never binds. Here it does. Asserting 400 would be asserting something false.
        val expected = listOf(25.0, 50.0, 100.0, 200.0, 387.5)
        val policy = scheduler(Random(20260904))
        for (attempt in 0 until 5) {
            val draws = 20_000
            val mean = (1..draws).sumOf { policy.delayMsFor(attempt) }.toDouble() / draws
            assertTrue(
                abs(mean - expected[attempt]) < expected[attempt] * 0.02,
                "attempt $attempt: mean ${mean}ms drifted from the expected ${expected[attempt]}ms by more than 2%",
            )
        }
        // And the budget the five of them add up to, which is the number that has to stay near
        // the un-jittered 775ms.
        assertEquals(762.5, expected.sum(), 0.001)
    }

    @Test
    fun `two orders conflicting at the same attempt do not wake together`() {
        // THE property. Without it, orders that collided at T collide again at T+25, T+75, ... and
        // the convoy re-forms at every step.
        val policy = scheduler(Random(20260904))
        val distinct = (1..500).map { policy.delayMsFor(3) }.toSet()
        assertTrue(
            distinct.size > 50,
            "500 draws produced only ${distinct.size} distinct delays — the retries are still in lockstep",
        )
    }

    @Test
    fun `a delay is never zero, so a retry always waits`() {
        // The smallest window bottom is 25 * 0.5 = 12.5 ms, but the floor is explicit: a zero delay
        // would hand the dispatch back to the command pool with no wait at all.
        assertTrue(scheduler(fixed(0.0)).delayMsFor(0) >= 1L)
        val policy = scheduler(Random(20260904))
        repeat(500) { assertTrue(policy.delayMsFor(0) >= 1L) }
    }

    @Test
    fun `the cap binds after jitter, not before`() {
        // The top of attempt 4's window is 600; the cap must clip it rather than the jitter being
        // applied to an already-capped 500 (which would centre the last draw on 500, not 400).
        assertEquals(ConcurrencyRetryScheduler.MAX_DELAY_MS, scheduler(fixed(1.0)).delayMsFor(4))
        val policy = scheduler(Random(20260904))
        repeat(500) { assertTrue(policy.delayMsFor(4) <= ConcurrencyRetryScheduler.MAX_DELAY_MS) }
    }

    /**
     * The arithmetic above proves [ConcurrencyRetryScheduler.delayMsFor]; this proves it is on the
     * path. A jitter nobody calls is the failure mode a pure policy test cannot see.
     */
    @Test
    fun `the delay actually handed to the retry executor is the jittered one`() {
        val recording = RecordingScheduledPool()
        val ran = CountDownLatch(200)
        val scheduler = scheduler(
            random = Random(20260904),
            retryExecutor = recording,
            commandExecutor = Executor { ran.countDown() },
        )

        // history.size == 3 is attempt 4 of 5, base 200ms, window [100, 300].
        val history = List(3) { arrayOf<Class<out Throwable>>(ConcurrencyException::class.java) }
        repeat(200) {
            val rescheduled = scheduler.scheduleRetry(
                GenericCommandMessage.asCommandMessage<Any>("cmd"),
                ConcurrencyException("simulated 23505"),
                history,
            ) { }
            assertTrue(rescheduled, "a ConcurrencyException below the attempt cap must be rescheduled")
        }
        assertTrue(ran.await(30, TimeUnit.SECONDS), "the scheduled dispatches never all ran")

        assertEquals(200, recording.delaysMs.size, "every retry must reach the retry executor")
        recording.delaysMs.forEach {
            assertTrue(it in 100L..300L, "scheduled ${it}ms, outside attempt-4's window [100, 300]")
        }
        assertTrue(
            recording.delaysMs.toSet().size > 20,
            "the executor received only ${recording.delaysMs.toSet().size} distinct delays — " +
                "delayMsFor is not on the path",
        )
        recording.shutdownNow()
    }

    /** Records what `scheduleRetry` asks the retry pool to wait, then behaves as the real pool. */
    private class RecordingScheduledPool : ScheduledThreadPoolExecutor(1) {
        val delaysMs = CopyOnWriteArrayList<Long>()

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            delaysMs += unit.toMillis(delay)
            // Zero delay: this test asserts the NUMBER passed in, not that the wall clock honours
            // it, and 200 real backoffs would make it a 60-second test.
            return super.schedule(command, 0L, TimeUnit.MILLISECONDS)
        }
    }
}
