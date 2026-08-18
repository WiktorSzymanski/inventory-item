package pl.szymanski.wiktor.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Reservation
import java.sql.PreparedStatement

/**
 * The batch writer replaces Spring Data JDBC's generated statements by hand, so the things Spring
 * Data was doing for free are the things that can now silently stop happening: the version
 * predicate, the version bump, the full column rewrite, and the exception on a lost update.
 */
class InventoryBatchWriterTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val writer = InventoryBatchWriter(jdbc)

    private val sql = slot<String>()
    private val setter = slot<BatchPreparedStatementSetter>()

    private fun item(id: String, qty: Int = 5, version: Long = 7L, payload: String = "", delayMs: Int = 0) =
        InventoryItem(id = id, availableQty = qty, reserveDelayMs = delayMs, additionalBytes = payload, version = version)

    private fun expectBatch(counts: IntArray) {
        every { jdbc.batchUpdate(capture(sql), capture(setter)) } returns counts
    }

    @Test
    fun `the update checks the version it read and bumps it by one`() {
        expectBatch(intArrayOf(1))
        writer.updateAll(listOf(item("ITEM-A", qty = 4, version = 7L)))

        assertTrue(sql.captured.contains("WHERE item_id = ? AND version = ?"), sql.captured)

        val ps = mockk<PreparedStatement>(relaxed = true)
        setter.captured.setValues(ps, 0)
        verify { ps.setInt(1, 4) }        // available_qty
        verify { ps.setLong(4, 8L) }      // SET version = read + 1
        verify { ps.setString(5, "ITEM-A") }
        verify { ps.setLong(6, 7L) }      // WHERE version = the version phase 1 read
    }

    @Test
    fun `the update rewrites additional_bytes and reserve_delay_ms, as Spring Data JDBC did`() {
        // Not redundancy: rewriting the TOASTed payload on every reserve is the cost the
        // PAYLOAD_BYTES lever measures. Dropping these columns would make this path cheaper than
        // the per-line one it replaces for a reason that has nothing to do with transaction shape.
        expectBatch(intArrayOf(1))
        writer.updateAll(listOf(item("ITEM-A", payload = "xxxxx", delayMs = 12)))

        assertTrue(sql.captured.contains("additional_bytes = ?"), sql.captured)
        assertTrue(sql.captured.contains("reserve_delay_ms = ?"), sql.captured)

        val ps = mockk<PreparedStatement>(relaxed = true)
        setter.captured.setValues(ps, 0)
        verify { ps.setInt(2, 12) }
        verify { ps.setString(3, "xxxxx") }
    }

    @Test
    fun `rows go out sorted by item_id whatever order they arrive in`() {
        expectBatch(intArrayOf(1, 1, 1))
        writer.updateAll(listOf(item("ITEM-C"), item("ITEM-A"), item("ITEM-B")))

        val ps = mockk<PreparedStatement>(relaxed = true)
        val ids = (0..2).map { i ->
            val captured = slot<String>()
            every { ps.setString(5, capture(captured)) } returns Unit
            setter.captured.setValues(ps, i)
            captured.captured
        }
        // Postgres executes a batch in array order, so this IS the lock order.
        assertEquals(listOf("ITEM-A", "ITEM-B", "ITEM-C"), ids)
    }

    @Test
    fun `a lost update raises the exception InventoryService already retries`() {
        expectBatch(intArrayOf(1, 0, 1))

        val thrown = assertThrows<InventoryVersionConflictException> {
            writer.updateAll(listOf(item("ITEM-A"), item("ITEM-B", version = 7L), item("ITEM-C")))
        }
        // Still an OptimisticLockingFailureException, which is the type InventoryService.runOrderTask
        // keys its retry off. Narrowing that would strand every conflicted order.
        assertTrue(thrown is OptimisticLockingFailureException)
        // The zero row count is the second statement, so it must name ITEM-B — a writer that
        // reported the wrong row would send the retry loop chasing the wrong conflict, and would
        // send TO-4's cache eviction to the wrong key.
        assertEquals("ITEM-B", thrown.itemId)
        assertEquals(7L, thrown.expectedVersion)
        assertTrue(thrown.message!!.contains("ITEM-B"), thrown.message)
    }

    @Test
    fun `the returned rows carry the version the database now holds`() {
        // TO-4 refreshes its inventory-state cache from these. A writer that returned the rows as
        // they were READ would seed that cache one version behind the row it just wrote, and every
        // subsequent order on that item would conflict on a predicate that can no longer match.
        expectBatch(intArrayOf(1, 1))

        val written = writer.updateAll(
            listOf(item("ITEM-B", qty = 2, version = 7L), item("ITEM-A", qty = 3, version = 4L)),
        )

        assertEquals(listOf("ITEM-A", "ITEM-B"), written.map { it.id }, "returned in the order written")
        assertEquals(listOf(5L, 8L), written.map { it.version })
        assertEquals(listOf(3, 2), written.map { it.availableQty })
    }

    @Test
    fun `an empty batch touches the database not at all`() {
        writer.updateAll(emptyList())
        writer.insertAll(emptyList())
        verify(exactly = 0) { jdbc.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
    }

    @Test
    fun `reservations are inserted with the composite key the table declares`() {
        expectBatch(intArrayOf(1))
        writer.insertAll(listOf(Reservation(itemId = "ITEM-A", reservationId = "ORDER-1", quantity = 2)))

        assertTrue(sql.captured.startsWith("INSERT INTO reservations"), sql.captured)

        val ps = mockk<PreparedStatement>(relaxed = true)
        setter.captured.setValues(ps, 0)
        verify { ps.setString(1, "ITEM-A") }
        verify { ps.setString(2, "ORDER-1") }
        verify { ps.setInt(3, 2) }
    }
}
