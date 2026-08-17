package pl.szymanski.wiktor.service

import org.springframework.core.task.TaskExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Defers a conflict retry WITHOUT holding the caller's thread.
 *
 * The wait used to be Spring's `@Retryable` interceptor, which sleeps on the `order-worker` thread
 * that is retrying — so up to 375 ms of a worker's time is spent doing nothing, and under load the
 * pool can be entirely parked in backoff. The rebuild moved that wait to a SECOND pool
 * (`order-retry-*`, 50 threads) which served the backoff and then executed the retried attempt.
 * This is now backed by [OrderWorkerPool] instead — the same pool that ran the first attempt — so
 * there is no second pool and no hand-off hop. TO-3 still runs the two-pool topology, which is what
 * makes it the comparison.
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
 * happens and WHICH POOL runs the retried attempt, and nothing else: `delayMsFor(0..3)` is 25, 50,
 * 100, 200 ms, exactly the sleeps Spring's interceptor would have taken before attempts 2 through 5.
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
 * ONE pool for first attempts and retries.
 *
 * The previous topology ran two execution lanes — `order-worker-*` (150) for first attempts and
 * `order-retry-*` (50) which served the backoff AND executed the retried attempt, mirroring the ES
 * branches where Axon's `RetryingCallback` dispatches inline onto its own retry pool. TO-3 still
 * runs it that way. Here there is a single `ScheduledThreadPoolExecutor` of `150 + 50 = 200`
 * threads, all named `order-worker-*`:
 *
 *   - [execute] is the first-attempt path, submitted by the `@ApplicationModuleListener`;
 *   - [schedule] is the retry path, called BY THE WORKER THREAD THAT JUST FAILED, which then
 *     returns to the pool immediately.
 *
 * Nothing is held during the backoff. A `ScheduledThreadPoolExecutor` parks delayed tasks in a
 * `DelayedWorkQueue` ordered by due time; no thread and no DB connection is assigned to a task
 * until it comes due, so a thousand orders in backoff occupy a thousand queue entries and zero
 * threads. This is why merging the pools costs nothing at rest: the retry lane's threads were only
 * ever needed for the EXECUTION half of its job, and that half is what moved here.
 *
 * On queue position: `execute()` is a zero-delay scheduled task, so a retry due at T sorts behind
 * every first attempt submitted before T and ahead of every one submitted after — the same place
 * the FIFO tail gave it under the old `execute-on-retry-pool=false`. What differs from that setting
 * is that no second pool exists at all, and a retry no longer crosses a pool boundary to resume.
 *
 * The width is [OrderWorkerProperties.threads][pl.szymanski.wiktor.config.OrderWorkerProperties],
 * which now defaults to 200 precisely so total executing capacity and the connection demand match
 * the 150 + 50 this branch used to run. See `application.yaml`.
 *
 * NOT a `TaskScheduler`, NOT a `ScheduledExecutorService` — deliberately, see
 * [pl.szymanski.wiktor.config.OrderWorkerConfig].
 */
class OrderWorkerPool(threads: Int) : TaskExecutor, AutoCloseable {

    private val threadNumber = AtomicInteger(1)

    /**
     * Exposed so `OrderWorkerConfig` can bind `executor_*` to it. Boot auto-binds those series for
     * `ThreadPoolTaskExecutor` beans, which this is not, and the dashboards group by that tag.
     */
    internal val executor = ScheduledThreadPoolExecutor(threads) { runnable ->
        // Daemon, where the old ThreadPoolTaskExecutor workers were not. The merged pool holds
        // delayed tasks as a matter of course, and a pending retry must never be able to hold the
        // JVM open at shutdown — the retry pool made the same choice for the same reason.
        // Shutdown semantics only; nothing about throughput depends on it.
        Thread(runnable, "order-worker-${threadNumber.getAndIncrement()}").apply { isDaemon = true }
    }

    /**
     * Retries scheduled but not yet started, i.e. still serving out their backoff.
     *
     * Tracked by hand because the merged pool's own queue can no longer answer the question:
     * `order_retry_pool_queued` used to be the retry pool's `queue.size`, but here that queue is
     * `executor_queued_tasks` and holds READY first attempts alongside retries in backoff. Keeping
     * this series makes the two separable again — ready backlog is
     * `executor_queued_tasks - order_retry_pool_queued`.
     */
    private val backoffInFlight = AtomicInteger()

    override fun execute(task: Runnable) {
        executor.execute(task)
    }

    /** Never runs [task] on the calling thread. Throws `RejectedExecutionException` at shutdown. */
    fun schedule(delayMs: Long, task: Runnable) {
        backoffInFlight.incrementAndGet()
        try {
            executor.schedule(
                {
                    // Decremented when the attempt STARTS, so the gauge means "in backoff" and not
                    // "in backoff or running".
                    backoffInFlight.decrementAndGet()
                    task.run()
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (e: RejectedExecutionException) {
            backoffInFlight.decrementAndGet()
            throw e
        }
    }

    fun backoffInFlight(): Int = backoffInFlight.get()

    override fun close() {
        executor.shutdownNow()
    }
}
