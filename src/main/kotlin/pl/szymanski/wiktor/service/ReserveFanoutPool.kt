package pl.szymanski.wiktor.service

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pool the reserve path's MODIFY phase fans out across. TO-3-parallel's whole difference from
 * TO-3 lives behind this class.
 *
 * On TO-3 the modify phase folds an order's lines one at a time on the order-worker thread, so an
 * order costs `ITEMS_PER_ORDER x reserveDelayMs` of that thread — 100 ms at W-base/C01, 400 ms at
 * W-fan/C01. Here the lines are grouped by item and the groups run concurrently, so the same order
 * costs roughly ONE delay of wall time. Two things follow, and they are the reason the branch
 * exists:
 *
 *  - the order-worker thread is occupied for `max_group x delay` instead of `lines x delay`, which
 *    moves the pool ceiling from `workers / (lines x delay)` to `workers / delay`; and
 *  - the gap between the phase-1 read and the versioned batch UPDATE that checks those versions —
 *    i.e. the optimistic-lock CONFLICT WINDOW — shrinks by the same factor. That is the effect
 *    worth measuring, since it costs a full re-run of the phase plus a wasted write transaction
 *    every time it fires.
 *
 * **These threads take no database connection and hold no row lock.** The modify phase runs outside
 * the write transaction by construction (see [pl.szymanski.wiktor.service.command.ReserveOrderItemsCommandHandler]),
 * so a wide pool here does NOT move the Hikari budget the way a wider order-worker or Tomcat pool
 * would. It is the property that makes the width affordable at all, and the reason ES cannot copy
 * the trick: on the ES branches the same sleep is paid inside the aggregate with its lock held and
 * two connections charged per busy command thread.
 *
 * **THE BEAN TYPE IS LOAD-BEARING, and getting it wrong fails silently.** This class deliberately
 * implements NONE of `Executor`, `TaskExecutor` or `ScheduledExecutorService`, for the same reason
 * `orderRetryScheduler` is a bean of its own custom type: it must trip no Boot conditional and add
 * no candidate to any by-type lookup.
 *
 *  - `@Async` picks its default executor by TYPE. Measured, this context already resolves
 *    `TaskExecutor` to TWO beans — `orderWorkerExecutor` and Boot's `taskScheduler`, which is an
 *    `AsyncTaskExecutor` and therefore a `TaskExecutor` as well — so the lookup is already
 *    ambiguous, there is no bean named `taskExecutor` to break the tie, and
 *    `InventoryService.onOrderCreated` consequently runs on a `SimpleAsyncTaskExecutor` rather than
 *    on the order pool. That is TO-3's behaviour and this branch must not alter it. Adding a third
 *    executor-typed bean here is exactly the kind of edit that would, and nothing would say so.
 *  - A `ScheduledExecutorService` bean would additionally back off Boot's `taskScheduler` outright
 *    and migrate the outbox republisher and OutboxMetrics onto these threads.
 *
 * Because the invariant is "resolution is unchanged" rather than any particular resolution,
 * `ReserveFanoutIsNotAnExecutorTest` asserts it DIFFERENTIALLY: it stands the same context up with
 * and without this pool and requires the by-type lookups to be identical. Note that
 * `OrderWorkerPoolAutoConfigurationTest`'s existing `applicationTaskExecutor` assertion could not
 * catch a regression here — that bean is `@ConditionalOnMissingBean(Executor.class)` and stays
 * backed off whether the context holds two executors or three.
 *
 * Saturation degrades rather than queues. The queue is BOUNDED and a rejected group runs inline on
 * the submitting order-worker thread — which is exactly TO-3's sequential behaviour, the right
 * thing to fall back to. An unbounded queue would instead absorb the overflow invisibly and the
 * parallelism would quietly stop happening at the load where it matters most. There is no deadlock
 * risk in running a group on the caller: fan-out tasks never submit back to this pool.
 */
class ReserveFanoutPool(threads: Int, queueCapacity: Int) : AutoCloseable {

    private val threadNumber = AtomicInteger(1)

    /**
     * Exposed so `ReserveFanoutConfig` can bind `executor_*` to it, the same hand-rolled bind
     * [OrderWorkerPool] needs and for the same reason: Boot instruments only `ThreadPoolTaskExecutor`
     * beans and this is not one.
     *
     * Unlike the order pool this is a plain `ThreadPoolExecutor`, so `executor_pool_max_threads`
     * reports the real width here — the `Integer.MAX_VALUE` reading that makes that series useless
     * on a `ScheduledThreadPoolExecutor` does not apply.
     */
    internal val executor = ThreadPoolExecutor(
        threads,
        threads,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        { runnable ->
            // Daemon for the same reason the order pool's threads are: a reserve in flight must
            // never hold the JVM open at shutdown. Shutdown semantics only.
            Thread(runnable, "reserve-fanout-${threadNumber.getAndIncrement()}").apply { isDaemon = true }
        },
        // NOT the stock CallerRunsPolicy, which is `if (!executor.isShutdown()) runnable.run()`
        // and therefore DISCARDS a task rejected during shutdown. [invokeAll] waits on every
        // future it created, so a discarded task is one that never completes and a join that never
        // returns — a shutdown hang, reachable only in the narrow window where the queue is full
        // as the context closes, which is exactly the kind of thing that would be diagnosed once
        // and never reproduced. Running it unconditionally costs nothing: the task is arithmetic
        // and a sleep, it holds no connection, and its result is discarded by the caller anyway if
        // the write phase then fails against a closing datasource.
        { runnable, _ -> runnable.run() },
    )

    /**
     * Runs every task and returns only once all of them have finished — success or failure.
     *
     * No early cancellation on the first failure: a line group that has already started is left to
     * finish, because the phase's result is discarded wholesale by the caller anyway and cancelling
     * mid-`reserve` would buy nothing but a partially applied working copy. The caller inspects the
     * futures and rethrows.
     */
    fun <T> invokeAll(tasks: List<Callable<T>>): List<Future<T>> = executor.invokeAll(tasks)

    override fun close() {
        executor.shutdownNow()
    }
}
