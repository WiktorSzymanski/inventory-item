package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.gateway.DefaultCommandGateway
import org.axonframework.commandhandling.gateway.RetryScheduler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Configuration
class CommandGatewayConfig {

    companion object {
        /**
         * THIS POOL IS A TIMER, NOT AN EXECUTION LANE. Ported from ES-4 on 2026-08-20; it arrived
         * there as `ES-4-NullLock-oneExec`.
         *
         * It used to be a second EXECUTION lane, and that was never a design choice — it was a
         * consequence nobody opted into. Axon's `RetryingCallback.RetryDispatch.run()` calls
         * `commandBus.dispatch()` INLINE, and the autoconfigured `SimpleCommandBus` handles on the
         * calling thread — so a retried command's aggregate load, `reserveDelayMs` sleep and event
         * append all execute on whichever thread runs the scheduled task. At the old width of 4
         * that made the retry path a 16x narrower waist than the first-attempt path, worst on a
         * lock-free branch where nothing serialises writers in the JVM and the event store's
         * UNIQUE (aggregate_identifier, sequence_number) plus this retry is what resolves
         * contention — i.e. most contended work landed here.
         *
         * [ConcurrencyRetryScheduler] now hands that task to [sagaCommandExecutor] instead, so
         * first attempts and retries share one pool and this one only serves out the backoff.
         *
         * The width is therefore 1. A thread here does nothing but call `execute(...)` on the
         * command pool -- microseconds, non-blocking against an unbounded queue -- and the backoff
         * itself is served in the executor's DelayedWorkQueue on no thread at all, so a single
         * timer thread serves any retry rate this harness can produce. It was held at 30 through
         * the port so that change would be TOPOLOGY ALONE against the two-lane runs it is read
         * against; that comparison has been made, and 29 threads kept only to resemble a shape
         * this branch no longer has are 29 threads that invite the old reading of this pool.
         *
         * The one path that would occupy this thread with real work is the
         * RejectedExecutionException fallback in [ConcurrencyRetryScheduler], which runs the
         * dispatch inline rather than dropping it. [sagaCommandExecutor] is built on an unbounded
         * LinkedBlockingQueue, so it can only reject while shutting down -- but note that at width
         * 1 that fallback now stalls every other retry's backoff, not one lane of thirty. It is
         * counted: `inventory_retry_handoff_rejected`.
         *
         * THE THREAD WIDTHS ARE UNCHANGED FROM ES-2, which is what makes the two branches
         * comparable: they admit and execute at the same width, and differ in the store.
         *
         * THE CONNECTION ARITHMETIC IS NOT, and cannot be. On ES-2 one busy thread holds TWO
         * `axon-jdbc-pool` connections at once — its command's Spring transaction, plus one more
         * per event-store read/append, because the storage engine is wired with a plain
         * DataSourceConnectionProvider that never joins that transaction. Neither half of that
         * premise survives the move:
         *   1. there is ONE pool here, not two. A second pool on ES-2 protects the Spring Data
         *      repositories from Axon; the MongoDB driver checks a connection out per OPERATION
         *      and returns it, so there is no pinning for a second pool to protect against;
         *   2. the Axon stores go through [SessionAwareMongoTemplate], so an append DOES join the
         *      transaction its unit of work opened, rather than taking a connection beside it.
         *
         * So the multiplier is 1, and the pool is the SUM of ES-2's two (400, not 350) so the
         * database-side resource the two branches are given is the same number:
         *
         *     ES-2      2 x (112 command + 0 retry + 60 saga + 3 projections) = 350, pool 350
         *     here      1 x (112 command + 0 retry + 60 saga + 3 projections) = 175, pool 400
         *
         * 60 is ceil(total-segments / replicas) at REPLICAS=1; the 3 are inventory-projection,
         * order-projection and mock-kafka-publisher (reserve-metrics is SUBSCRIBING and runs on the
         * appending thread, already counted). The 400 comes from docker-compose's
         * `AXON_MONGO_POOL_SIZE` default, which it splices into the connection URI — Spring reads
         * maxPoolSize from the URI, so `axon.mongo.pool.size` in application.yaml informs the
         * startup warning but does not size anything.
         *
         * **TOMCAT IS STILL NOT IN THAT SUM, AND IS STILL A REAL DEMANDER.** `InventoryService`
         * dispatches the accept command with `sendAndWait` on the Tomcat thread and
         * `SimpleCommandBus` handles it THERE, so every in-flight POST demands a connection like
         * any other command thread. `server.tomcat.threads.max` is 99, so true peak demand is
         * 99 + 175 = 274 against a pool of 400 — which, unlike ES-2's 548 against 350, actually
         * fits. That headroom is a consequence of the driver's model, not a resourcing decision,
         * and it is one of the things an ES-2 vs ES-2-mongo comparison is measuring.
         *
         * Starvation still does not fail cleanly, which is why the budget is written down rather
         * than left to the run: the driver blocks on `waitQueueTimeoutMS` rather than failing
         * fast, and [ConcurrencyRetryScheduler] declines to retry anything without a
         * ConcurrencyException in its cause chain — a MongoTimeoutException is not one. A starved
         * command therefore stalls and then fails TERMINALLY into the saga's abandon() path, whose
         * own compensating commands need the same exhausted pool. It shows up as latency and a
         * rejection rate, not as an obvious error. `hikaricp_connections_timeout_total` has no
         * counterpart; watch `mongodb_driver_pool_checkedout` against
         * `mongodb_driver_pool_size` on every run here.
         */
        private const val RETRY_POOL_SIZE = 1

        /**
         * The ONLY execution width on this branch: first attempts, retries and the saga's terminal
         * dispositions all run here. 112 = the 82 the two-lane shape used + the 30 the retry lane
         * no longer executes on, which is what keeps the connection budget above identical to it.
         *
         * `internal` rather than `private` so [RetryDispatchTargetTest] can assert that arithmetic
         * without standing up a context.
         */
        internal const val COMMAND_POOL_SIZE = 112

        /**
         * ceil(total-segments / replicas) at REPLICAS=1 **for the DEFAULT configuration**;
         * see application.yaml.
         *
         * NOT read at runtime and NOT wired to [SagaProcessorProperties.totalSegments], which is
         * what actually sizes the processor and is overridable per run via
         * `AXON_SAGA_TOTAL_SEGMENTS`. This constant exists so the budget assertion below can be
         * made without a Spring context, and it therefore describes the default run only.
         * AxonCustomizerConfig logs the RESOLVED budget at startup and warns when the configured
         * segment count pushes peak demand past the pool -- that is the check that covers a run
         * with the override set.
         */
        internal const val SAGA_SEGMENT_THREADS = 60

        /** inventory-projection, order-projection, mock-kafka-publisher. reserve-metrics is
         *  SUBSCRIBING and runs on the appending thread, so it is already counted. */
        internal const val SINGLE_THREADED_PROJECTIONS = 3

        /**
         * One, not ES-2's two. See the [RETRY_POOL_SIZE] doc: the second connection there is the
         * event store taking its own beside the command's transaction, which the session-aware
         * Mongo template does not do.
         */
        internal const val CONNECTIONS_PER_BUSY_THREAD = 1
    }

