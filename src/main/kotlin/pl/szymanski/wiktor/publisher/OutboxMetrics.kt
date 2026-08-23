package pl.szymanski.wiktor.publisher

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class OutboxMetrics(
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry,
    @Value("\${app.outbox-cursor.watermark:false}")
    private val watermarkEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val backlog = meterRegistry.gauge("outbox.backlog", AtomicLong(0))

    /**
     * Where the drain has got to. Flat while the drain is stalled, which no other series shows.
     *
     * **Reads the ACTIVE arm's cursor**, and therefore changes UNIT with the arm — sequence entries
     * under `app.outbox-cursor.watermark=false`, transaction ids under `=true`. The question it
     * answers ("where is the drain, and how far behind the frontier") is the same either way, and
     * both are monotonic counters of the same shape, so the panels resolve unchanged.
     *
     * Reading the wrong arm's column here is worse than useless: the watermark arm never touches
     * `position`, so a seq-based lag would grow with the whole table forever and read as a drain
     * that has completely stopped, which is exactly the conclusion the run is trying to test.
     */
    private val cursorPosition = meterRegistry.gauge("outbox.cursor.position", AtomicLong(0))

    /**
     * How far the cursor is behind the frontier it is allowed to reach.
     *
     * Seq arm: publications assigned a seq the cursor has not reached, from the SEQUENCE's
     * high-water mark rather than `max(seq)` — `last_value` is O(1), needs no index, and counts
     * rows still uncommitted, which is the population the lag is actually about.
     *
     * Watermark arm: transaction ids between the saved boundary and `pg_snapshot_xmin`, i.e. the
     * work the drain could take on its next pass. Near zero when it keeps up, and it climbs for
     * exactly as long as one slow writer pins `xmin` — the head-of-line cost of the arm, which
     * no other series makes visible.
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
                if (watermarkEnabled) {
                    "SELECT xact_position::text::bigint FROM outbox_cursor WHERE id = 1"
                } else {
                    "SELECT position FROM outbox_cursor WHERE id = 1"
                },
                Long::class.java,
            ) ?: 0L
            val highWater = jdbcTemplate.queryForObject(
                if (watermarkEnabled) {
                    "SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint"
                } else {
                    "SELECT last_value FROM event_publication_seq_seq"
                },
                Long::class.java,
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
