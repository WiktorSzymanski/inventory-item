package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservationReleasedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.Reservation
import pl.szymanski.wiktor.repository.InventoryBatchWriter
import pl.szymanski.wiktor.repository.InventoryVersionConflictException
import pl.szymanski.wiktor.repository.SagaCursorWriter
import java.time.Instant
import java.util.UUID

/**
 * The write transaction of one saga step, and the two orderings it depends on.
 *
 * **Claim first.** The cursor claim has to be the FIRST statement, or a redelivered step writes a
 * second reservation and a second decrement before discovering it was not its turn. Rolling that
 * back would work, but only because the whole thing happens to be one transaction; asserting the
 * order makes the guarantee structural instead of incidental, and it keeps a lost claim from taking
 * an `inventory_state` row lock it has no business holding.
 *
 * **Inventory last.** The versioned UPDATE has to be the LAST statement, so the exclusive row lock
 * it takes is held only until COMMIT rather than across the outbox write. That is inherited from
 * TO-3's `OrderWriteCommandHandler` for the same reason, and it is the thing that makes the lock
 * window one line wide on this branch.
 */
class SagaStepWriterTest {

    private val cursor = mockk<SagaCursorWriter>()
    private val batchWriter = mockk<InventoryBatchWriter>(relaxed = true)
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val registry = SimpleMeterRegistry()

    private val writer = SagaStepWriter(cursor, batchWriter, publisher, registry)

    private val item = InventoryItem(id = "ITEM-1", availableQty = 9, version = 3L)
    private val correlationId: UUID = UUID.randomUUID()

    private val reserveOutcome = ReserveStepOutcome(
        orderId = "ORDER-1",
        lineIndex = 2,
        updatedItem = item,
        reservation = Reservation(itemId = "ITEM-1", reservationId = "ORDER-1", quantity = 1),
        event = InventoryReservedEvent("ITEM-1", "ORDER-1", 1, correlationId, Instant.EPOCH, "ORDER-1", 2),
    )

    private val releaseOutcome = ReleaseStepOutcome(
        orderId = "ORDER-1",
        lineIndex = 2,
        restoredItem = item,
        reservationId = "ORDER-1",
        event = InventoryReservationReleasedEvent("ITEM-1", "ORDER-1", 1, correlationId, Instant.EPOCH, "ORDER-1", 2),
    )

    private fun persistCount(outcome: String): Long =
        registry.find("state_persist_time")
            .tag("source", "db_write").tag("outcome", outcome).timer()?.count() ?: 0L

    @Test
    fun `a reserve claims the cursor first and updates inventory last`() {
        every { cursor.advance("ORDER-1", 2) } returns true

        assertTrue(writer.writeReserve(reserveOutcome))

        verifyOrder {
            cursor.advance("ORDER-1", 2)
            publisher.publishEvent(reserveOutcome.event)
            batchWriter.insertAll(listOf(reserveOutcome.reservation))
            batchWriter.updateAll(listOf(item))
        }
    }

    @Test
    fun `a release claims the cursor first and updates inventory last`() {
        every { cursor.retreat("ORDER-1", 2) } returns true

        assertTrue(writer.writeRelease(releaseOutcome))

        verifyOrder {
            cursor.retreat("ORDER-1", 2)
            publisher.publishEvent(releaseOutcome.event)
            batchWriter.deleteReservation("ITEM-1", "ORDER-1")
            batchWriter.updateAll(listOf(item))
        }
    }

    @Test
    fun `a redelivered reserve writes nothing at all`() {
        // The saga has already moved past line 2, so the claim matches no row. Nothing else may
        // run: a second reservation row would violate the PK, and a second decrement would sell
        // stock twice.
        every { cursor.advance("ORDER-1", 2) } returns false

        assertFalse(writer.writeReserve(reserveOutcome))

        verify(exactly = 0) { publisher.publishEvent(any<Any>()) }
        verify(exactly = 0) { batchWriter.insertAll(any()) }
        verify(exactly = 0) { batchWriter.updateAll(any()) }
    }

    @Test
    fun `a redelivered release writes nothing at all`() {
        every { cursor.retreat("ORDER-1", 2) } returns false

        assertFalse(writer.writeRelease(releaseOutcome))

        verify(exactly = 0) { publisher.publishEvent(any<Any>()) }
        verify(exactly = 0) { batchWriter.deleteReservation(any(), any()) }
        verify(exactly = 0) { batchWriter.updateAll(any()) }
    }

    @Test
    fun `a lost version predicate is timed as a conflict and rethrown`() {
        // The attempts that LOSE are the slow ones — the final UPDATE blocks on the row lock until
        // the holder commits and only then fails its predicate — so a success-only histogram gets
        // better as contention gets worse. Both outcomes are sampled for exactly that reason.
        every { cursor.advance("ORDER-1", 2) } returns true
        every { batchWriter.updateAll(any()) } throws InventoryVersionConflictException("ITEM-1", 3L)

        assertThrows<InventoryVersionConflictException> { writer.writeReserve(reserveOutcome) }

        assertEquals(1L, persistCount("conflict"))
        assertEquals(0L, persistCount("committed"))
    }

    @Test
    fun `a committed step is timed once, on the committed series`() {
        every { cursor.advance("ORDER-1", 2) } returns true

        writer.writeReserve(reserveOutcome)

        assertEquals(1L, persistCount("committed"))
        assertEquals(0L, persistCount("conflict"))
    }

    @Test
    fun `a step that never claimed the cursor is not timed as a write`() {
        // It wrote nothing, so counting it as either a commit or a conflict would put a
        // near-zero sample into a histogram that is supposed to describe the cost of writing.
        every { cursor.advance("ORDER-1", 2) } returns false

        writer.writeReserve(reserveOutcome)

        assertEquals(0L, persistCount("committed"))
        assertEquals(0L, persistCount("conflict"))
    }
}
