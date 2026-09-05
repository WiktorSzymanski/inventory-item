package pl.szymanski.wiktor.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.random.RandomGenerator
import kotlin.math.abs

/**
 * The jitter this branch adds to the shared backoff curve.
 *
 * Two properties have to hold together, and dropping either one quietly ruins something. The delays
 * must actually SPREAD — that is the point, decorrelating orders that conflicted at the same
 * instant — and their MEAN must stay on the deterministic curve, or `order.retry.backoff.time`
 * stops being comparable with the branches that do not jitter.
 */
class OrderRetryJitterTest {

    /**
     * The delay indices [pl.szymanski.wiktor.service.InventoryService.scheduleRetry] actually
     * evaluates: FAILURES SO FAR, so 1..MAX_RETRIES rather than 0 until MAX_RETRIES. The n=0 term
     * of the curve is never waited, here or on ES.
     *
     * This test drives `delayMsFor` directly, so it cannot see the call site drift — that is
     * [InventoryServiceRetryTest.assertDelaysOnCurve]'s job, and the reason this list is spelled
     * out rather than assumed.
     */
    private val waitedIndices = (1..OrderRetryPolicy.MAX_RETRIES).toList()

    /** The last retry's window is [200, 600) clipped at 500; see [OrderRetryPolicy.delayMsFor]. */
    private fun clippedWindowTop(base: Long) = (base * 3 / 2).coerceAtMost(OrderRetryPolicy.MAX_DELAY_MS)

    /** A [RandomGenerator] returning exactly what it is told, for the boundary cases. */
    private fun fixed(vararg values: Double): RandomGenerator {
        val it = values.iterator()
        return object : RandomGenerator {
            override fun nextLong(): Long = throw UnsupportedOperationException()
            override fun nextDouble(): Double = it.next()
        }
    }

    @Test
    fun `the underlying curve is the one the ES branches wait`() {
        assertEquals(
            listOf(50L, 100L, 200L, 400L),
            waitedIndices.map { OrderRetryPolicy.baseDelayMsFor(it) },
        )
    }

    @Test
    fun `the window is base plus or minus half, at both ends`() {
        // nextDouble() == 0.0 is the bottom of the window, 1.0 the (exclusive) top.
        assertEquals(50L, OrderRetryPolicy.delayMsFor(2, fixed(0.0)))   // 100 * 0.5
        assertEquals(100L, OrderRetryPolicy.delayMsFor(2, fixed(0.5)))  // 100 * 1.0 — the base
        assertEquals(150L, OrderRetryPolicy.delayMsFor(2, fixed(1.0)))  // 100 * 1.5
    }

    @Test
    fun `every draw for every attempt lands inside its window`() {
        val random = Random(20260818)
        for (attempt in waitedIndices) {
            val base = OrderRetryPolicy.baseDelayMsFor(attempt)
            repeat(2_000) {
                val delay = OrderRetryPolicy.delayMsFor(attempt, random)
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
        // number here, and order.retry.backoff.time would measure a different thing here than on
        // the un-jittered branches it is compared against.
        //
        // THE LAST RETRY IS THE EXCEPTION, and it is arithmetic, not slop. Its window [200, 600) is
        // clipped at MAX_DELAY_MS = 500, so
        //     E = (1/400) * INTEGRAL(200..500) x dx + (100/400) * 500 = 262.5 + 125 = 387.5
        // Asserting 400 there would be asserting something false. Identical to the ES branches'
        // RetryBackoffJitterTest, which reaches the same clipped draw by the same route.
        val expected = listOf(50.0, 100.0, 200.0, 387.5)
        val random = Random(20260818)
        waitedIndices.forEachIndexed { i, attempt ->
            val draws = 20_000
            val mean = (1..draws).sumOf { OrderRetryPolicy.delayMsFor(attempt, random) }.toDouble() / draws
            assertTrue(
                abs(mean - expected[i]) < expected[i] * 0.02,
                "attempt $attempt: mean ${mean}ms drifted from the expected ${expected[i]}ms by more than 2%",
            )
        }
        // And the budget the four of them add up to, which is what has to match ES.
        assertEquals(737.5, expected.sum(), 0.001)
    }

    @Test
    fun `two orders conflicting at the same attempt do not wake together`() {
        // THE property. Without it, orders that collided at T collide again at T+25, T+75, ... and
        // the convoy re-forms at every step — which on a branch that deliberately raises the
        // conflict rate is a self-inflicted wound.
        val distinct = (1..500).map { OrderRetryPolicy.delayMsFor(waitedIndices.last()) }.toSet()
        assertTrue(
            distinct.size > 50,
            "500 draws produced only ${distinct.size} distinct delays — the retries are still in lockstep",
        )
    }

    @Test
    fun `a delay is never zero, so a retry always waits`() {
        // The smallest window bottom actually waited is 50 * 0.5 = 25 ms, but the floor is explicit
        // and is asserted at the curve's own origin, which the floor still has to hold for: a zero
        // delay would re-queue the attempt with no wait at all.
        assertTrue(OrderRetryPolicy.delayMsFor(0, fixed(0.0)) >= 1L)
        repeat(500) { assertTrue(OrderRetryPolicy.delayMsFor(waitedIndices.first()) >= 1L) }
    }

    @Test
    fun `the cap binds after jitter, not before`() {
        // If MAX_RETRIES is ever raised, the upper tail must not run past the cap.
        assertEquals(OrderRetryPolicy.MAX_DELAY_MS, OrderRetryPolicy.delayMsFor(20, fixed(1.0)))
        repeat(500) { assertTrue(OrderRetryPolicy.delayMsFor(20) <= OrderRetryPolicy.MAX_DELAY_MS) }
    }
}
