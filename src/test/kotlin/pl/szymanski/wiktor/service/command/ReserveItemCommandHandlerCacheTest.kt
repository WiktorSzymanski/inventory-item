package pl.szymanski.wiktor.service.command

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.OptimisticLockingFailureException
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Reservation
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.ReservationRepository
import java.time.Clock
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ReserveItemCommandHandlerCacheTest {

    private val inventoryRepo: InventoryRepository = mockk()
    private val reservationRepo: ReservationRepository = mockk(relaxed = true)
    private val eventPublisher: ApplicationEventPublisher = mockk(relaxed = true)
    private lateinit var cache: Cache<String, InventoryItem>
    private lateinit var handler: ReserveItemCommandHandler

    private val command = ReserveItemCommand("ORDER-1", "ITEM-001", 1, UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        // save() is generic, so a relaxed answer can't produce a Reservation; echo the argument.
        every { reservationRepo.save(any<Reservation>()) } answers { firstArg() }
        cache = Caffeine.newBuilder().build()
        handler = ReserveItemCommandHandler(
            inventoryRepo, reservationRepo, eventPublisher, cache, Clock.systemUTC(), SimpleMeterRegistry(),
        )
    }

    @Test
    fun `evicts the cached item on optimistic conflict so the retry rereads fresh`() {
        // Cache holds a (now stale) version; the conditional UPDATE fails.
        cache.put("ITEM-001", InventoryItem("ITEM-001", availableQty = 10, version = 5))
        every { inventoryRepo.save(any()) } throws OptimisticLockingFailureException("conflict")

        assertThrows<OptimisticLockingFailureException> { handler.handle(command) }

        // Served from cache, so no DB fetch happened; the conflict must have evicted the entry.
        verify(exactly = 0) { inventoryRepo.findById(any()) }
        assertNull(cache.getIfPresent("ITEM-001"))
    }

    @Test
    fun `populates the cache with the version-incremented item after a successful commit`() {
        // Cache miss -> DB fetch returns version 3; save returns the bumped version 4.
        every { inventoryRepo.findById("ITEM-001") } returns
            Optional.of(InventoryItem("ITEM-001", availableQty = 10, version = 3))
        every { inventoryRepo.save(any()) } returns
            InventoryItem("ITEM-001", availableQty = 9, version = 4)

        handler.handle(command)

        val cached = cache.getIfPresent("ITEM-001")
        assertEquals(4L, cached?.version)
        assertEquals(9, cached?.availableQty)
    }

    @Test
    fun `version guard keeps the cache monotonic and never moves it backwards`() {
        // Cache already holds version 7; an out-of-order refresh carrying version 4 must not win.
        cache.put("ITEM-001", InventoryItem("ITEM-001", availableQty = 5, version = 7))
        every { inventoryRepo.save(any()) } returns
            InventoryItem("ITEM-001", availableQty = 4, version = 4)

        handler.handle(command)

        assertEquals(7L, cache.getIfPresent("ITEM-001")?.version)
    }

    @Test
    fun `expires an entry that was not used within the idle TTL window`() {
        // Drive Caffeine's clock manually so the test is deterministic and does not wait.
        val nanos = AtomicLong(0)
        val ttlCache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .ticker(Ticker { nanos.get() })
            .build<String, InventoryItem>()

        ttlCache.put("ITEM-001", InventoryItem("ITEM-001", availableQty = 10, version = 1))

        // Just inside the window: a read keeps it alive and resets the access timer.
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(4))
        assertEquals(1L, ttlCache.getIfPresent("ITEM-001")?.version)

        // 5 minutes + 1ns of idleness after that read: the entry must be evicted.
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(5) + 1)
        ttlCache.cleanUp()
        assertNull(ttlCache.getIfPresent("ITEM-001"))
    }
}
