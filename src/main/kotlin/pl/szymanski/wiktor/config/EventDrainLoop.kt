package pl.szymanski.wiktor.config

import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
 *  - **watermark** (`app.outbox-cursor.watermark=true`) — [drainFromWatermark], which fixes what
 *    the seq cursor gets wrong. See its own doc; the short version is that `seq` is assigned at
 *    INSERT and commit order is not insert order, so the seq cursor advances past rows that were
 *    not yet visible and strands them below itself (measured: 58,623 rescued in the first 20
 *    minutes of TO-2's capacity run, against TO-1's 0).
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
    private val watermarkEnabled: Boolean = false,
) {
    private val pending = AtomicBoolean(false)
    private val wakeup = Semaphore(0)

    @Volatile
    private var running = true

    /**
     * Set by [drainFromWatermark] when a pass found its window still closed, and read by [runLoop]
     * to wait with a timeout instead of blocking. NOTIFY is delivered at COMMIT, so the row that
     * woke the loop is committed — what keeps it invisible is an OLDER transaction still pinning
     * `xmin`. Nothing will signal on that transaction's behalf if it writes no publication of its
     * own (the purge's DELETE is exactly that), so a stalled pass has to come back by itself or the
     * row waits out the sweep's minute. Costs one query — an `xmin` read, no table access — per
     * [REARM_MILLIS] for as long as the stall lasts, and stops the moment the window opens.
     */
    @Volatile
    private var stalled = false

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
                // A stalled watermark pass gets a deadline instead of an indefinite block; every
                // other case blocks until something signals. Either way a pass follows.
                if (stalled) wakeup.tryAcquire(REARM_MILLIS, TimeUnit.MILLISECONDS) else wakeup.acquire()
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
        when {
            watermarkEnabled -> drainFromWatermark()
            cursorEnabled -> drainFromCursor()
            else -> drainByScan()
        }
    }

    /**
     * Deliver the half-open window `[cursor, xmin)`, then close it.
     *
     * **What this fixes.** [drainFromCursor] advances to the highest `seq` it has SEEN, and seq is
     * assigned at INSERT while the reserve path's row locks are taken three statements later — so a
     * transaction can hold a low seq and commit after higher ones have been drained, and the cursor
     * moves past rows that were never visible to it. They land below the cursor, where only the
     * sweep reaches them: every 60 s, min-age 60 s, in batches. Here the boundary is
     * `pg_snapshot_xmin`, below which every transaction is already decided, so there is nothing
     * behind it that has not been seen.
     *
     * **Why the boundary moves only at the end.** The window is closed once, after the last page,
     * never per page. A boundary saved per page would sit above rows this pass had not delivered
     * yet — and in this mode the sweep scans without a position predicate precisely because nothing
     * is meant to be stranded, so per-page saving would reintroduce the bug with no backstop.
     * Crashing mid-window replays the window instead, which the claim UPDATE makes a no-op.
     *
     * **What it costs.** The drain can no longer run ahead of the oldest in-flight write
     * transaction: one slow writer holds `xmin` and every delivery waits behind it. That is the
     * honest price of the guarantee, and it doubles as the pacing this branch otherwise has none of.
     *
     * The cursor still advances past deliveries that FAILED, exactly as [drainFromCursor] does and
     * for the same reason — a boundary one bad row can hold back degrades into the scan it
     * replaces. Those rows are the sweep's, and in this mode the sweep can reach them anywhere.
     */
    private fun drainFromWatermark() {
        val from = cursorStore.loadWatermark()
        // Once per pass. Re-reading between pages would widen the window under the pass's own feet
        // and admit rows sorting BELOW ones already delivered.
        val to = processor.currentWatermark()

        if (BigInteger(from) >= BigInteger(to)) {
            // The window never opened: xmin has not moved past where the last pass finished, so
            // anything committed since is pinned by an older transaction and cannot be proven
            // decided. Nothing to save — the boundary is already there — and nothing to read.
            stalled = true
            return
        }
        stalled = false

        var lastXact = from
        var lastSeq = 0L
        while (running) {
            val page = processor.findInWindow(from, to, lastXact, lastSeq, batchSize)
            if (page.isEmpty()) break

            val delivered = page
                .map { ref -> executor.submit<Boolean> { deliver(ref.id) } }
                .map { future -> runCatching { future.get() }.getOrDefault(false) }
                .count { it }

            // maxWith, not last(): the query is ordered, but the page key must not depend on that.
            // (xact_id, seq) is the only unique total order here — one transaction's six
            // publications all share its xact_id.
            val furthest = page.maxWith(compareBy({ BigInteger(it.xactId) }, { it.seq }))
            lastXact = furthest.xactId
            lastSeq = furthest.seq

            if (delivered < page.size) {
                log.warn(
                    "Window page of {} delivered {}; {} left to the sweep",
                    page.size, delivered, page.size - delivered,
                )
            }
            if (page.size < batchSize) break
        }

        // Closed: everything below `to` has been delivered or handed to the sweep.
        cursorStore.saveWatermark(to)
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

        /** How long a stalled watermark pass waits before retrying itself. See [stalled]. */
        const val REARM_MILLIS = 50L
    }
}
