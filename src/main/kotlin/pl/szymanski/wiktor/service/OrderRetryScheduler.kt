package pl.szymanski.wiktor.service

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Defers a conflict retry WITHOUT holding the caller's thread.
 *
 * The wait used to be Spring's `@Retryable` interceptor, which sleeps on the `order-worker`
 * thread that is retrying — so up to 375 ms of a worker's time is spent doing nothing, and under
 * load the pool can be entirely parked in backoff. Here the worker returns immediately and the
 * attempt is re-submitted later.
 *
 * Implementations must NOT run the task on the calling thread, and may throw
 * `RejectedExecutionException` (shutdown) — callers are required to handle that, because a lost
 * task means an order stranded in PENDING.
 */
fun interface OrderRetryScheduler {
    fun schedule(delayMs: Long, task: Runnable)
}

/**
 * The retry policy, unchanged from the former `@Retryable(maxRetries = 4, delay = 25,
 * multiplier = 2.0, maxDelay = 500)`. Spelled out here because the rebuild varies WHERE the wait
 * happens and nothing else: `delayMsFor(0..3)` is 25, 50, 100, 200 ms, exactly the sleeps Spring's
 * interceptor would have taken before attempts 2 through 5.
 *
 * Kept as constants rather than properties on purpose — a knob here would let a run vary the one
 * dimension this rebuild must hold fixed.
 */
object OrderRetryPolicy {
    const val MAX_RETRIES = 4
    const val INITIAL_DELAY_MS = 25L
    const val MAX_DELAY_MS = 500L

    fun delayMsFor(attempt: Int): Long = (INITIAL_DELAY_MS shl attempt).coerceAtMost(MAX_DELAY_MS)
}

/**
 * Runs the scheduled task on its own pool, never on the caller.
 *
 * Whether that task IS the retried attempt or merely a hand-off back to `orderWorkerExecutor` is
 * decided by `app.order-retry.execute-on-retry-pool`, in `InventoryService` — see
 * `OrderRetryProperties`. Both topologies matter: executing here mirrors the ES branches, where
 * Axon's `RetryingCallback` re-dispatches inline onto its own retry pool; handing back runs retries
 * at full worker width, which is the configuration that differs from the old behaviour in a
 * single dimension.
 */
class DelayedOrderRetryScheduler(
    private val executor: ScheduledExecutorService,
) : OrderRetryScheduler, AutoCloseable {

    override fun schedule(delayMs: Long, task: Runnable) {
        executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