    @Bean(destroyMethod = "shutdownNow")
    fun retryTimerExecutor(meterRegistry: MeterRegistry): ScheduledExecutorService {
        val pool = ScheduledThreadPoolExecutor(RETRY_POOL_SIZE, named("retry-timer"))
        // Both gauges mean something DIFFERENT here than on a two-lane branch, and that is the
        // point of publishing them:
        //   active — should sit at ~0. These threads only call execute(...) on the command pool.
        //            Anything sustained above 0 means work is running here that should not be,
        //            i.e. the hand-off is being bypassed (see inventory_retry_handoff_rejected).
        //   queued — retries currently serving out their backoff. A ScheduledThreadPoolExecutor
        //            holds delayed tasks in its DelayedWorkQueue, so this is the in-flight retry
        //            count, NOT threads waiting for a slot. On a two-lane branch the same series
        //            conflates the two.
        monitorPool(meterRegistry, "retry", "retry-timer", pool)
        return pool
    }

    /**
     * Takes [sagaCommandExecutor] as a parameter rather than building its own: the retried command
     * must land on the SAME pool the first attempt used. No cycle — the executor knows nothing
     * about the scheduler.
     */
    @Bean
    fun retryScheduler(
        meterRegistry: MeterRegistry,
        retryTimerExecutor: ScheduledExecutorService,
        @Qualifier("sagaCommandExecutor") sagaCommandExecutor: Executor,
    ): RetryScheduler = ConcurrencyRetryScheduler(
        retryExecutor = retryTimerExecutor,
        commandExecutor = sagaCommandExecutor,
        maxRetries = 5,        // UNCHANGED — retry POLICY is identical on every ES variant, so a
        initialDelayMs = 25L,  // difference between them cannot be blamed on the backoff curve.
        meterRegistry = meterRegistry,
    )

