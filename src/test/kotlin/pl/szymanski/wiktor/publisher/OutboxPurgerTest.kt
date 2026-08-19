package pl.szymanski.wiktor.publisher

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration

/**
 * Covers the contract the purge exists for: it must bound itself.
 *
 * The bug this whole change fixes was caused by long-lived transactions pinning the xmin horizon,
 * so a sweep that deleted a large backlog in one unbounded transaction would BE the bug. Every
 * batch is its own transaction and the sweep stops at maxBatches, which also keeps it from
 * starving the single shared scheduler thread it runs on alongside OutboxMetrics and
 * IncompleteEventRepublisher.
 */
class OutboxPurgerTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val registry = SimpleMeterRegistry()

    private fun purger(
        enabled: Boolean = true,
        batchSize: Int = 100,
        maxBatches: Int = 10,
        minAge: Duration = Duration.ofSeconds(60),
    ) = OutboxPurger(jdbc, registry, enabled, minAge, batchSize, maxBatches)

    private fun purgedCount() = registry.counter("outbox.purged").count().toInt()

    private fun deletesReturning(vararg counts: Int) {
        every { jdbc.update(any<String>(), *anyVararg()) } returnsMany counts.toList()
    }

    @Test
    fun `a short batch ends the sweep`() {
        deletesReturning(100, 100, 42)

        purger(batchSize = 100).purgeCompleted()

        verify(exactly = 3) { jdbc.update(any<String>(), *anyVararg()) }
        assertEquals(242, purgedCount())
    }

    @Test
    fun `an empty first batch ends the sweep immediately`() {
        deletesReturning(0)

        purger(batchSize = 100).purgeCompleted()

        verify(exactly = 1) { jdbc.update(any<String>(), *anyVararg()) }
        assertEquals(0, purgedCount())
    }

    @Test
    fun `a backlog of full batches stops at maxBatches rather than running to exhaustion`() {
        deletesReturning(*IntArray(50) { 100 })

        purger(batchSize = 100, maxBatches = 3).purgeCompleted()

        verify(exactly = 3) { jdbc.update(any<String>(), *anyVararg()) }
        assertEquals(300, purgedCount())
    }

    @Test
    fun `disabled purge touches the database not at all`() {
        purger(enabled = false).purgeCompleted()

        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
        assertEquals(0, purgedCount())
    }

    @Test
    fun `only rows completed before the cutoff are eligible`() {
        val sql = slot<String>()
        every { jdbc.update(capture(sql), *anyVararg()) } returns 0

        purger(minAge = Duration.ofSeconds(90)).purgeCompleted()

        // The guard that makes the sweep safe: an in-flight publication has completion_date NULL,
        // and NULL is never < anything, so it can never be selected.
        assertEquals(true, sql.captured.contains("completion_date <"))
        assertEquals(true, sql.captured.contains("LIMIT"))
    }
}
