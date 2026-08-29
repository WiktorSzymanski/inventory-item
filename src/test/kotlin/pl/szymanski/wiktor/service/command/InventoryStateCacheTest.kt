package pl.szymanski.wiktor.service.command

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderItems
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.repository.InventoryBatchWriter
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.InventoryVersionConflictException
import pl.szymanski.wiktor.repository.OrderRepository
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * TO-4's variable, on the split reserve path. The cache moved with the phases: it is READ in the
 * read phase, where it now decides which items need a SELECT at all rather than being consulted
 * once per line, and it is MAINTAINED in the write phase, where the eviction-on-conflict and the
 * version-guarded post-commit merge used to live on `ReserveItemCommandHandler.handle`.
 *
 * Every property here was true of the per-line path and has to stay true, or the branch is
 * measuring a different cache than the one it was measuring before.
 */
class InventoryStateCacheTest {

    private val inventoryRepo: InventoryRepository = mockk()
    private val orderRepo: OrderRepository = mockk(relaxed = true)
    private val batchWriter: InventoryBatchWriter = mockk(relaxed = true)
    private val eventPublisher: ApplicationEventPublisher = mockk(relaxed = true)
    private val clock: Clock = Clock.systemUTC()

    private lateinit var cache: Cache<String, InventoryItem>
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var writeHandler: OrderWriteCommandHandler
    private lateinit var handler: ReserveOrderItemsCommandHandler

    @BeforeEach
    fun setUp() {
        cache = Caffeine.newBuilder().build()
        registry = SimpleMeterRegistry()
        writeHandler = OrderWriteCommandHandler(
            batchWriter, orderRepo, eventPublisher, cache, registry,
        )
        handler = ReserveOrderItemsCommandHandler(
            inventoryRepo, orderRepo, writeHandler, cache, clock, registry,
        )
        every { orderRepo.findById("ORDER-1") } returns Optional.of(
            Order(orderId = "ORDER-1", userId = "USER-1", items = OrderItems(emptyList()), status = OrderStatus.PENDING)
        )
        // save() is generic, so a relaxed answer cannot produce an Order; echo the argument.
        every { orderRepo.save(any<Order>()) } answers { firstArg() }
        every { batchWriter.updateAll(any()) } answers {
            firstArg<List<InventoryItem>>().sortedBy { it.id }.map { it.copy(version = it.version + 1) }
        }
    }

    private fun item(id: String, qty: Int = 10, version: Long = 3L) =
        InventoryItem(id = id, availableQty = qty, version = version)

    private fun event(vararg lines: Pair<String, Int>) = OrderCreatedEvent(
        orderId = "ORDER-1",
        userId = "USER-1",
        items = lines.map { ReservedItem(it.first, it.second) },
        correlationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
    )

    private fun counted(name: String): Double = registry.find(name).counter()?.count() ?: 0.0

    private fun outcomeFor(vararg items: InventoryItem) = OrderReserveOutcome(
        confirmedOrder = Order(
            orderId = "ORDER-1", userId = "USER-1", items = OrderItems(emptyList()), status = OrderStatus.CONFIRMED,
        ),
        updatedItems = items.toList(),
        reservations = emptyList(),
        reservedEvents = emptyList(),
        completedEvent = OrderCompletedEvent("ORDER-1", Instant.EPOCH),
    )

    // ---- read phase -----------------------------------------------------------------------

    @Test
    fun `an order whose items are all cached reads no inventory at all`() {
        cache.put("ITEM-A", item("ITEM-A"))
        cache.put("ITEM-B", item("ITEM-B"))

        handler.handle(event("ITEM-A" to 1, "ITEM-B" to 2))

        // The per-line path saved N findById calls here; this saves the whole round trip.
        verify(exactly = 0) { inventoryRepo.findAllById(any()) }
    }

    @Test
    fun `only the misses are fetched, and in one select`() {
        cache.put("ITEM-A", item("ITEM-A"))
        every { inventoryRepo.findAllById(any()) } answers {
            firstArg<Iterable<String>>().map { item(it) }
        }

        handler.handle(event("ITEM-A" to 1, "ITEM-B" to 1, "ITEM-C" to 1))

        verify(exactly = 1) { inventoryRepo.findAllById(listOf("ITEM-B", "ITEM-C")) }
    }

