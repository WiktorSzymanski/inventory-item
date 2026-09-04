package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.ReserveFanoutPool
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * What the FAN-OUT adds to the modify phase, over and above the invariants
 * [ReserveOrderItemsPhaseTest] already pins for TO-3.
 *
 * The property this branch has to earn is that nothing downstream can tell which path built the
 * outcome. A parallel phase 2 that produced the same rows in a different ORDER would still pass
 * every test in that file and would still be wrong here: it would reorder the outbox and make a
 * TO-3 vs TO-3-parallel diff show a difference this branch did not intend to make.
 */
class ReserveOrderItemsFanoutTest {

    private val inventoryRepo = mockk<InventoryRepository>()
    private val orderRepo = mockk<OrderRepository>()
    private val writeHandler = mockk<OrderWriteCommandHandler>(relaxed = true)
    private val outcome = slot<OrderReserveOutcome>()

    private fun handlerOn(pool: ReserveFanoutPool) = ReserveOrderItemsCommandHandler(
        inventoryRepo, orderRepo, writeHandler, pool, Clock.systemUTC(), SimpleMeterRegistry(),
    )

    /** Stock, with an optional per-item reserve delay so groups can be made to finish out of order. */
    private fun stockOf(vararg items: Triple<String, Int, Int>) {
        val byId = items.associateBy { it.first }
        every { inventoryRepo.findAllById(any()) } answers {
            firstArg<Iterable<String>>().mapNotNull { id ->
                byId[id]?.let {
                    InventoryItem(id = id, availableQty = it.second, reserveDelayMs = it.third, version = 3L)
                }
            }
        }
    }

    private fun stockOf(vararg items: Pair<String, Int>) =
        stockOf(*items.map { Triple(it.first, it.second, 0) }.toTypedArray())

    private fun pendingOrder() {
        every { orderRepo.findById("ORDER-1") } returns Optional.of(
            Order(
                orderId = "ORDER-1",
                userId = "USER-1",
                items = OrderItems(emptyList()),
                status = OrderStatus.PENDING,
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
    fun `a width of one and a wide pool produce byte-identical outcomes`() {
        val lines = arrayOf(
            "ITEM-C" to 1, "ITEM-A" to 2, "ITEM-B" to 3,
            "ITEM-A" to 1, "ITEM-D" to 4, "ITEM-B" to 2,
        )

        fun runAt(threads: Int): OrderReserveOutcome {
            pendingOrder()
            stockOf("ITEM-A" to 10, "ITEM-B" to 10, "ITEM-C" to 10, "ITEM-D" to 10)
            every { writeHandler.write(capture(outcome)) } returns Unit
            ReserveFanoutPool(threads, queueCapacity = 64).use { handlerOn(it).handle(event(*lines)) }
            return outcome.captured
        }

        val sequential = runAt(1)
        val parallel = runAt(8)

        // The lists, element for element — not just their contents. Reservation order is outbox
        // order, and the A/B is only clean while the two branches emit the same sequence.
        assertEquals(
            sequential.reservations.map { it.itemId to it.quantity },
            parallel.reservations.map { it.itemId to it.quantity },
        )
        assertEquals(
            sequential.reservedEvents.map { it.id to it.quantity },
            parallel.reservedEvents.map { it.id to it.quantity },
        )
        assertEquals(
            sequential.updatedItems.map { it.id to it.availableQty },
            parallel.updatedItems.map { it.id to it.availableQty },
        )
    }

    @Test
    fun `a line's result lands at its own index even when its group finishes last`() {
        pendingOrder()
        // ITEM-A is slow and comes FIRST in the order; ITEM-B is instant and comes second. Run
        // concurrently, B's group finishes long before A's, so an implementation that appended
        // results as they arrived would emit them backwards.
        stockOf(Triple("ITEM-A", 10, 120), Triple("ITEM-B", 10, 0))
        every { writeHandler.write(capture(outcome)) } returns Unit

        ReserveFanoutPool(threads = 4, queueCapacity = 64).use {
            handlerOn(it).handle(event("ITEM-A" to 1, "ITEM-B" to 2))
        }

        assertEquals(
            listOf("ITEM-A", "ITEM-B"),
            outcome.captured.reservations.map { it.itemId },
            "results must be placed at the client's line index, not appended on completion",
        )
        assertEquals(listOf("ITEM-A", "ITEM-B"), outcome.captured.reservedEvents.map { it.id })
    }

    @Test
    fun `the groups actually run concurrently`() {
        pendingOrder()
        // Four items, 100 ms each. TO-3 pays 400 ms here; this branch should pay about 100.
        stockOf(
            Triple("ITEM-A", 10, 100), Triple("ITEM-B", 10, 100),
            Triple("ITEM-C", 10, 100), Triple("ITEM-D", 10, 100),
        )

        val elapsedMs = ReserveFanoutPool(threads = 4, queueCapacity = 64).use { pool ->
            val handler = handlerOn(pool)
            val startNs = System.nanoTime()
            handler.handle(event("ITEM-A" to 1, "ITEM-B" to 1, "ITEM-C" to 1, "ITEM-D" to 1))
            (System.nanoTime() - startNs) / 1_000_000
        }

        // Deliberately loose: the claim under test is "not serial", and 250 ms sits far from both
        // the parallel figure (~100) and the serial one (~400) on any machine that can run the
        // rest of this suite.
        assertTrue(elapsedMs < 250, "modify phase took ${elapsedMs}ms — the groups ran serially")
    }

    @Test
    fun `an overflowing queue runs the group on the caller and changes nothing`() {
        pendingOrder()
        stockOf("ITEM-A" to 10, "ITEM-B" to 10, "ITEM-C" to 10, "ITEM-D" to 10)
        every { writeHandler.write(capture(outcome)) } returns Unit

        // One thread, one queue slot, four groups: at least two must be rejected and run inline on
        // the calling thread. That path is the branch's degradation-to-TO-3, and it must be
        // invisible in the result.
        ReserveFanoutPool(threads = 1, queueCapacity = 1).use {
            handlerOn(it).handle(event("ITEM-A" to 1, "ITEM-B" to 2, "ITEM-C" to 3, "ITEM-D" to 4))
        }

        assertEquals(
            listOf("ITEM-A" to 9, "ITEM-B" to 8, "ITEM-C" to 7, "ITEM-D" to 6),
            outcome.captured.updatedItems.map { it.id to it.availableQty },
        )
        assertEquals(listOf("ITEM-A", "ITEM-B", "ITEM-C", "ITEM-D"), outcome.captured.reservations.map { it.itemId })
    }

    @Test
    fun `insufficient stock surfaces as itself, not wrapped by the executor`() {
        pendingOrder()
        stockOf("ITEM-A" to 10, "ITEM-B" to 1)

        // Thrown on a fan-out thread, so it comes back inside an ExecutionException. Unwrapped, it
        // is what InventoryService.runOrderTask classifies; wrapped, the order would still fail but
        // with a different exception and message than TO-3 records for the same input, and any
        // conflict raised in this phase would silently stop being retried.
        ReserveFanoutPool(threads = 4, queueCapacity = 64).use { pool ->
            assertThrows<InsufficientStockException> {
                handlerOn(pool).handle(event("ITEM-A" to 1, "ITEM-B" to 5))
            }
        }
    }
}