    @Bean
    fun commandGateway(commandBus: CommandBus, retryScheduler: RetryScheduler): DefaultCommandGateway =
        DefaultCommandGateway.builder()
            .commandBus(commandBus)
            .retryScheduler(retryScheduler)
            .build()

    @Bean(destroyMethod = "shutdown")
    @Qualifier("sagaCommandExecutor")
    fun sagaCommandExecutor(meterRegistry: MeterRegistry): Executor {
        // Equivalent to Executors.newFixedThreadPool(n), spelled out so the concrete
        // ThreadPoolExecutor stays visible to monitorPool below — Executors' wrapper hides
        // activeCount and the queue behind the ExecutorService interface.
        val pool = ThreadPoolExecutor(
            COMMAND_POOL_SIZE,
            COMMAND_POOL_SIZE,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            named("saga-command"),
        )
        monitorPool(meterRegistry, "command", "saga-command", pool)
        return pool
    }

    /**
     * Named threads so a `jcmd <pid> Thread.print` answers "where does a retry actually execute?"
     * by inspection, and so every log line carries it — logback's pattern is `[%thread]`. Both
     * pools used Executors' default factory before, which names everything `pool-N-thread-M` and
     * makes the two indistinguishable in a dump. Under this topology that question is the point.
     */
    private fun named(prefix: String): ThreadFactory {
        val counter = AtomicInteger(1)
        return ThreadFactory { runnable -> Thread(runnable, "$prefix-${counter.getAndIncrement()}") }
    }

    /**
     * Without these the pools publish nothing and "did merging the lanes help?" is unanswerable: a
     * run that got faster and a run that merely moved its backlog from one queue to the next look
     * identical in the existing metrics.
     *
     * Names match ES-4's and ES-4-NullLock-mod's exactly, so any panel built for either resolves
     * here too. Read `saga_pool_queued{pool="command"}`: it is where a retry waits BEHIND newer
     * first attempts, which is the cost this topology trades the wider lane for.
     */
    private fun monitorPool(
        registry: MeterRegistry,
        lane: String,
        executorName: String,
        pool: ThreadPoolExecutor,
    ) {
        // Micrometer's standard executor metrics, which is what puts this pool in the
        // "Executor pools — threads & queue" dashboard panel: it queries executor_active_threads /
        // executor_pool_size_threads / executor_queued_tasks by {{name}}. Those names come from
        // Boot's auto-instrumentation of ThreadPoolTaskExecutor beans, which these raw
        // ThreadPoolExecutors are not — so bind them by hand.
        //
        // The MeterBinder form is deliberate over ExecutorServiceMetrics.monitor(), which WRAPS the
        // executor to time every submission. Gauges only: no per-task overhead on a hot path.
        // executorName, not lane: the panel legend is {{name}}, so it should read the same
        // as the pool's threads in a dump rather than a bare "command"/"retry".
        ExecutorServiceMetrics(pool, executorName, emptyList()).bindTo(registry)
        // Kept alongside because ES-4, ES-4-NullLock-mod and -A publish these names, so one panel
        // spans every instrumented ES variant.
        Gauge.builder("saga.pool.active", pool) { it.activeCount.toDouble() }
            .tag("pool", lane)
            .description("Threads currently executing a task on this pool")
            .register(registry)
        Gauge.builder("saga.pool.queued", pool) { it.queue.size.toDouble() }
            .tag("pool", lane)
            .description("Tasks waiting on this pool's queue")
            .register(registry)
        Gauge.builder("saga.pool.size", pool) { it.poolSize.toDouble() }
            .tag("pool", lane)
            .description("Threads currently alive in this pool")
            .register(registry)
    }
}