    @Test
    fun `a cached item is served at the version the cache holds, and that version is what the write checks`() {
        cache.put("ITEM-A", item("ITEM-A", qty = 10, version = 9L))

        handler.handle(event("ITEM-A" to 4))

        verify {
            batchWriter.updateAll(
                match { rows -> rows.single().version == 9L && rows.single().availableQty == 6 },
            )
        }
    }

    // ---- write phase ----------------------------------------------------------------------

    @Test
    fun `an optimistic conflict evicts the row that lost, and only that row`() {
        cache.put("ITEM-A", item("ITEM-A", version = 5L))
        cache.put("ITEM-B", item("ITEM-B", version = 5L))
        every { batchWriter.updateAll(any()) } throws InventoryVersionConflictException("ITEM-B", 5L)

        assertThrows<InventoryVersionConflictException> {
            writeHandler.write(outcomeFor(item("ITEM-A", version = 5L), item("ITEM-B", version = 5L)))
        }

        // ITEM-B's cached version can no longer satisfy the UPDATE predicate, so the retry has to
        // reread it. ITEM-A's still can — the rollback put the row back where it was read.
        assertNull(cache.getIfPresent("ITEM-B"))
        assertNotNull(cache.getIfPresent("ITEM-A"))
    }

    @Test
    fun `a successful write refreshes the cache with the version-incremented rows`() {
        writeHandler.write(outcomeFor(item("ITEM-A", qty = 9, version = 3L)))

        val cached = cache.getIfPresent("ITEM-A")
        assertEquals(4L, cached?.version, "the cache must hold the version the row now has")
        assertEquals(9, cached?.availableQty)
    }

    @Test
    fun `the version guard keeps the cache monotonic and never moves it backwards`() {
        // Cache already holds version 7; an out-of-order refresh carrying version 4 must not win.
        cache.put("ITEM-A", item("ITEM-A", qty = 5, version = 7L))
        every { batchWriter.updateAll(any()) } returns listOf(item("ITEM-A", qty = 4, version = 4L))

        writeHandler.write(outcomeFor(item("ITEM-A", qty = 4, version = 3L)))

        assertEquals(7L, cache.getIfPresent("ITEM-A")?.version)
        assertEquals(5, cache.getIfPresent("ITEM-A")?.availableQty)
    }

    // ---- what the cache reports ---------------------------------------------------------------

    /**
     * `inventory.opt.cache.hit` / `.miss` are ES-4's meter names, deliberately: `queries.promql`
     * and the "Aggregate cache" dashboard panel are shared across the two families and read those
     * two series by name. Caffeine's own `cache_gets_total{result}` describes the same lookups but
     * is read by nothing in the harness.
     */
    @Test
    fun `each item the cache serves counts a hit and each one it does not counts a miss`() {
        cache.put("ITEM-A", item("ITEM-A"))
        every { inventoryRepo.findAllById(any()) } answers {
            firstArg<Iterable<String>>().map { item(it) }
        }

        handler.handle(event("ITEM-A" to 1, "ITEM-B" to 1, "ITEM-C" to 1))

        assertEquals(1.0, counted("inventory.opt.cache.hit"))
        assertEquals(2.0, counted("inventory.opt.cache.miss"))
    }

    /** The lookup is per distinct item, so the hit rate must not move with ITEMS_PER_ORDER. */
    @Test
    fun `two lines naming one item are a single lookup`() {
        cache.put("ITEM-A", item("ITEM-A"))

        handler.handle(event("ITEM-A" to 1, "ITEM-A" to 1))

        assertEquals(1.0, counted("inventory.opt.cache.hit"))
        assertEquals(0.0, counted("inventory.opt.cache.miss"))
    }

    // ---- the cache itself -------------------------------------------------------------------

    @Test
    fun `expires an entry that was not used within the idle TTL window`() {
        // Drive Caffeine's clock manually so the test is deterministic and does not wait.
        val nanos = AtomicLong(0)
        val ttlCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .ticker(Ticker { nanos.get() })
            .build<String, InventoryItem>()

        ttlCache.put("ITEM-A", item("ITEM-A", version = 1L))

        // Just inside the window: a read keeps it alive and resets the access timer.
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(4))
        assertEquals(1L, ttlCache.getIfPresent("ITEM-A")?.version)

        // 5 minutes + 1ns of idleness after that read: the entry must be evicted.
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(5) + 1)
        ttlCache.cleanUp()
        assertNull(ttlCache.getIfPresent("ITEM-A"))
    }
}
