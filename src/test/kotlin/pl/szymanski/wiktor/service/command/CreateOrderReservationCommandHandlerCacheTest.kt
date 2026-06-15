package pl.szymanski.wiktor.service.command

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
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
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.ReservationRepository

class CreateOrderReservationCommandHandlerCacheTest {

    private val inventoryRepo: InventoryRepository = mockk()
    private val reservationRepo: ReservationRepository = mockk(relaxed = true)
    private val orderRepo: OrderRepository = mockk(relaxed = true)
    private val eventPublisher: ApplicationEventPublisher = mockk(relaxed = true)
    private lateinit var cache: Cache<String, InventoryItem>
    private lateinit var handler: CreateOrderReservationCommandHandler

    private val command = CreateOrderReservationCommand("USER-1", listOf(OrderItem("ITEM-001", 1)))

    @BeforeEach
    fun setUp() {
        cache = Caffeine.newBuilder().build()
        handler = CreateOrderReservationCommandHandler(
            inventoryRepo, reservationRepo, orderRepo, eventPublisher, cache, SimpleMeterRegistry(),
        )
    }

    @Test
    fun `evicts the cached item on optimistic conflict so the retry rereads fresh`() {
        // Cache holds a (now stale) version; the conditional UPDATE fails.
        cache.put("ITEM-001", InventoryItem("ITEM-001", availableQty = 10, version = 5))
        every { inventoryRepo.saveAll(any<List<InventoryItem>>()) } throws
            OptimisticLockingFailureException("conflict")

        assertThrows<OptimisticLockingFailureException> { handler.handle("ORDER-1", command) }

        // Served from cache, so no DB fetch happened; the conflict must have evicted the entry.
        verify(exactly = 0) { inventoryRepo.findAllById(any()) }
        assertNull(cache.getIfPresent("ITEM-001"))
    }

    @Test
    fun `populates the cache with the version-incremented item after a successful commit`() {
        // Cache miss -> DB fetch returns version 3; save returns the bumped version 4.
        every { inventoryRepo.findAllById(any()) } returns
            listOf(InventoryItem("ITEM-001", availableQty = 10, version = 3))
        every { inventoryRepo.saveAll(any<List<InventoryItem>>()) } returns
            listOf(InventoryItem("ITEM-001", availableQty = 9, version = 4))

        handler.handle("ORDER-1", command)

        val cached = cache.getIfPresent("ITEM-001")
        assertEquals(4L, cached?.version)
        assertEquals(9, cached?.availableQty)
    }

    @Test
    fun `version guard keeps the cache monotonic and never moves it backwards`() {
        // Cache already holds version 7; an out-of-order refresh carrying version 4 must not win.
        cache.put("ITEM-001", InventoryItem("ITEM-001", availableQty = 5, version = 7))
        every { inventoryRepo.saveAll(any<List<InventoryItem>>()) } returns
            listOf(InventoryItem("ITEM-001", availableQty = 4, version = 4))

        handler.handle("ORDER-1", command)

        assertEquals(7L, cache.getIfPresent("ITEM-001")?.version)
    }
}
