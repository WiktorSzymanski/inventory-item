package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderItems
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.ReserveFanoutPool
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Which aggregate each `state_load_time` sample belongs to.
 *
 * The reservation path loads two different things from the database — the order row once, and the
 * order's inventory rows once — and until this contract held, both landed in one untagged
 * histogram. Its p50 then moved with the ratio between them rather than with the cost of either,
 * and there was nothing to line up against the ES branches, which tag the same metric with the Axon
 * aggregate type.
 *
 * The order read is timed even when it decides the handler does no further work: a missing or
 * already-settled order still paid for a full round trip, and dropping those samples would fit the
 * histogram to the orders that went on to reserve.
 */
class StateLoadAggregateTagTest {

    private val inventoryRepo = mockk<InventoryRepository>()
    private val orderRepo = mockk<OrderRepository>()
    private val writeHandler = mockk<OrderWriteCommandHandler>(relaxed = true)
    private val registry = SimpleMeterRegistry()

    private val handler = ReserveOrderItemsCommandHandler(
        inventoryRepo, orderRepo, writeHandler,
        ReserveFanoutPool(threads = 4, queueCapacity = 64),
        Clock.systemUTC(), registry,
    )

    private fun loads(source: String, aggregate: String): Long =
        registry.find("state_load_time").tag("source", source).tag("aggregate", aggregate).timer()?.count() ?: 0L

    private fun order(status: OrderStatus = OrderStatus.PENDING) {
        every { orderRepo.findById("ORDER-1") } returns Optional.of(
            Order(orderId = "ORDER-1", userId = "USER-1", items = OrderItems(emptyList()), status = status)
        )
    }

    private fun stock(vararg items: Pair<String, Int>) {
        val byId = items.toMap()
        every { inventoryRepo.findAllById(any()) } answers {
            firstArg<Iterable<String>>().mapNotNull { id ->
                byId[id]?.let { InventoryItem(id = id, availableQty = it, version = 3L) }
            }
        }
    }

    private fun event(vararg lines: Pair<String, Int>) = OrderCreatedEvent(
        orderId = "ORDER-1",
        userId = "USER-1",
        items = lines.map { ReservedItem(it.first, it.second) },
        correlationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `a reservation records the order read and the item read under their own aggregates`() {
        order()
        stock("item-1" to 10, "item-2" to 10)

        handler.handle(event("item-1" to 1, "item-2" to 1))

        assertEquals(1L, loads("db_fetch", "Order"), "one order row read")
        assertEquals(1L, loads("db_fetch", "InventoryItem"), "one batched inventory read")
    }

    /** Two lines on one item are still ONE inventory read, so the counts must not track line count. */
    @Test
    fun `the item read is one sample per order, not per line`() {
        order()
        stock("item-1" to 10)

        handler.handle(event("item-1" to 1, "item-1" to 1))

        assertEquals(1L, loads("db_fetch", "InventoryItem"))
        assertEquals(1L, loads("db_fetch", "Order"))
    }

    @Test
    fun `a missing order still records its round trip and never reaches the item read`() {
        every { orderRepo.findById("ORDER-1") } returns Optional.empty()

        assertThrows<NotFoundException> { handler.handle(event("item-1" to 1)) }

        assertEquals(1L, loads("db_fetch", "Order"))
        assertEquals(0L, loads("db_fetch", "InventoryItem"))
    }

    @Test
    fun `an already settled order still records its round trip and never reaches the item read`() {
        order(status = OrderStatus.CONFIRMED)

        handler.handle(event("item-1" to 1))

        assertEquals(1L, loads("db_fetch", "Order"))
        assertEquals(0L, loads("db_fetch", "InventoryItem"))
    }

    /** No sample may fall back to an untagged series, or it silently rejoins the pooled histogram. */
    @Test
    fun `every state_load_time series carries an aggregate tag`() {
        order()
        stock("item-1" to 10)

        handler.handle(event("item-1" to 1))

        val untagged = registry.find("state_load_time").timers()
            .filter { it.id.getTag("aggregate").isNullOrBlank() }
            .map { it.id.tags.toString() }
        assertEquals(emptyList<String>(), untagged)
    }
}
