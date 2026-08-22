package pl.szymanski.wiktor.publisher

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.config.EventPublicationDirectProcessor
import pl.szymanski.wiktor.config.OutboxCursorStore
import java.time.Duration
import java.util.UUID

/**
 * Primary delivery path of the polling variant: every tick dispatches undelivered publications to
 * the outbox-poller worker pool, which routes each one through the claim-guarded
 * [EventPublicationDirectProcessor] — the same delivery core as TO-2, with the poll tick instead of
 * NOTIFY as the trigger. Each row is claimed, delivered to the @ApplicationModuleListener recorded
 * in its listener_id, and marked complete in one transaction per publication, so a delivered row is
 * never re-delivered and a failed one stays undelivered (at-least-once).
 *
 * **Two ways to find the next page**, chosen by [cursorEnabled]:
 *
 *  - **cursor** (default) — [drainFromCursor] reads `seq > cursor` off the partial index added by
 *    V8 and advances the cursor to the page's highest seq. The read starts past everything already
 *    delivered, so an idle tick costs one index descent rather than a walk of the whole
 *    `completion_date IS NULL` region. Rows the cursor moves past undelivered are owned by the
 *    SECOND delivery process, [pl.szymanski.wiktor.config.IncompleteEventRepublisher].
 *  - **scan** (`app.outbox-cursor.enabled=false`) — [drainByScan], the behaviour every TO-1 run
 *    before this change measured: one unbounded `findIncompleteIds` per tick and no second process
 *    at all. Kept so the two topologies can be A/B'd from ONE image, which is the only way the
 *    comparison is not confounded by everything else that differs between two builds.
 *
 * The tick blocks until the whole batch is delivered, so the fixedDelay contract guarantees ticks
 * never overlap in-flight deliveries — which is also why neither loop here needs TO-2's `running`
 * guard: there is only ever one pass. With app.outbox-poller.threads > 1 publications are delivered
 * in parallel; publication order is then only preserved per worker thread.
 */
@Component
class OutboxPollingPublisher(
    private val processor: EventPublicationDirectProcessor,
    @Qualifier("outboxPollerExecutor") private val executor: ThreadPoolTaskExecutor,
    private val cursorStore: OutboxCursorStore,
    @Value("\${app.outbox-cursor.enabled:true}")
    private val cursorEnabled: Boolean,
    @Value("\${app.event-delivery.batch-size:1000}")
    private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /** Logged once at startup so a bench run cannot record the wrong arm of the A/B. */
    @PostConstruct
    fun logMode() {
        log.info(
            "[OUTBOX] drain mode={} (app.outbox-cursor.enabled), batch-size={}",
            if (cursorEnabled) "CURSOR" else "SCAN", batchSize,
        )
    }

    @Scheduled(fixedDelayString = "\${spring.modulith.events.polling-interval:PT10S}")
    fun drain() {
        if (cursorEnabled) drainFromCursor() else drainByScan()
    }

    /**
     * Deliver forward from the persisted cursor, in pages of [batchSize].
     *
     * The cursor advances to the page's highest seq **unconditionally** — after a page in which
     * some, or even all, deliveries failed. That is deliberate, and it is what makes the fast path
     * fast: a cursor that could be held back by one bad row would stall behind it and the tick
     * would degrade to the scan it replaces. The rows it moves past are not lost, they are handed
     * to the other process — [pl.szymanski.wiktor.config.IncompleteEventRepublisher] sweeps
     * everything still incomplete BELOW the cursor.
     *
     * The cursor is read once per tick and carried in a local, so a multi-page drain does not
     * re-read it; it is written once per page, after that page's `future.get()` barrier, which is
     * the only point at which the page's outcome is known.
     */
    private fun drainFromCursor() {
        var cursor = cursorStore.load()
        while (true) {
            val page = processor.findAfterCursor(cursor, batchSize)
            if (page.isEmpty()) return

            val delivered = page
                .map { ref -> executor.submit<Boolean> { deliver(ref.id) } }
                .map { future -> runCatching { future.get() }.getOrDefault(false) }
                .count { it }

            // maxOf, not last(): the query is ordered, but the cursor must not depend on that.
            cursor = page.maxOf { it.seq }
            cursorStore.save(cursor)

            if (delivered < page.size) {
                // Not an error and not a reason to stop — the sweep owns these now. Logged
                // because a page that delivers NOTHING while the cursor keeps moving is what a
                // broken listener looks like from here, and it is otherwise invisible.
                log.warn(
                    "Drain page of {} delivered {}; cursor advanced to {}, {} left to the sweep",
                    page.size, delivered, cursor, page.size - delivered,
                )
            }
            // A short page means the backlog is exhausted. Anything committed since belongs to the
            // next tick, which is 0.1 s away.
            if (page.size < batchSize) return
        }
    }

    /**
     * Sweep every incomplete publication, unbounded, in one query per tick. The pre-V8 behaviour,
     * unchanged down to the `ORDER BY publication_date` in [EventPublicationDirectProcessor
     * .findIncompleteIds] — this is the baseline arm and it has to be byte-for-byte what earlier
     * TO-1 runs measured, not a tidied-up version of it.
     */
    private fun drainByScan() {
        val futures = processor.findIncompleteIds(Duration.ZERO).map { id ->
            executor.submit { deliver(id) }
        }
        futures.forEach { it.get() }
    }

    private fun deliver(id: UUID): Boolean =
        runCatching { processor.process(id) }
            .onFailure { e -> log.error("Failed to deliver publication {}", id, e) }
            .isSuccess
}
