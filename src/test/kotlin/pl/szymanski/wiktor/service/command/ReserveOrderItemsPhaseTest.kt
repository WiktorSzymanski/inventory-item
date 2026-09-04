package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderItems
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.ReserveFanoutPool
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * What the read and modify phases compute, given that
 * [ReserveOrderItemsTransactionBoundaryTest] has established WHERE they run.
 *
 * The invariants here are the ones the split could plausibly break: the order must still be one
 * read, the working copy must still see its own decrements, the batch must still go out in the
 * global lock order, and a doomed order must still touch nothing.
 */
class ReserveOrderItemsPhaseTest {

    private val inventoryRepo = mockk<InventoryRepository>()
    private val orderRepo = mockk<OrderRepository>()
    private val writeHandler = mockk<OrderWriteCommandHandler>(relaxed = true)
    private val clock: Clock = Clock.systemUTC()

    // Every assertion below is TO-3's, unchanged: this branch only moves the modify phase onto a
    // pool, and the invariants it has to keep — one select per order, the itemId-sorted batch,
    // same-item lines folding into one row, insufficient stock aborting before any write — are
    // exactly the ones that file already pinned. A width above 1 is what makes them run
    // concurrently and therefore worth re-asserting here.
    private val fanoutPool = ReserveFanoutPool(threads = 4, queueCapacity = 64)

    private val handler = ReserveOrderItemsCommandHandler(
        inventoryRepo, orderRepo, writeHandler, fanoutPool, clock, SimpleMeterRegistry(),
    )

    private val outcome = slot<OrderReserveOutcome>()

    private fun stockOf(vararg items: Pair<String, Int>) {
        val byId = items.toMap()
        every { inventoryRepo.findAllById(any()) } answers {
            firstArg<Iterable<String>>().mapNotNull { id ->
                byId[id]?.let { InventoryItem(id = id, availableQty = it, version = 3L) }
            }
        }
    }

    private fun pendingOrder(status: OrderStatus = OrderStatus.PENDING) {
        every { orderRepo.findById("ORDER-1") } returns Optional.of(
            Order(
                orderId = "ORDER-1",
                userId = "USER-1",
                items = OrderItems(emptyList()),
                status = status,
            )
        )
    }

    private fun event(vararg lines: Pair<String, Int>) = OrderCreatedEvent(
        orderId = "ORDER-1",
        userId = "USER-1",
        items = lines.map { ReservedItem(it.first, it.second) },
        correlationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `an N-line order costs ONE select, not N`() {
        pendingOrder()
        stockOf("ITEM-A" to 10, "ITEM-B" to 10, "ITEM-C" to 10)

        handler.handle(event("ITEM-A" to 1, "ITEM-B" to 2, "ITEM-C" to 3))

        // The whole point of the read phase. The per-line path issued one findById per line.
        verify(exactly = 1) { inventoryRepo.findAllById(any()) }
    }

    @Test
    fun `the batch goes out sorted by itemId, preserving the global lock order`() {
        pendingOrder()
        stockOf("ITEM-A" to 10, "ITEM-B" to 10, "ITEM-C" to 10)
        every { writeHandler.write(capture(outcome)) } returns Unit

        // Deliberately not in id order, and not the order the per-line path would have locked
        // them in either.
        handler.handle(event("ITEM-C" to 1, "ITEM-A" to 2, "ITEM-B" to 3))

        assertEquals(
            listOf("ITEM-A", "ITEM-B", "ITEM-C"),
            outcome.captured.updatedItems.map { it.id },
            "unsorted batch rows reintroduce the deadlock the per-line path's sortedBy avoided",
        )
    }

    @Test
    fun `two lines naming the same item fold into one row and see each other's decrement`() {
        pendingOrder()
        stockOf("ITEM-A" to 10)
        every { writeHandler.write(capture(outcome)) } returns Unit

        handler.handle(event("ITEM-A" to 3, "ITEM-A" to 4))

        val updated = outcome.captured.updatedItems
        assertEquals(1, updated.size, "the same row must be updated once, not twice")
        assertEquals(3, updated.single().availableQty, "10 - 3 - 4: the working copy must carry both lines")
        // Per line, exactly as the per-line path produced them — the fold is of ROWS, not of the
        // order's lines.
        assertEquals(2, outcome.captured.reservations.size)
        assertEquals(2, outcome.captured.reservedEvents.size)

        // AND THE DATABASE THEN REFUSES IT, here as on the per-line path: `reservations` is keyed
        // (item_id, reservation_id), reservation_id IS the order id, and Reservation is a
        // Persistable whose isNew() is always true — so two lines on one item are two INSERTs of
        // one key and the order ends REJECTED on a duplicate-key violation, with nothing written.
        // Verified against a real Postgres on 2026-08-18. Not a regression and not worth fixing:
        // k6's buildOrder draws lines without replacement, and config.validate rejects
        // ALLOW_DUP_LINES, so no benchmarked order can contain a duplicate line. What this test
        // pins is the FOLD — that the working copy is sequential — not that duplicates commit.
    }

    @Test
    fun `the version read in phase one is the version the write phase will check`() {
        pendingOrder()
        stockOf("ITEM-A" to 10)
        every { writeHandler.write(capture(outcome)) } returns Unit

        handler.handle(event("ITEM-A" to 1))

        assertEquals(
            3L, outcome.captured.updatedItems.single().version,
            "the batch UPDATE's predicate comes from this value; bumping it here would defeat the check",
        )
    }

    @Test
    fun `insufficient stock aborts before anything is written`() {
        pendingOrder()
        stockOf("ITEM-A" to 10, "ITEM-B" to 1)

        assertThrows<InsufficientStockException> {
            handler.handle(event("ITEM-A" to 1, "ITEM-B" to 5))
        }

        // Nothing to roll back, because nothing was started. InventoryService turns this into a
        // rejected order exactly as it did on the per-line path.
        verify(exactly = 0) { writeHandler.write(any()) }
        confirmVerified(writeHandler)
    }

    @Test
    fun `a missing item aborts before anything is written`() {
        pendingOrder()
        stockOf("ITEM-A" to 10)

        val thrown = assertThrows<NotFoundException> {
            handler.handle(event("ITEM-A" to 1, "ITEM-GONE" to 1))
        }
        assertTrue(thrown.message!!.contains("ITEM-GONE"))
        verify(exactly = 0) { writeHandler.write(any()) }
    }

    @Test
    fun `a redelivered event for an already-terminal order reads no inventory at all`() {
        pendingOrder(status = OrderStatus.CONFIRMED)

        handler.handle(event("ITEM-A" to 1))

        verify(exactly = 0) { inventoryRepo.findAllById(any()) }
        verify(exactly = 0) { writeHandler.write(any()) }
    }

    @Test
    fun `the order handed to the write phase is already CONFIRMED`() {
        pendingOrder()
        stockOf("ITEM-A" to 10)
        every { writeHandler.write(capture(outcome)) } returns Unit

        handler.handle(event("ITEM-A" to 1))

        assertEquals(OrderStatus.CONFIRMED, outcome.captured.confirmedOrder.status)
        assertEquals("ORDER-1", outcome.captured.completedEvent.orderId)
    }
}
