package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.OrderSaga
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.domain.SagaLines
import pl.szymanski.wiktor.domain.SagaStatus
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * The reserve step's phase boundaries and its `state_load_time` accounting.
 *
 * Two contracts, both easy to break without any test failing elsewhere:
 *
 *  * **The stale-saga guard runs before the item is read.** A redelivery is safe either way — the
 *    cursor guard inside the write is the authoritative one — but reaching the read means paying a
 *    round trip AND the item's `reserveDelayMs` sleep for work that is going to be discarded. Under
 *    a workload with a real delay set, that is the difference between a cheap replay and one that
 *    occupies a worker for as long as a genuine reserve.
 *  * **`state_load_time{aggregate=InventoryItem}` is one sample per LINE.** On TO-3 it is one per
 *    order, because that branch reads the whole order's rows in a single `findAllById`. Anything
 *    comparing the two histograms without dividing by ITEMS_PER_ORDER is comparing a line against
 *    an order.
 */
class ReserveOrderItemStepTest {

    private val inventoryRepo = mockk<InventoryRepository>()
    private val stepWriter = mockk<SagaStepWriter>()
    private val registry = SimpleMeterRegistry()

    private val handler = ReserveOrderItemCommandHandler(
        inventoryRepo, stepWriter, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), registry,
    )

    private val correlationId: UUID = UUID.randomUUID()

    private fun saga(currentIndex: Int = 0, status: SagaStatus = SagaStatus.RUNNING) = OrderSaga(
        orderId = "ORDER-1",
        correlationId = correlationId,
        lines = SagaLines(listOf(ReservedItem("ITEM-A", 2), ReservedItem("ITEM-B", 3))),
        currentIndex = currentIndex,
        status = status,
        startedAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
    )

    private fun command(lineIndex: Int) = ReserveOrderItemCommand("ORDER-1", lineIndex, correlationId)

    private fun stock(vararg items: Pair<String, Int>) {
        val byId = items.toMap()
        every { inventoryRepo.findById(any()) } answers {
            Optional.ofNullable(byId[firstArg<String>()]?.let { InventoryItem(firstArg(), it, version = 7L) })
        }
    }

    private fun itemLoads(): Long = registry.find("state_load_time")
        .tag("source", "db_fetch").tag("aggregate", "InventoryItem").timer()?.count() ?: 0L

    @Test
    fun `the step reserves the line the command names and writes exactly one outcome`() {
        stock("ITEM-A" to 10, "ITEM-B" to 10)
        every { stepWriter.writeReserve(any()) } returns true

        assertEquals(StepOutcome.APPLIED, handler.handle(saga(currentIndex = 1), command(1)))

        verify(exactly = 1) {
            stepWriter.writeReserve(
                match {
                    it.lineIndex == 1 &&
                        it.updatedItem.id == "ITEM-B" &&
                        // 10 - 3, the quantity on LINE 1, not line 0's 2.
                        it.updatedItem.availableQty == 7 &&
                        it.event.lineIndex == 1 &&
                        it.event.orderId == "ORDER-1"
                }
            )
        }
        assertEquals(1L, itemLoads())
        assertEquals(1.0, registry.counter("inventory.append.success").count())
    }

    @Test
    fun `a step the saga has moved past is skipped without reading the item`() {
        stock("ITEM-A" to 10, "ITEM-B" to 10)

        assertEquals(StepOutcome.SKIPPED, handler.handle(saga(currentIndex = 2), command(0)))

        verify(exactly = 0) { inventoryRepo.findById(any()) }
        verify(exactly = 0) { stepWriter.writeReserve(any()) }
        assertEquals(0L, itemLoads())
    }

    @Test
    fun `a step for a saga that has begun compensating is skipped`() {
        stock("ITEM-A" to 10, "ITEM-B" to 10)

        assertEquals(
            StepOutcome.SKIPPED,
            handler.handle(saga(currentIndex = 0, status = SagaStatus.COMPENSATING), command(0)),
        )

        verify(exactly = 0) { inventoryRepo.findById(any()) }
    }

    @Test
    fun `a write that loses its cursor claim reports SKIPPED, not success`() {
        // The read-side guard passed but the row moved before the write. Reporting APPLIED here
        // would count an `inventory.append.success` for a step that wrote nothing.
        stock("ITEM-A" to 10, "ITEM-B" to 10)
        every { stepWriter.writeReserve(any()) } returns false

        assertEquals(StepOutcome.SKIPPED, handler.handle(saga(currentIndex = 0), command(0)))

        assertEquals(0.0, registry.counter("inventory.append.success").count())
    }

    @Test
    fun `insufficient stock is thrown in the modify phase, with nothing written`() {
        stock("ITEM-A" to 1, "ITEM-B" to 10)

        assertThrows<InsufficientStockException> { handler.handle(saga(currentIndex = 0), command(0)) }

        verify(exactly = 0) { stepWriter.writeReserve(any()) }
        // The read still happened and still cost a round trip, so it is still sampled.
        assertEquals(1L, itemLoads())
    }

    @Test
    fun `a missing item is a NotFound, and its failed read is still timed`() {
        stock()

        assertThrows<NotFoundException> { handler.handle(saga(currentIndex = 0), command(0)) }

        assertEquals(1L, itemLoads())
    }
}
