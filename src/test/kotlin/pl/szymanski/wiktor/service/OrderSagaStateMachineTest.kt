package pl.szymanski.wiktor.service

import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import pl.szymanski.wiktor.domain.SagaStatus
import pl.szymanski.wiktor.service.command.CompleteOrderCommand
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.ReleaseReservationCommand
import pl.szymanski.wiktor.service.command.ReserveOrderItemCommand
import pl.szymanski.wiktor.service.command.StepOutcome
import java.util.UUID

/**
 * The saga's transition table, asserted from the OUTSIDE: given a saga row in some state, which step
 * does an incoming event cause?
 *
 * **The property under test is that the answer depends only on the ROW.** Every one of the saga's
 * four triggers calls the same `submitAdvance`, and none of them says what to do — because an event
 * only knows where the saga was when it was written. It may be delivered twice, after a restart, or
 * alongside a republished sibling, so anything derived from its contents would be acting on a stale
 * belief about the saga's position. Deciding from the row is what makes a redelivery re-run the
 * CURRENT step (which the cursor guard then turns into a no-op) instead of resurrecting an old one.
 *
 * A `SyncTaskExecutor` runs the step on the calling thread, so each case is a single assertion with
 * no latch: what is being tested here is the routing, not the threading.
 */
class OrderSagaStateMachineTest {

    private val harness = SagaServiceHarness(
        executor = SyncTaskExecutor(),
        retryScheduler = { _, task -> task.run() },
    )

    private val correlationId: UUID = UUID.randomUUID()

    private fun advance(orderId: String = "ORDER-1") =
        harness.service.submitAdvance(orderId, correlationId)

    @Test
    fun `a fresh saga reserves line 0`() {
        val saga = harness.givenSaga("ORDER-1", lineCount = 3, currentIndex = 0)
        every { harness.reserveHandler.handle(any(), any()) } returns StepOutcome.APPLIED

        advance()

        verify(exactly = 1) {
            harness.reserveHandler.handle(saga, ReserveOrderItemCommand("ORDER-1", 0, correlationId))
        }
    }

    @Test
    fun `a saga mid-order reserves the line the cursor stands on`() {
        val saga = harness.givenSaga("ORDER-1", lineCount = 3, currentIndex = 2)
        every { harness.reserveHandler.handle(any(), any()) } returns StepOutcome.APPLIED

        advance()

        verify(exactly = 1) {
            harness.reserveHandler.handle(saga, ReserveOrderItemCommand("ORDER-1", 2, correlationId))
        }
    }

    @Test
    fun `a saga whose every line is reserved completes the order`() {
        harness.givenSaga("ORDER-1", lineCount = 3, currentIndex = 3)

        advance()

        verify(exactly = 1) {
            harness.completeHandler.handle(CompleteOrderCommand("ORDER-1", 3, correlationId))
        }
        verify(exactly = 0) { harness.reserveHandler.handle(any(), any()) }
    }

    @Test
    fun `a compensating saga releases the last line still held, counting down`() {
        val saga = harness.givenSaga(
            "ORDER-1", lineCount = 4, currentIndex = 3, status = SagaStatus.COMPENSATING,
        )
        every { harness.releaseHandler.handle(any(), any()) } returns StepOutcome.APPLIED

        advance()

        // currentIndex 3 means lines 0..2 are held, so the one to give back is line 2.
        verify(exactly = 1) {
            harness.releaseHandler.handle(saga, ReleaseReservationCommand("ORDER-1", 2, correlationId))
        }
    }

    @Test
    fun `a compensating saga with nothing left to release rejects the order`() {
        harness.givenSaga(
            "ORDER-1",
            lineCount = 4,
            currentIndex = 0,
            status = SagaStatus.COMPENSATING,
            failureReason = "out of stock",
            failureCode = "insufficient_stock",
        )

        advance()

        verify(exactly = 1) {
            harness.failOrderHandler.handle(FailOrderCommand("ORDER-1", "out of stock", correlationId))
        }
        verify(exactly = 0) { harness.releaseHandler.handle(any(), any()) }
    }

    @Test
    fun `an order that fails before reserving anything is rejected without any release`() {
        // The degenerate compensation: the first line was the one that failed, so the reserved
        // prefix is empty and the walk back has zero steps. This is the common case for
        // out-of-stock under a hot-item workload, not an edge case.
        harness.givenSaga(
            "ORDER-1",
            lineCount = 1,
            currentIndex = 0,
            status = SagaStatus.COMPENSATING,
            failureReason = "out of stock",
            failureCode = "insufficient_stock",
        )

        advance()

        verify(exactly = 1) { harness.failOrderHandler.handle(any()) }
    }

    @Test
    fun `an ended saga does nothing, and says so by completing normally`() {
        // Every order's LAST event is redelivered into an already-ended saga in normal operation —
        // the terminal step's own event arrives after the saga has ended — so this path is the
        // common case, not a fault. It must complete the publication rather than fail it, or the
        // republisher would retry every finished order for ever.
        harness.givenSaga("ORDER-1", lineCount = 2, currentIndex = 2, status = SagaStatus.ENDED)

        val future = advance()

        assertTrue(future.isDone)
        assertFalse(future.isCompletedExceptionally)
        verify(exactly = 0) { harness.reserveHandler.handle(any(), any()) }
        verify(exactly = 0) { harness.releaseHandler.handle(any(), any()) }
        verify(exactly = 0) { harness.completeHandler.handle(any()) }
        verify(exactly = 0) { harness.failOrderHandler.handle(any()) }
    }

    @Test
    fun `an order with no lines completes immediately`() {
        harness.givenSaga("ORDER-1", lineCount = 0, currentIndex = 0)

        advance()

        verify(exactly = 1) {
            harness.completeHandler.handle(CompleteOrderCommand("ORDER-1", 0, correlationId))
        }
    }

    @Test
    fun `a missing saga leaves the publication incomplete instead of dropping the order`() {
        // Nothing registered for ORDER-MISSING. Failing the future is what leaves the
        // event_publication row uncompleted, so IncompleteEventRepublisher tries again — the
        // alternative, completing it, silently strands the order in PENDING for ever.
        val future = advance("ORDER-MISSING")

        assertTrue(future.isCompletedExceptionally)
    }

    @Test
    fun `the queue wait is measured only for the delivery that follows admission`() {
        harness.givenSaga("ORDER-1", lineCount = 2, currentIndex = 1)
        every { harness.reserveHandler.handle(any(), any()) } returns StepOutcome.APPLIED

        harness.service.submitAdvance("ORDER-1", correlationId, first = true)
        harness.service.submitAdvance("ORDER-1", correlationId)
        harness.service.submitAdvance("ORDER-1", correlationId)

        // No sample at all here: acceptOrder was never called, so there is no admission timestamp
        // to measure against. What matters is that the two later deliveries — which are saga
        // progress, not queueing — cannot contribute one either.
        assertEquals(0L, harness.registry.timer("order.queue.wait").count())
    }
}
