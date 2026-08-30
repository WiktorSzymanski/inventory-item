package pl.szymanski.wiktor.service

import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import pl.szymanski.wiktor.service.command.ReserveOrderItemCommand
import pl.szymanski.wiktor.service.command.StepOutcome
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A worker thread parked in retry backoff is a worker thread not doing work — and on this branch
 * that pool is the ONLY pool, so the property matters more here than anywhere.
 *
 * ONE thread, two orders. The first conflicts once and must wait out its 25 ms backoff; the second is
 * queued behind it and needs nothing but a thread. Because the backoff is served in the pool's
 * DelayedWorkQueue rather than by sleeping, the thread is released and the second order runs during
 * the wait, so the reserve handler sees A, B, A.
 *
 * The single thread is what makes this a real assertion. If a retry ever held a thread for the length
 * of its backoff — a `Thread.sleep` in a decorator, a scheduler that runs inline — this branch would
 * have no worker left at all.
 *
 * The ordering also pins the queue discipline: A's retry becomes due 25 ms after B was submitted, so
 * B goes first. A retry does not jump ahead of work already queued.
 *
 * What is retried here is a saga STEP, not an order. That is the only thing the port from TO-3
 * changed about this test, and it is worth stating: TO-3's retry re-ran the whole order, so on that
 * branch this test proved the pool was free during an ORDER's backoff. Here it proves it during a
 * LINE's, which is the smaller and more frequent event.
 */
class OrderRetryUnblocksWorkerTest {

    // Exactly one thread, serving first attempts and retries alike: that is the whole experiment.
    private val pool = OrderWorkerPool(threads = 1)
    private val harness = SagaServiceHarness(pool)

    @AfterEach
    fun tearDown() {
        pool.close()
    }

    @Test
    fun `a step waiting out its backoff does not hold the only worker thread`() {
        harness.givenSaga("ORDER-A")
        harness.givenSaga("ORDER-B")

        val attempts = CopyOnWriteArrayList<String>()
        val allAttemptsSeen = CountDownLatch(3)
        var firstOrderHasFailedOnce = false

        every { harness.reserveHandler.handle(any(), any()) } answers {
            val command = secondArg<ReserveOrderItemCommand>()
            attempts += command.orderId
            allAttemptsSeen.countDown()
            if (command.orderId == "ORDER-A" && !firstOrderHasFailedOnce) {
                firstOrderHasFailedOnce = true
                throw OptimisticLockingFailureException("conflict")
            }
            StepOutcome.APPLIED
        }

        // Queued in this order, so the single worker necessarily picks A first.
        harness.service.submitAdvance("ORDER-A", UUID.randomUUID(), first = true)
        harness.service.submitAdvance("ORDER-B", UUID.randomUUID(), first = true)

        assertTrue(
            allAttemptsSeen.await(5, TimeUnit.SECONDS),
            "expected 3 reserve attempts, saw $attempts",
        )
        assertEquals(listOf("ORDER-A", "ORDER-B", "ORDER-A"), attempts)
    }
}
