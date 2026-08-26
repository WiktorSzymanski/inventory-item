package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.OrderItems
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.domain.Reservation
import pl.szymanski.wiktor.repository.InventoryBatchWriter
import pl.szymanski.wiktor.repository.InventoryVersionConflictException
import pl.szymanski.wiktor.repository.OrderRepository
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What `state_persist_time{source=db_write}` is allowed to leave out.
 *
 * The metric used to be recorded only on the last line of [OrderWriteCommandHandler.write], which
 * is unreachable when the versioned batch UPDATE throws. That UPDATE is exactly where a contending
 * order blocks on the `inventory_state` row lock until the holder commits and only then loses on
 * `@Version`, so the attempts that were dropped were the slow ones and the histogram was fitted to
 * the uncontended survivors — a distribution that gets BETTER as contention gets worse.
 *
 * Both outcomes are now sampled, split by an `outcome` tag so the committed series stays exactly
 * what it always was and archived runs remain comparable.
 */
class OrderWritePersistTimingTest {

    private val batchWriter = mockk<InventoryBatchWriter>(relaxed = true)
    private val orderRepo = mockk<OrderRepository>(relaxed = true)
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val registry = SimpleMeterRegistry()

    private val handler = OrderWriteCommandHandler(batchWriter, orderRepo, publisher, registry)

    init {
        // `save` is generic (<S : Order> S), so a relaxed mock hands back a bare Object and the
        // Kotlin call site fails its cast before any assertion is reached.
        every { orderRepo.save(any()) } answers { firstArg() }
    }

    private fun persistCount(outcome: String): Long =
        registry.find("state_persist_time").tag("source", "db_write").tag("outcome", outcome)
            .timer()?.count() ?: 0L

    private fun persistTotalMs(outcome: String): Double =
        registry.find("state_persist_time").tag("source", "db_write").tag("outcome", outcome)
            .timer()?.totalTime(TimeUnit.MILLISECONDS) ?: 0.0

    private fun outcome(): OrderReserveOutcome {
        val item = InventoryItem(id = "ITEM-A", availableQty = 9, version = 3L)
        return OrderReserveOutcome(
            confirmedOrder = Order(
                orderId = "ORDER-1",
                userId = "USER-1",
                items = OrderItems(emptyList()),
                status = OrderStatus.CONFIRMED,
                version = 1L,
            ),
            updatedItems = listOf(item),
            reservations = listOf(
                Reservation("ITEM-A", "RES-1", 1, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
            ),
            reservedEvents = listOf(
                InventoryReservedEvent("ITEM-A", "ORDER-1", 1, UUID.randomUUID(), Instant.EPOCH)
            ),
            completedEvent = OrderCompletedEvent("ORDER-1", Instant.EPOCH),
        )
    }

    @Test
    fun `a committed write is sampled as outcome=committed`() {
        handler.write(outcome())

        assertEquals(1L, persistCount("committed"))
        assertEquals(0L, persistCount("conflict"))
    }

    @Test
    fun `a losing write is sampled too, as outcome=conflict, and still throws`() {
        every { batchWriter.updateAll(any()) } throws InventoryVersionConflictException("ITEM-A", 3L)

        assertThrows<InventoryVersionConflictException> { handler.write(outcome()) }

        assertEquals(
            1L, persistCount("conflict"),
            "the attempt that blocked on the row lock and then lost must not vanish from the histogram",
        )
        assertEquals(0L, persistCount("committed"))
    }

    /**
     * The change TO-2-fix-B is: `seq` is a BIGSERIAL handed out by the `event_publication` INSERT,
     * and the drain's cursor orders by it. Taken at statement 1 — where it was — the seq-to-commit
     * window was the whole of the versioned UPDATE's lock wait, which reached 4 s under contention
     * on TO-2-fix-A_capacity_W-base_20260825T235228Z. Taken last it is the duration of these
     * INSERTs, which is what makes seq order commit order closely enough for a plain `seq >` cursor.
     */
    @Test
    fun `the outbox is written after the versioned inventory update`() {
        handler.write(outcome())

        verifyOrder {
            batchWriter.updateAll(any())
            publisher.publishEvent(any<InventoryReservedEvent>())
            publisher.publishEvent(any<OrderCompletedEvent>())
        }
    }

    @Test
    fun `a conflict at the inventory update publishes nothing`() {
        every { batchWriter.updateAll(any()) } throws InventoryVersionConflictException("ITEM-A", 3L)

        assertThrows<InventoryVersionConflictException> { handler.write(outcome()) }

        // The losing attempt burns no seq at all now, where before it burned N and rolled them
        // back -- which is half of why the cursor could run ahead of commit order.
        verify(exactly = 0) { publisher.publishEvent(any<InventoryReservedEvent>()) }
        verify(exactly = 0) { publisher.publishEvent(any<OrderCompletedEvent>()) }
        assertEquals(1L, persistCount("conflict"))
    }

    /**
     * Pins the deliberate choice that this span is outbox + state writes, not state writes alone:
     * `startNs` is taken at method entry, ahead of the `event_publication` INSERTs. Moving it below
     * them would silently redefine the metric on six branches at once, so a slow publisher has to
     * show up in the sample.
     */
    @Test
    fun `the sampled span starts before the outbox write`() {
        every { publisher.publishEvent(any<Any>()) } answers { Thread.sleep(30) }

        handler.write(outcome())

        assertTrue(
            persistTotalMs("committed") >= 25.0,
            "state_persist_time must still enclose the outbox publish; got ${persistTotalMs("committed")}ms",
        )
    }
}
