package pl.szymanski.wiktor.publisher

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class OutboxMetrics(
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val backlog = meterRegistry.gauge("outbox.backlog", AtomicLong(0))

    /** Where the drain has got to. Flat while the drain is stalled, which no other series shows. */
    private val cursorPosition = meterRegistry.gauge("outbox.cursor.position", AtomicLong(0))

    /**
     * Publications assigned a seq that the cursor has not reached. Reads the SEQUENCE's high-water
     * mark, not `max(seq)`: `last_value` is O(1) and needs no index, and it counts rows still
     * uncommitted — which is the population the lag is actually about.
     */
    private val cursorLag = meterRegistry.gauge("outbox.cursor.lag", AtomicLong(0))

    /**
     * The oldest publication still undelivered. `min(seq)` on the partial index is its leftmost
     * entry, so this costs one index descent — unlike a `count(*)` of the region behind the cursor,
     * which would reintroduce at 5 s intervals exactly the scan this change exists to remove.
     */
    private val oldestIncompleteSeq = meterRegistry.gauge("outbox.oldest.incomplete.seq", AtomicLong(0))

    @Scheduled(fixedDelay = 5_000)
    fun updateBacklog() {
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
            Long::class.java,
        ) ?: 0L
        backlog.set(count)
    }

    /**
     * Separate from [updateBacklog] and defensive: on a database migrated before V8 these objects
     * do not exist, and a throwing @Scheduled method would take the backlog gauge down with it.
     */
    @Scheduled(fixedDelay = 5_000)
    fun updateCursor() {
        runCatching {
            val position = jdbcTemplate.queryForObject(
                "SELECT position FROM outbox_cursor WHERE id = 1", Long::class.java,
            ) ?: 0L
            val highWater = jdbcTemplate.queryForObject(
                "SELECT last_value FROM event_publication_seq_seq", Long::class.java,
            ) ?: 0L
            val oldest = jdbcTemplate.queryForObject(
                "SELECT coalesce(min(seq), 0) FROM event_publication WHERE completion_date IS NULL",
                Long::class.java,
            ) ?: 0L

            cursorPosition.set(position)
            cursorLag.set((highWater - position).coerceAtLeast(0))
            oldestIncompleteSeq.set(oldest)
        }.onFailure { e -> log.debug("[OUTBOX] cursor metrics unavailable", e) }
    }
}
