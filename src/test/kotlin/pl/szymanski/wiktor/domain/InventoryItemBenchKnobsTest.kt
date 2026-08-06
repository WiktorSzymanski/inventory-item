package pl.szymanski.wiktor.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * The benchmark levers. Each must be a provable no-op at 0: phase 1 of the load-test
 * campaign measures every variant with them at 0 and compares those numbers against
 * phase-2 runs on the same binaries.
 */
class InventoryItemBenchKnobsTest {

    private val clock: Clock = Clock.systemUTC()

    private fun item(payload: Int = 0, qty: Int = 10) =
        InventoryItem.create("ITEM-1", qty, UUID.randomUUID(), clock, payload)

    @Test
    fun `padding is stored on the item row, not only on the creation event`() {
        val (created, event) = item(payload = 1024)
        assertEquals(1024, created.additionalBytes.length)
        assertEquals(1024, event.additionalBytes.length)
    }

    @Test
    fun `padding survives a reserve, so every read-modify-write carries it`() {
        val (created, _) = item(payload = 512)
        val result = created.reserve("RES-1", 1, UUID.randomUUID(), clock)
        assertEquals(512, result.updatedItem.additionalBytes.length)
    }

    @Test
    fun `zero padding stores an empty string`() {
        val (created, event) = item(payload = 0)
        assertEquals("", created.additionalBytes)
        assertEquals("", event.additionalBytes)
    }
}
