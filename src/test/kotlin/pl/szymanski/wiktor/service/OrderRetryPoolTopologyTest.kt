package pl.szymanski.wiktor.service

import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import pl.szymanski.wiktor.service.command.StepOutcome
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WHICH pool runs a retried step — the branch, asserted directly.
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

    // Two threads, the shipped topology in miniature: first attempts and retries share them.
    private val pool = OrderWorkerPool(threads = 2)
    private val harness = SagaServiceHarness(pool)

    @AfterEach
    fun tearDown() {
        pool.close()
    }

    /** @return the thread-name prefixes the two attempts ran on, in order. */
    private fun attemptThreads(): List<String> {
        harness.givenSaga("ORDER-1")
        val threads = CopyOnWriteArrayList<String>()
        val bothAttempts = CountDownLatch(2)
        var hasFailedOnce = false

        every { harness.reserveHandler.handle(any(), any()) } answers {
            threads += Thread.currentThread().name.substringBeforeLast('-')
            bothAttempts.countDown()
            if (!hasFailedOnce) {
                hasFailedOnce = true
                throw OptimisticLockingFailureException("conflict")
            }
            StepOutcome.APPLIED
        }

        harness.service.submitAdvance("ORDER-1", UUID.randomUUID(), first = true)

        assertTrue(bothAttempts.await(5, TimeUnit.SECONDS), "expected 2 attempts, saw $threads")
        return threads
    }

    @Test
    fun `the retried step runs on the worker pool, because there is no other pool`() {
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
