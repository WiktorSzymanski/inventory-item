package pl.szymanski.wiktor.config

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Closed-loop delivery driver for the NOTIFY path.
 *
 * This branch used to submit one task per NOTIFY straight onto the delivery pool: an open
 * loop with no backpressure, so a commit burst became a delivery burst, which drove the
 * order-worker pool into row contention (measured: db_write p95 404 ms and conflict_ratio
 * 4.64 against TO-1's 0.96 ms / 0.37 on the same workload). Here the NOTIFY payload is
 * discarded and the notification is only a wake-up: a burst collapses into a single pending
 * drain, and the drain itself is closed — [drainAll] blocks on every task in a page before
 * fetching the next one, so deliveries in flight can never exceed the executor's width.
 *
 * **Two ways to find the next page**, chosen by [cursorEnabled]:
 *
 *  - **cursor** (default) — [drainFromCursor] reads `seq > cursor` and advances the cursor to the
 *    page's highest seq. The read starts past everything already delivered, so an idle drain costs
 *    one index descent rather than a walk of the whole `completion_date IS NULL` region.
 *  - **scan** (`app.outbox-cursor.enabled=false`) — [drainByScan], the behaviour every TO-2 run
 *    before this change measured. Kept so the two can be A/B'd from ONE image, which is the only
 *    way the comparison is not confounded by everything else that differs between two builds.
 *
 * Either way this is TO-1's `OutboxPollingPublisher.drain()` contract with the scheduler tick
 * replaced by a NOTIFY signal, so delivery keeps NOTIFY's latency instead of waiting out a fixed
 * interval.
 */
class EventDrainLoop(
    private val processor: EventPublicationDirectProcessor,
    private val executor: ExecutorService,
    private val batchSize: Int,
    private val cursorStore: OutboxCursorStore,
    private val cursorEnabled: Boolean,
) {
    private val pending = AtomicBoolean(false)
    private val wakeup = Semaphore(0)

    @Volatile
    private var running = true

    /**
     * Request a drain. Coalescing: while one is already pending, further calls are free —
     * ten thousand NOTIFYs between two drains cost one drain, not ten thousand tasks.
     */
    fun signal() {
        if (pending.compareAndSet(false, true)) wakeup.release()
    }

    /**
     * Drain until stopped. Runs on its own thread; returns when [stop] is called.
     *
     * `pending` is cleared BEFORE draining, never after, so a NOTIFY that arrives mid-drain
     * re-arms the flag and is served by the next pass rather than being swallowed. The
     * inverse race — a NOTIFY landing between `acquire()` and `pending.set(false)` — loses
     * its permit but not its work: that row is already committed, so the drain about to run
     * will see it. A redundant extra pass is harmless; the claim UPDATE in
     * [EventPublicationDirectProcessor.process] makes delivery idempotent and an empty page
     * ends the pass immediately.
     */
    fun runLoop() {
        while (running) {
            try {
                wakeup.acquire()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!running) return
            pending.set(false)
            runCatching { drainAll() }
                .onFailure { e -> log.error("Drain pass failed", e) }
        }
    }

    fun drainAll() {
        if (cursorEnabled) drainFromCursor() else drainByScan()
    }

    /**
     * Deliver forward from the persisted cursor, in pages of [batchSize].
     *
     * The cursor advances to the page's highest seq **unconditionally** — after a page in which
     * some, or even all, deliveries failed. That is deliberate, and it is what makes the fast path
     * fast: a cursor that could be held back by one bad row would stall behind it and the drain
     * would degrade to the scan it replaces. The rows it moves past are not lost, they are handed
     * to the other process — [IncompleteEventRepublisher] sweeps everything still incomplete BELOW
     * the cursor.
     *
     * The cursor is read once per pass and carried in a local, so a multi-page drain does not
     * re-read it; it is written once per page, after that page's `future.get()` barrier, which is
     * the only point at which the page's outcome is known.
     */
    private fun drainFromCursor() {
        var cursor = cursorStore.load()
        while (running) {
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
            // A short page means the backlog is exhausted. Anything committed since has
            // raised its own signal.
            if (page.size < batchSize) return
        }
    }

    /**
     * Deliver every incomplete publication, in pages of [batchSize]. The pre-cursor behaviour.
     *
     * Paging keeps a large backlog from being materialised in one go — the open-loop
     * breakpoint run stranded 439k publications, which as a single unbounded fetch would be 439k UUIDs
     * and 439k queued tasks.
     */
    private fun drainByScan() {
        while (running) {
            val ids = processor.findIncompleteIds(Duration.ZERO, batchSize)
            if (ids.isEmpty()) return

            val delivered = ids
                .map { id -> executor.submit<Boolean> { deliver(id) } }
                .map { future -> runCatching { future.get() }.getOrDefault(false) }
                .count { it }

            // Every row in a full page failing means delivery is broken, not merely behind;
            // refetching would spin on the same rows. Bail and let the republisher retry.
            // The cursor path needs no such guard: it cannot refetch a page it has passed.
            if (delivered == 0) {
                log.error("Drain page of {} publication(s) delivered nothing; ending pass", ids.size)
                return
            }
            // A short page means the backlog is exhausted. Anything committed since has
            // raised its own signal.
            if (ids.size < batchSize) return
        }
    }

    private fun deliver(id: UUID): Boolean =
        runCatching { processor.process(id) }
            .onFailure { e -> log.error("Failed to deliver publication {}", id, e) }
            .isSuccess

    /** Unpark [runLoop] so its thread can exit. */
    fun stop() {
        running = false
        wakeup.release()
    }

    companion object {
        private val log = LoggerFactory.getLogger(EventDrainLoop::class.java)
    }
}
