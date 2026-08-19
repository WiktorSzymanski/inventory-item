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
import java.util.concurrent.TimeUnit

/**
 * ES-4-NullLock-oneExec: the retry pool TIMES the backoff; it does not execute the retried command.
 *
 * On every other ES branch this class schedules Axon's `commandDispatch` Runnable directly onto
 * [retryExecutor], and that is where the whole retried command then runs — aggregate load,
 * `reserveDelayMs` sleep, append and commit. Not a quirk of this code: Axon's
 * `RetryingCallback.RetryDispatch.run()` calls `commandBus.dispatch(...)` INLINE, and the
 * autoconfigured `SimpleCommandBus` handles on the calling thread. The retry pool is therefore a
 * second EXECUTION lane on those branches, and its width is a hard cap on retried work — which on a
 * lock-free branch is where most contended work ends up.
 *
 * Here the scheduled task only hands the dispatch to [commandExecutor], so first attempts and
 * retries share one pool. Axon neither documents the inline-dispatch consequence nor forbids this:
 * `RetryScheduler`'s contract is "schedule this Runnable", and where it runs is the caller's
 * choice. This is the ES analogue of TO's `ORDER_RETRY_EXECUTE_ON_RETRY_POOL=false`.
 *
 * What that buys and costs is the experiment: retries get the full command-pool width instead of a
 * 30-thread lane, but they rejoin an unbounded FIFO at the TAIL, behind first attempts admitted
 * after them — so a retry's real wait becomes backoff + queue depth.
 */
class ConcurrencyRetryScheduler(
    private val retryExecutor: ScheduledExecutorService,
    private val commandExecutor: Executor,
    private val maxRetries: Int = 5,
    private val initialDelayMs: Long = 25L,
    meterRegistry: MeterRegistry,
) : RetryScheduler {

    companion object {
        private val log = LoggerFactory.getLogger(ConcurrencyRetryScheduler::class.java)
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
        val delay = (initialDelayMs * (1L shl history.size)).coerceAtMost(500L)
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
                // Direct runtime evidence of the property this branch exists for. Logback's
                // pattern is `[%thread]` and the pools are named, so this prints `saga-command-N`
                // here where any other ES branch would print its retry-pool thread.
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
