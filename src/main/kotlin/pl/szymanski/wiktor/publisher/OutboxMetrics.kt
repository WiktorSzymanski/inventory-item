package pl.szymanski.wiktor.publisher

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Component
class OutboxMetrics(
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val backlog = meterRegistry.gauge("outbox.backlog", AtomicLong(0))

    /**
     * How full PostgreSQL's async-notify queue is, 0..1 — the SERVER side of this branch's
     * backpressure, and the one number that says whether the bound has reached the writers.
     *
     * Delivery here is push: when the delivery pool saturates, `pg-notify-listener` blocks on submit
     * and stops draining this queue, so it grows and `pg_notify` at commit gets slower. Nothing else
     * in the metric set shows that — `outbox_backlog` stays low precisely because the rows are being
     * delivered, just behind a queue. A rising value alongside a pinned `outbox_notify_queue_depth`
     * is the signature of backpressure working as designed; approaching 1.0 is the failure mode,
     * where NOTIFY starts failing at commit.
     */
    private val notifyQueueUsage: AtomicReference<Double> =
        meterRegistry.gauge("outbox.notify.queue.usage", AtomicReference(0.0)) { it.get() }!!

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
     * Separate from [updateBacklog] and defensive: a throwing @Scheduled method would take the
     * backlog gauge down with it.
     *
     * `outbox.cursor.position` and `outbox.cursor.lag` are deliberately NOT published on this
     * branch. V8's cursor is still in the schema, but delivery no longer reads it — [EventDrainLoop]
     * runs only on reconnect — so the cursor sits still while the sequence advances, and a lag gauge
     * computed against the sequence high-water mark would climb forever and read as a completely
     * stalled drain. That exact gauge lied on TO-2-fix-A for the same reason, and "stalled drain" is
     * a plausible enough result that a bench run cannot catch it. Neither series is referenced by
     * `queries.promql` or by either dashboard, so nothing downstream loses a panel.
     *
     * `outbox.oldest.incomplete.seq` survives: min(seq) over the partial index is one index descent
     * and still means what it says — the oldest thing not yet delivered, by whichever path.
     */
    @Scheduled(fixedDelay = 5_000)
    fun updateNotifyQueue() {
        runCatching {
            val usage = jdbcTemplate.queryForObject(
                "SELECT pg_notification_queue_usage()", Double::class.java,
            ) ?: 0.0
            val oldest = jdbcTemplate.queryForObject(
                "SELECT coalesce(min(seq), 0) FROM event_publication WHERE completion_date IS NULL",
                Long::class.java,
            ) ?: 0L

            // Held as a Double, not the AtomicLong the other gauges use: this is a 0..1 fraction
            // and every realistic value would round to 0 as a long.
            notifyQueueUsage.set(usage)
            oldestIncompleteSeq.set(oldest)
        }.onFailure { e -> log.debug("[OUTBOX] notify-queue metrics unavailable", e) }
    }
}
