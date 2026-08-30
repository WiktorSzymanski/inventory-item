package pl.szymanski.wiktor.repository

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The predicates that make a replayed saga step a no-op.
 *
 * These five statements ARE the idempotency argument for the whole branch, and they fail silently
 * when wrong: a predicate that matches too much lets a redelivered step reserve twice, and one that
 * matches too little stalls the saga with no error anywhere. Neither shows up in a bench run as
 * anything but a number that does not add up, so the SQL is asserted directly — its arguments and
 * the shape of its WHERE clause — rather than only through behaviour.
 *
 * Row-count semantics are the other half: exactly one row updated means "this caller owns the
 * step". Anything else means somebody else already did it, and the caller must write nothing.
 */
class SagaCursorWriterTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val writer = SagaCursorWriter(jdbc)

    private fun updateReturning(rows: Int) {
        every { jdbc.update(any<String>(), *anyVararg()) } returns rows
    }

    @Test
    fun `advance moves the cursor onto the next line and claims the current one`() {
        val sql = slot<String>()
        val args = mutableListOf<Any>()
        every { jdbc.update(capture(sql), *varargAll { args.add(it); true }) } returns 1

        assertTrue(writer.advance("ORDER-1", 3))

        // New value 4, claiming index 3: the predicate names the line being claimed, the SET names
        // where the cursor ends up. Swapping them would advance a saga twice per step.
        assertEquals(listOf<Any>(4, "ORDER-1", 3), args)
        assertTrue(sql.captured.contains("status = 'RUNNING'"), sql.captured)
        assertTrue(sql.captured.contains("current_index = ?"), sql.captured)
    }

    @Test
    fun `retreat moves the cursor back onto the line being released`() {
        val sql = slot<String>()
        val args = mutableListOf<Any>()
        every { jdbc.update(capture(sql), *varargAll { args.add(it); true }) } returns 1

        assertTrue(writer.retreat("ORDER-1", 2))

        // Releasing line 2 means the cursor was at 3 and ends at 2. Compensation walks the reserved
        // prefix backwards, so the line released is always the last one held.
        assertEquals(listOf<Any>(2, "ORDER-1", 3), args)
        assertTrue(sql.captured.contains("status = 'COMPENSATING'"), sql.captured)
    }

    @Test
    fun `a claim that matches no row is refused`() {
        updateReturning(0)

        assertFalse(writer.advance("ORDER-1", 3))
        assertFalse(writer.retreat("ORDER-1", 3))
        assertFalse(writer.beginCompensation("ORDER-1", "out of stock", "insufficient_stock"))
        assertFalse(writer.endCompleted("ORDER-1", 4))
        assertFalse(writer.endCompensated("ORDER-1"))
    }

    @Test
    fun `beginCompensation only fires from RUNNING, and records both reason and code`() {
        val sql = slot<String>()
        val args = mutableListOf<Any>()
        every { jdbc.update(capture(sql), *varargAll { args.add(it); true }) } returns 1

        assertTrue(writer.beginCompensation("ORDER-1", "out of stock", "insufficient_stock"))

        assertEquals(listOf<Any>("out of stock", "insufficient_stock", "ORDER-1"), args)
        // The RUNNING predicate is what stops a second failed line — or a redelivered failure —
        // starting a second walk back through lines that are already being released.
        assertTrue(sql.captured.contains("status = 'RUNNING'"), sql.captured)
        assertTrue(sql.captured.contains("status = 'COMPENSATING'"), sql.captured)
    }

    @Test
    fun `endCompleted requires every line to be reserved`() {
        val sql = slot<String>()
        val args = mutableListOf<Any>()
        every { jdbc.update(capture(sql), *varargAll { args.add(it); true }) } returns 1

        assertTrue(writer.endCompleted("ORDER-1", 4))

        assertEquals(listOf<Any>("ORDER-1", 4), args)
        // Without `current_index = lineCount` a replayed "last line reserved" event could confirm an
        // order whose compensation has already begun.
        assertTrue(sql.captured.contains("current_index = ?"), sql.captured)
        assertTrue(sql.captured.contains("status = 'ENDED'"), sql.captured)
    }

    @Test
    fun `endCompensated requires the cursor to be back at zero`() {
        val sql = slot<String>()
        every { jdbc.update(capture(sql), *anyVararg()) } returns 1

        assertTrue(writer.endCompensated("ORDER-1"))

        // Rejecting the order while lines are still held would strand that stock permanently:
        // nothing revisits an ENDED saga.
        assertTrue(sql.captured.contains("current_index = 0"), sql.captured)
        assertTrue(sql.captured.contains("status = 'COMPENSATING'"), sql.captured)
    }
}
