package pl.szymanski.wiktor.service

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * TO-3-mod: defers a conflict retry WITHOUT holding the caller's thread.
 *
 * On stock TO-3 the wait is Spring's `@Retryable` interceptor, which sleeps on the `order-worker`
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
 * The retry policy, unchanged from TO-3's `@Retryable(maxRetries = 4, delay = 25,
 * multiplier = 2.0, maxDelay = 500)`. Spelled out here because TO-3-mod varies WHERE the wait
 * happens and nothing else: `delayMsFor(0..3)` is 25, 50, 100, 200 ms, exactly the sleeps Spring's
 * interceptor would have taken before attempts 2 through 5.
 *
 * Kept as constants rather than properties on purpose — a knob here would let a run differ from
 * TO-3 in the one dimension this branch must hold fixed.
 */
object OrderRetryPolicy {
    const val MAX_RETRIES = 4
    const val INITIAL_DELAY_MS = 25L
    const val MAX_DELAY_MS = 500L

    fun delayMsFor(attempt: Int): Long = (INITIAL_DELAY_MS shl attempt).coerceAtMost(MAX_DELAY_MS)
}

/**
 * Timing only: it schedules, it never runs the reservation itself. The re-submitted task goes back
 * to `orderWorkerExecutor`, so the width of the retry path equals the width of the first-attempt
 * path. (The ES branches do the opposite — Axon's `RetryingCallback` re-dispatches inline, so
 * retried commands execute on the 4-thread retry pool. That narrowing is a property of ES, and
 * reproducing it here would confound this A/B.)
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
