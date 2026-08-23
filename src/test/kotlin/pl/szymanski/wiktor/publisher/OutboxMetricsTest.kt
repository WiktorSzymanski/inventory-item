package pl.szymanski.wiktor.publisher

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The cursor gauges have to follow the ACTIVE arm.
 *
 * Reading the wrong arm's column is silent and ruinous: the watermark arm never writes `position`,
 * so a seq-based lag would grow with the sequence forever and the run would read as a drain that
 * had stopped dead — which is the exact conclusion the A/B exists to test. A bench run cannot catch
 * this, because a stalled-looking drain is a plausible result.
 */
class OutboxMetricsTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val registry = SimpleMeterRegistry()

    private fun metrics(watermarkEnabled: Boolean): OutboxMetrics {
        val queries = mutableListOf<String>()
        val sql = slot<String>()
        every { jdbc.queryForObject(capture(sql), Long::class.java) } answers {
            queries += sql.captured
            when {
                sql.captured.contains("xact_position") -> 700L
                sql.captured.contains("position") -> 40L
                sql.captured.contains("pg_snapshot_xmin") -> 756L
                sql.captured.contains("last_value") -> 100L
                else -> 0L
            }
        }
        issued = queries
        return OutboxMetrics(jdbc, registry, watermarkEnabled)
    }

    private lateinit var issued: MutableList<String>

    private fun gauge(name: String) = registry.get(name).gauge().value()

    @Test
    fun `the seq arm reads the seq cursor and the sequence high-water mark`() {
        metrics(watermarkEnabled = false).updateCursor()

        assertEquals(40.0, gauge("outbox.cursor.position"))
        assertEquals(60.0, gauge("outbox.cursor.lag")) // 100 - 40
        assertTrue(issued.none { it.contains("xact_position") }, "seq arm must not read the watermark column")
        assertTrue(issued.none { it.contains("pg_snapshot_xmin") }, "seq arm must not read xmin")
    }

    @Test
    fun `the watermark arm reads the watermark column and xmin`() {
        metrics(watermarkEnabled = true).updateCursor()

        assertEquals(700.0, gauge("outbox.cursor.position"))
        assertEquals(56.0, gauge("outbox.cursor.lag")) // 756 - 700, in transaction ids
        assertTrue(
            issued.none { it.contains("last_value") },
            "the watermark arm must not price its lag against the seq sequence — it never moves `position`, " +
                "so that lag grows with the whole table and reads as a drain that has stopped",
        )
    }
}
