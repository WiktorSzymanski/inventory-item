package pl.szymanski.wiktor.publisher

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Deletes delivered publications so `event_publication` stops growing without bound.
 *
 * The outbox guarantee only needs a row until it has been delivered; keeping completed rows forever
 * cost this branch its capacity.
 *
 * The win is not the disk it frees, it is what a small table does to autovacuum. The default
 * trigger is `50 + 0.2 * n_live_tup`, so 300k live rows tolerate 60,052 dead tuples before a
 * vacuum while 428 live rows tolerate 136 — and the cost of findIncompleteIds() is a pure function
 * of how many dead entries have piled up in front of it (4,872 buffers unvacuumed, 17 after a
 * VACUUM, on identical data). Keeping n_live_tup small keeps vacuum aggressive, which keeps that
 * scan cheap. V7 attacks the same mechanism from the other side by making the trigger absolute
 * rather than proportional; the two are complementary.
 *
 * Three properties matter more than the deleting:
 *
 * 1. **Its own thread, NOT @Scheduled.** Spring's default scheduler pool is one thread and no TO
 *    branch declares a spring.task.scheduling section. On TO-1 that single thread is what runs
 *    OutboxPollingPublisher.drain(), so a @Scheduled sweep would serialize against outbox delivery
 *    on that branch and not on this one — a per-branch confound in a comparison whose whole point
 *    is the delivery topology. A dedicated thread makes this component behave identically on all
 *    four branches, and drops the dependency on @EnableScheduling being present at all.
 *
 * 2. **Every batch is its own transaction.** The bug this whole change fixes was caused by
 *    long-lived transactions pinning the xmin horizon so dead tuples could not be reclaimed. A
 *    sweep that deleted a large backlog in one unbounded transaction would BE that bug. There is
 *    deliberately no @Transactional here: JdbcTemplate runs each update in autocommit, so each
 *    batch commits and releases its snapshot before the next one starts.
 *
 * 3. **A sweep is bounded by [maxBatches]**, so one pass cannot run unboundedly long no matter how
 *    far behind the table has fallen.
 *
 * Only rows with a non-NULL completion_date are eligible, so nothing in flight is ever touched. A
 * row deleted between a reader's findIncompleteIds() and its delivery attempt is harmless: the
 * claim `UPDATE ... WHERE id = ? AND completion_date IS NULL` matches 0 rows and the caller already
 * skips on that, exactly as it does when another delivery path wins the race.
 */
@Component
class OutboxPurger(
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry,
    @Value("\${app.outbox-purge.enabled:true}")
    private val enabled: Boolean,
    @Value("\${app.outbox-purge.interval:PT5S}")
    private val interval: Duration,
    @Value("\${app.outbox-purge.min-age:PT5S}")
    private val minAge: Duration,
    @Value("\${app.outbox-purge.batch-size:2000}")
    private val batchSize: Int,
    @Value("\${app.outbox-purge.max-batches:10}")
    private val maxBatches: Int,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val purged: Counter = meterRegistry.counter("outbox.purged")

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "outbox-purge").apply { isDaemon = true }
        }

    @PostConstruct
    fun start() {
        if (!enabled) {
            log.info("[OUTBOX] purge disabled; delivered publications will accumulate")
            return
        }
        val periodMs = interval.toMillis()
        // scheduleWithFixedDelay, not AtFixedRate: the gap is measured from the END of a sweep, so
        // a slow sweep can never queue another on top of itself.
        scheduler.scheduleWithFixedDelay(
            { runCatching { purgeCompleted() }.onFailure { e -> log.error("[OUTBOX] purge sweep failed", e) } },
            periodMs, periodMs, TimeUnit.MILLISECONDS,
        )
        log.info("[OUTBOX] purge every {} ms, deleting publications completed more than {} ms ago", periodMs, minAge.toMillis())
    }

    @PreDestroy
    fun stop() {
        scheduler.shutdownNow()
    }

    fun purgeCompleted() {
        if (!enabled) return

        var total = 0
        var batches = 0
        while (batches < maxBatches) {
            val deleted = deleteBatch()
            batches++
            if (deleted <= 0) break
            purged.increment(deleted.toDouble())
            total += deleted
            // A short batch means the eligible set is exhausted; anything completed since is
            // younger than the cutoff anyway and belongs to the next sweep.
            if (deleted < batchSize) break
        }

        if (total == 0) return
        if (batches >= maxBatches) {
            log.info("[OUTBOX] purge hit its batch bound: {} rows in {} batches, more remain", total, batches)
        } else {
            log.debug("[OUTBOX] purged {} completed publication(s) in {} batch(es)", total, batches)
        }
    }

    /**
     * One batch, one transaction. The subselect is what bounds it — a bare
     * `DELETE ... WHERE completion_date < ?` has no LIMIT in PostgreSQL and would take the whole
     * eligible set at once.
     *
     * `completion_date < now() - interval` is also the safety guard: an in-flight publication has
     * completion_date NULL, and NULL is never < anything, so it cannot be selected. Time comes from
     * the database rather than the JVM, matching how the incomplete-publication queries age rows.
     */
    private fun deleteBatch(): Int =
        jdbcTemplate.update(
            """DELETE FROM event_publication
               WHERE id IN (SELECT id FROM event_publication
                             WHERE completion_date < now() - make_interval(secs => ?)
                             LIMIT ?)""",
            minAge.toMillis() / 1000.0,
            batchSize,
        )
}
