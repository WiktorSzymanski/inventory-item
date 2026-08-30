package pl.szymanski.wiktor.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.szymanski.wiktor.exception.InsufficientStockException
import java.time.Clock
import java.util.UUID

/**
 * The two benchmark levers. Both must be provable no-ops at 0: phase 1 of the load-test
 * campaign measures every variant with both at 0 and compares those numbers against
 * phase-2 runs on the same binaries.
 */
class InventoryItemBenchKnobsTest {

    private val clock: Clock = Clock.systemUTC()

    private fun item(payload: Int = 0, delayMs: Int = 0, qty: Int = 10) =
        InventoryItem.create("ITEM-1", qty, UUID.randomUUID(), clock, payload, delayMs)

    @Test
    fun `padding is stored on the item row, not only on the creation event`() {
        val (created, event) = item(payload = 1024)
        assertEquals(1024, created.additionalBytes.length)
        assertEquals(1024, event.additionalBytes.length)
    }

    @Test
    fun `padding survives a reserve, so every read-modify-write carries it`() {
        val (created, _) = item(payload = 512)
        val result = created.reserve("RES-1", "RES-1", 0, 1, UUID.randomUUID(), clock)
        assertEquals(512, result.updatedItem.additionalBytes.length)
    }

    @Test
    fun `zero padding stores an empty string`() {
        val (created, event) = item(payload = 0)
        assertEquals("", created.additionalBytes)
        assertEquals("", event.additionalBytes)
    }

    @Test
    fun `a successful reserve sleeps for the configured delay`() {
        val (created, _) = item(delayMs = 50)
        val startedNs = System.nanoTime()
        created.reserve("RES-1", "RES-1", 0, 1, UUID.randomUUID(), clock)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs >= 45, "expected at least 45ms, slept ${elapsedMs}ms")
    }

    @Test
    fun `a zero delay does not sleep`() {
        val (created, _) = item(delayMs = 0)
        val startedNs = System.nanoTime()
        created.reserve("RES-1", "RES-1", 0, 1, UUID.randomUUID(), clock)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs < 20, "expected no sleep, took ${elapsedMs}ms")
    }

    @Test
    fun `a rejected reserve does not sleep`() {
        // The delay models expensive domain logic, reached only once the reserve is known to
        // succeed. Paying it on the out-of-stock path would make the rejection rate a hidden
        // throughput lever.
        val (created, _) = item(delayMs = 200, qty = 1)
        val startedNs = System.nanoTime()
        assertThrows<InsufficientStockException> {
            created.reserve("RES-1", "RES-1", 0, 5, UUID.randomUUID(), clock)
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue(elapsedMs < 20, "expected no sleep on rejection, took ${elapsedMs}ms")
    }
}
