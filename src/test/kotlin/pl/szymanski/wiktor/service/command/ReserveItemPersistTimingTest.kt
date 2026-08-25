package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
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

/**
 * The per-line counterpart to the split path's OrderWritePersistTimingTest: an attempt that throws
 * out of the two saves must still leave a `state_persist_time` sample behind, tagged
 * `outcome=conflict`, instead of vanishing from the histogram.
 *
 * The bias this guards against is structurally smaller on this branch than on the split-path ones —
 * `findForUpdateById` takes the row lock at read time, so the wait is priced into
 * `state_load_time{source=db_fetch}` and never reaches the write — but the tag has to be present
 * here too, or `outcome` means different things on different TO branches.
 */
class ReserveItemPersistTimingTest {

    private val inventoryRepo = mockk<InventoryRepository>()
    private val reservationRepo = mockk<ReservationRepository>(relaxed = true)
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val registry = SimpleMeterRegistry()

    private val handler = ReserveItemCommandHandler(
        inventoryRepo, reservationRepo, publisher, Clock.systemUTC(), registry,
    )

    private val command = ReserveItemCommand("ORDER-1", "ITEM-A", 1, UUID.randomUUID())

    init {
        every { inventoryRepo.findForUpdateById("ITEM-A") } returns
            Optional.of(InventoryItem(id = "ITEM-A", availableQty = 10, version = 3L))
        // Both saves are generic (<S : T> S), so a relaxed mock hands back a bare Object and the
        // Kotlin call site fails its cast before any assertion is reached.
        every { inventoryRepo.save(any<InventoryItem>()) } answers { firstArg() }
        every { reservationRepo.save(any<Reservation>()) } answers { firstArg() }
    }

    private fun persistCount(outcome: String): Long =
        registry.find("state_persist_time").tag("source", "db_write").tag("outcome", outcome)
            .timer()?.count() ?: 0L

    private fun outboxCount(): Long =
        registry.find("outbox.write.time").timer()?.count() ?: 0L

    @Test
    fun `a committed line is sampled as outcome=committed`() {
        handler.handle(command)

        assertEquals(1L, persistCount("committed"))
        assertEquals(0L, persistCount("conflict"))
    }

    @Test
    fun `a losing line is sampled as outcome=conflict, and still throws`() {
        every { inventoryRepo.save(any<InventoryItem>()) } throws
            OptimisticLockingFailureException("lost")

        assertThrows<OptimisticLockingFailureException> { handler.handle(command) }

        assertEquals(1L, persistCount("conflict"))
        assertEquals(0L, persistCount("committed"))
    }

    /**
     * Pins the span: on this path the outbox write is timed separately and sits OUTSIDE
     * state_persist_time, where the split path's `startNs` encloses it. The two are not the same
     * measurement, and a doc comment once claimed they were.
     */
    @Test
    fun `the outbox write is outside the persist sample`() {
        every { publisher.publishEvent(any<Any>()) } answers { Thread.sleep(30) }

        handler.handle(command)

        val persistMs = registry.find("state_persist_time").tag("outcome", "committed")
            .timer()!!.totalTime(TimeUnit.MILLISECONDS)
        assertEquals(1L, outboxCount())
        assert(persistMs < 25.0) {
            "state_persist_time must exclude the outbox publish on the per-line path; got ${persistMs}ms"
        }
    }
}
