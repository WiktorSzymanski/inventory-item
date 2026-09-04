package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.commandhandling.CommandMessage
import org.axonframework.commandhandling.gateway.RetryScheduler
import org.axonframework.modelling.command.ConcurrencyException
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.random.RandomGenerator
import kotlin.math.roundToLong

/**
 * The retry pool TIMES the backoff; it does not execute the retried command.
 *
 * This class used to schedule Axon's `commandDispatch` Runnable directly onto [retryExecutor], and
 * that is where the whole retried command then ran — aggregate load, `reserveDelayMs` sleep, append
 * and commit. Not a quirk of this code: Axon's `RetryingCallback.RetryDispatch.run()` calls
 * `commandBus.dispatch(...)` INLINE, and the autoconfigured `SimpleCommandBus` handles on the
 * calling thread. The retry pool was therefore a second EXECUTION lane, and its width a hard cap on
 * retried work — which on a lock-free branch is where most contended work ends up.
 *
 * Now the scheduled task only hands the dispatch to [commandExecutor], so first attempts and
 * retries share one pool. Axon neither documents the inline-dispatch consequence nor forbids this:
 * `RetryScheduler`'s contract is "schedule this Runnable", and where it runs is the caller's
 * choice. This is the ES analogue of TO's `ORDER_RETRY_EXECUTE_ON_RETRY_POOL=false`.
 *
 * What that buys and costs: retries get the full command-pool width instead of a 30-thread lane,
 * but they rejoin an unbounded FIFO at the TAIL, behind first attempts admitted after them — so a
 * retry's real wait becomes backoff + queue depth.
 *
 * PORTED FROM ES-4 ON 2026-08-20, where it arrived as `ES-4-NullLock-oneExec`. ES-1, ES-2 and ES-4
 * now share this topology; ES-3 still runs the two-lane shape, so it is the one ES design point
 * where a retry executes on the retry pool.
 *
 * THE BACKOFF IS JITTERED HERE, as of 2026-09-04 — on ES-1, ES-1-parallel, ES-2 and ES-2-parallel,
 * and on no other ES branch. [baseDelayMsFor] is the deterministic curve ES-3, ES-4 and its four
 * arms, and ES-2-mongo still wait; [delayMsFor] is what this one waits, and carries the reasoning. The retry BUDGET is unchanged, so the divergence is the correlation
 * between retrying orders and nothing else.
 */
