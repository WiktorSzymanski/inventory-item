package pl.szymanski.wiktor.service.command

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
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
 *
 * This branch adds the second half of the problem. An order served entirely from the cache issues
 * no SELECT, and recorded no sample at all — so `state_load_time` here described the miss path
 * only, and its p50 was not the same population as the uncached branches'.
 *
 * Three InventoryItem arms now, one order's worth of work each:
 *
 *  * `cache` — the lookup loop, on EVERY order. It used to fire only when the order was fully
 *    cached, which left the cache work of every mixed order unmeasured.
 *  * `db_fetch` — the SELECT for the misses, and nothing else. Unchanged, so it still means what
 *    it meant before the cache existed.
 *  * `load` — both arms together, the analogue of ES-4's `state_load_time{phase=load}`. `cache`
 *    and `db_fetch` are disjoint populations whose ratio moves with the hit rate, so no percentile
 *    over either one is what a load costs on this branch; this is the series that answers that.
 */
class StateLoadAggregateTagTest {

    private val inventoryRepo = mockk<InventoryRepository>()
    private val orderRepo = mockk<OrderRepository>()
    private val writeHandler = mockk<OrderWriteCommandHandler>(relaxed = true)
    private val registry = SimpleMeterRegistry()

    private val cache: Cache<String, InventoryItem> = Caffeine.newBuilder().build()

    private val handler = ReserveOrderItemsCommandHandler(
        inventoryRepo, orderRepo, writeHandler, cache, Clock.systemUTC(), registry,
    )

    private fun cached(vararg items: Pair<String, Int>) = items.forEach { (id, qty) ->
        cache.put(id, InventoryItem(id = id, availableQty = qty, version = 3L))
    }

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
    fun `an order served entirely from the cache is still counted as a load`() {
        order()
        cached("item-1" to 10, "item-2" to 10)

        handler.handle(event("item-1" to 1, "item-2" to 1))

        assertEquals(1L, loads("cache", "InventoryItem"), "the cache-served load must be sampled")
        assertEquals(0L, loads("db_fetch", "InventoryItem"), "no SELECT was issued")
        assertEquals(1L, loads("db_fetch", "Order"))
    }

    /**
     * A partial hit pays for both arms, so it is sampled in both. Pricing it as `db_fetch` alone
     * left the lookup that served the rest of the order out of every histogram.
     */
    @Test
    fun `an order with any miss times the cache lookup as well as the select`() {
        order()
        cached("item-1" to 10)
        stock("item-2" to 10)

        handler.handle(event("item-1" to 1, "item-2" to 1))

        assertEquals(1L, loads("db_fetch", "InventoryItem"))
        assertEquals(1L, loads("cache", "InventoryItem"))
    }

    /** The pooled arm is what a load costs here, whichever side of the cache served it. */
    @Test
    fun `a partially served order records one pooled load sample`() {
        order()
        cached("item-1" to 10)
        stock("item-2" to 10)

        handler.handle(event("item-1" to 1, "item-2" to 1))

        assertEquals(1L, loads("load", "InventoryItem"))
    }

    @Test
    fun `an order served entirely from the cache records one pooled load sample`() {
        order()
        cached("item-1" to 10)

        handler.handle(event("item-1" to 1))

        assertEquals(1L, loads("load", "InventoryItem"))
    }

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
