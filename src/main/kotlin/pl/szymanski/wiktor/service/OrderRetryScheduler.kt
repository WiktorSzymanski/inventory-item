package pl.szymanski.wiktor.service

import org.springframework.core.task.TaskExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.random.RandomGenerator
import kotlin.math.roundToLong

/**
 * Defers a conflict retry WITHOUT holding the caller's thread.
 *
 * The wait used to be Spring's `@Retryable` interceptor, which sleeps on the `order-worker` thread
 * that is retrying — so up to 375 ms of a worker's time is spent doing nothing, and under load the
 * pool can be entirely parked in backoff. The rebuild moved that wait to a SECOND pool
 * (`order-retry-*`, 50 threads) which served the backoff and then executed the retried attempt.
 * This is now backed by [OrderWorkerPool] instead — the same pool that ran the first attempt — so
 * there is no second pool and no hand-off hop. No branch carries the two-pool topology any more —
 * TO-1, TO-2, TO-3 and TO-4 all merged it away — so it lives in the git history, not in a sibling
 * branch.
 *
 * Implementations must NOT run the task on the calling thread, and may throw
 * `RejectedExecutionException` (shutdown) — callers are required to handle that, because a lost
 * task means an order stranded in PENDING.
 */
fun interface OrderRetryScheduler {
    fun schedule(delayMs: Long, task: Runnable)
}

/**
 * The retry policy: the attempt budget and the backoff CURVE of the former
 * `@Retryable(maxRetries = 4, delay = 25, multiplier = 2.0, maxDelay = 500)`, plus JITTER.
 *
 * [baseDelayMsFor] is the curve waited exactly by the branches that do not jitter — the per-line
 * TO branches (TO-2-opt, TO-3-pessimistic, TO-3-mod-A) and every ES branch — 25, 50, 100, 200 ms
 * before attempts 2 through 5. [delayMsFor] spreads each of those uniformly over
 * `[0.5 x base, 1.5 x base)`.
 *
 * WHY JITTER HERE. This branch reads outside the transaction, so conflicting orders are no longer
 * serialised by row locks and there are more of them. Without jitter they retry in lockstep: two
 * orders that collide at T both wake at T+25, collide again, both wake at T+75, and the convoy
 * re-forms at every step. Deterministic backoff is cheap when conflicts are rare and is a
 * self-inflicted wound when they are not — which is why the jitter arrived WITH the split
 * transaction rather than before it, and why the two changes are separate commits.
 *
 * WHY SYMMETRIC AND NOT AWS's FULL JITTER (`rand(0, base)`). The expected delay here is exactly
 * `base` — 375 ms of accumulated backoff across a fully exhausted order, the same figure the
 * un-jittered branches spend. Full jitter halves it, which would change the retry BUDGET as well
 * as its correlation and leave `order.retry.backoff.time` measuring a different thing here than on
 * the branches this one is compared against. Only the spread differs; the mean does not.
 *
 * The `MAX_DELAY_MS` cap is applied AFTER jitter, so a jittered delay can never exceed it. At
 * `MAX_RETRIES = 4` the cap never binds (the largest draw is under 300 ms) and mean preservation is
 * therefore exact; raise MAX_RETRIES and the cap starts clipping the upper tail, which biases the
 * mean DOWN. That is the trade the cap exists for, but it is worth knowing before changing the
 * budget.
 *
 * Still constants, not properties. A run must not be able to vary the backoff, or a bench result
 * stops being a property of the branch.
 */
object OrderRetryPolicy {
    const val MAX_RETRIES = 4
    const val INITIAL_DELAY_MS = 25L
    const val MAX_DELAY_MS = 500L

    /** Half-width of the jitter window, as a fraction of the base delay. 0.5 = base +/- 50%. */
    const val JITTER_RATIO = 0.5

    /** The deterministic curve, without jitter: 25, 50, 100, 200 ms for attempts 0..3. */
    fun baseDelayMsFor(attempt: Int): Long = (INITIAL_DELAY_MS shl attempt).coerceAtMost(MAX_DELAY_MS)

    /**
     * [baseDelayMsFor] spread uniformly over `[0.5 x base, 1.5 x base)`, so the expected value is
     * the base delay itself.
     *
     * [random] defaults to [ThreadLocalRandom] — every order-worker thread draws from this on every
     * conflict, and a shared `Random` would put them in contention on one atomic seed while trying
     * to decorrelate them. It is a parameter only so the policy is testable without statistics.
     */
    fun delayMsFor(attempt: Int, random: RandomGenerator = ThreadLocalRandom.current()): Long {
        val base = baseDelayMsFor(attempt)
        val factor = (1.0 - JITTER_RATIO) + (2.0 * JITTER_RATIO * random.nextDouble())
        // At least 1 ms: a zero delay would put the retry back on the queue with no wait at all,
        // which is the convoy this exists to break.
        return (base * factor).roundToLong().coerceIn(1L, MAX_DELAY_MS)
    }
}

/**
 * ONE pool for first attempts and retries.
 *
 * The previous topology ran two execution lanes — `order-worker-*` (150) for first attempts and
 * `order-retry-*` (50) which served the backoff AND executed the retried attempt, mirroring the ES
 * branches where Axon's `RetryingCallback` dispatches inline onto its own retry pool. No TO branch
 * runs it that way now. Here there is a single `ScheduledThreadPoolExecutor`, all of whose threads
 * are named `order-worker-*`:
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
 * which defaults to 50 — the same 50 docker-compose pins, and the width every benchmarked run has
 * actually used. The merge was sized at `150 + 50 = 200` to be width-neutral; the cut to 50 came
 * after, and applies to first attempts and retries alike. See `application.yaml`.
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