class ConcurrencyRetryScheduler(
    private val retryExecutor: ScheduledExecutorService,
    private val commandExecutor: Executor,
    private val maxRetries: Int = 5,
    private val initialDelayMs: Long = 25L,
    meterRegistry: MeterRegistry,
    /**
     * [ThreadLocalRandom] by default — 112 `saga-command` threads draw from this on every conflict,
     * and a shared `Random` would put them in contention on one atomic seed while trying to
     * decorrelate them. A parameter only so the jitter is testable without statistics.
     */
    private val random: RandomGenerator = ThreadLocalRandom.current(),
) : RetryScheduler {

    companion object {
        private val log = LoggerFactory.getLogger(ConcurrencyRetryScheduler::class.java)

        /** The ceiling on a single backoff. Applied AFTER jitter — see [delayMsFor]. */
        const val MAX_DELAY_MS = 500L

        /** Half-width of the jitter window, as a fraction of the base delay. 0.5 = base +/- 50%. */
        const val JITTER_RATIO = 0.5
    }

    /**
     * The deterministic curve — 25, 50, 100, 200, 400 ms before attempts 2 through 6 — waited
     * exactly by every ES branch that does not jitter: ES-3, ES-4 and its four arms, and
     * ES-2-mongo. Kept separate from [delayMsFor] so the two are readable against each other and
     * so a test can assert the curve did not move.
     */
    internal fun baseDelayMsFor(attempt: Int): Long =
        (initialDelayMs * (1L shl attempt)).coerceAtMost(MAX_DELAY_MS)

    /**
     * [baseDelayMsFor] spread uniformly over `[0.5 x base, 1.5 x base)`.
     *
     * WHY JITTER AT ALL. Without it every order waits the same curve, so orders that collide at T
     * both wake at T+25, collide again, both wake at T+75, and the convoy re-forms at every step.
     * Nothing in this stack breaks that symmetry: the aggregate is lock-free, contention is
     * resolved by the event store's `UNIQUE (aggregate_identifier, sequence_number)` and this
     * retry, and the retried commands rejoin one FIFO together. The `-parallel` arms make it worse
     * still, dispatching every reserve command for an order at once so the collisions arrive in
     * tighter bunches. This is the same change TO took on 2026-08-18.
     *
     * WHY SYMMETRIC AND NOT AWS's FULL JITTER (`rand(0, base)`). The expected delay here is the
     * base delay itself, so the retry BUDGET is preserved and the only thing that differs from the
     * un-jittered ES branches is the correlation. Full jitter halves the budget — ~387 ms against
     * 775 ms — which would move two variables at once and make a run here unreadable against ES-4.
     *
     * WHERE THE MEAN IS NOT EXACTLY PRESERVED. `MAX_DELAY_MS` is applied AFTER jitter, so a
     * jittered delay can never exceed it. Unlike TO — which runs 4 retries, whose largest draw is
     * under 300 ms, so its cap never binds — ES runs 5, and the last attempt's window `[200, 600)`
     * IS clipped at 500:
     *
     *     E = (1/400) x INTEGRAL(200..500) x dx + (100/400) x 500 = 262.5 + 125 = 387.5 ms
     *
     * so that draw's mean is 387.5 rather than 400, and the whole budget 762.5 rather than 775 —
     * 1.6% low. Accepted deliberately: raising the cap to 600 would be a SECOND divergence from the
     * branches this one is read against, which is worse than a bias well inside run-to-run noise.
     * Raise `maxRetries` further and the clipping grows, so re-derive this before doing so.
     */
    internal fun delayMsFor(attempt: Int): Long {
        val base = baseDelayMsFor(attempt)
        val factor = (1.0 - JITTER_RATIO) + (2.0 * JITTER_RATIO * random.nextDouble())
        // At least 1 ms: a zero delay would hand the dispatch straight back to the command pool
        // with no wait at all, which is the convoy this exists to break.
        return (base * factor).roundToLong().coerceIn(1L, MAX_DELAY_MS)
    }

    private val retryCounter: Counter = Counter.builder("inventory.optimistic.retry").register(meterRegistry)
    private val exhaustedCounter: Counter = Counter.builder("inventory.optimistic.exhausted").register(meterRegistry)

    /**
     * The command pool refused the hand-off, i.e. it is shutting down — its queue is unbounded, so
     * this cannot fire under load. Counted because the alternative disposition is the worst one
     * available: a dropped dispatch task means the gateway callback never completes, the saga never
     * sees a failure, `SagaLifecycle.end()` is never reached and the order stays PENDING forever.
     */
    private val handoffRejectedCounter: Counter =
        Counter.builder("inventory.retry.handoff.rejected").register(meterRegistry)

    override fun scheduleRetry(
        commandMessage: CommandMessage<*>,
        lastFailure: RuntimeException,
        history: List<Array<Class<out Throwable>>>,
        commandDispatch: Runnable,
    ): Boolean {
        val isConcurrency = generateSequence(lastFailure as Throwable) { it.cause }
            .any { it is ConcurrencyException }
        if (!isConcurrency) return false
        if (history.size >= maxRetries) {
            log.warn("[RETRY] exhausted retries for {} after {} attempts", commandMessage.payloadType.simpleName, history.size)
            exhaustedCounter.increment()
            return false
        }
        val delay = delayMsFor(history.size)
        log.debug("[RETRY] ConcurrencyException on {} attempt={} retrying in {}ms", commandMessage.payloadType.simpleName, history.size + 1, delay)
        retryCounter.increment()
        // The hand-off is applied to the SCHEDULED task rather than to schedule() itself: the
        // backoff is served in the executor's DelayedWorkQueue, on no thread at all, so occupying a
        // command-pool thread for its duration would defeat the point of sharing the pool.
        retryExecutor.schedule({ dispatchOnCommandPool(commandDispatch) }, delay, TimeUnit.MILLISECONDS)
        return true
    }

    private fun dispatchOnCommandPool(commandDispatch: Runnable) {
        try {
            commandExecutor.execute {
                // Direct runtime evidence of the property this topology exists for. Logback's
                // pattern is `[%thread]` and the pools are named, so this prints `saga-command-N`
                // where the two-lane shape would print its retry-pool thread.
                if (log.isDebugEnabled) {
                    log.debug("[RETRY] executing on {}", Thread.currentThread().name)
                }
                commandDispatch.run()
            }
        } catch (e: RejectedExecutionException) {
            handoffRejectedCounter.increment()
            log.warn("[RETRY] command pool rejected the retry hand-off — dispatching on the retry thread instead", e)
            // Liveness over isolation. Running it here costs one retry-pool thread and one extra
            // Axon connection beyond the budget; losing the task strands an order permanently.
            commandDispatch.run()
        }
    }
}
