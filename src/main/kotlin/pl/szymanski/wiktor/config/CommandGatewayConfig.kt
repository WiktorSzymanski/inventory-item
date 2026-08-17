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
         * ES-4-NullLock-oneExec: THIS POOL IS NOW A TIMER, and that is the whole branch.
         *
         * Everywhere else in the ES family it is a second EXECUTION lane. Axon's
         * `RetryingCallback.RetryDispatch.run()` calls `commandBus.dispatch()` INLINE, and the
         * autoconfigured `SimpleCommandBus` handles on the calling thread — so a retried command's
         * aggregate load, `reserveDelayMs` sleep and event append all execute on whichever thread
         * runs the scheduled task. [ConcurrencyRetryScheduler] here hands that task to
         * [sagaCommandExecutor] instead, so first attempts and retries share one pool and this one
         * only serves out the backoff.
         *
         * The width stays 30 so the branch differs from its parent in TOPOLOGY ALONE. These threads
         * now do nothing but call `execute(...)`, and the backoff itself is served in the
         * executor's DelayedWorkQueue on no thread at all, so the cost of keeping them is nil.
         *
         * THE CONNECTION BUDGET IS UNCHANGED, which is what makes the pair readable. One busy
         * thread can hold TWO `axon-jdbc-pool` connections at the same time:
         *   1. its command's Spring transaction — [AxonConfig] builds the only TransactionManager
         *      as SpringTransactionManager over `axonDataSource`;
         *   2. one more per event-store read/append, because the storage engine is wired with a
         *      plain DataSourceConnectionProvider, NOT wrapped in
         *      UnitOfWorkAwareConnectionProviderWrapper, so it calls getConnection() itself and
         *      never joins the transaction from (1).
         * A thread that only submits opens no transaction and takes no connection, so the retry
         * lane drops out of the sum and the command lane absorbs its 30 threads:
         *
         *     parent   2 x ( 82 command + 30 retry + 60 saga + 3 projections) = 350
         *     here     2 x (112 command +  0 retry + 60 saga + 3 projections) = 350
         *
         * Same 350 that docker-compose's AXON_JDBC_POOL_SIZE default passes, same 175 executing
         * threads, same retry policy. A run that exports a lower pool size silently breaks that.
         *
         * **TOMCAT IS NOT IN THAT SUM, AND IT IS A REAL DEMANDER.** Inherited from the parent, not
         * introduced here: `InventoryService` dispatches the accept command with `sendAndWait` on
         * the Tomcat thread and `SimpleCommandBus` handles it THERE, so every in-flight POST holds
         * the same two connections as any other command thread. `server.tomcat.threads.max` is 99,
         * so true peak demand is 2 x (99 + 175) = 548 against a pool of 350; it holds only because
         * offered load, not the thread cap, bounds how many POSTs are in flight.
         *
         * Starvation does not fail cleanly, which is why the budget is written down rather than
         * left to the run: `axonDataSource` sets connectionTimeout = 5000, and
         * [ConcurrencyRetryScheduler] declines to retry anything without a ConcurrencyException in
         * its cause chain — a SQLTransientConnectionException is not one. A starved command
         * therefore stalls 5s and then fails TERMINALLY into the saga's abandon() path, whose own
         * compensating commands need the same exhausted pool. It shows up as latency and a
         * rejection rate, not as an obvious error. Watch
         * `hikaricp_connections_timeout_total{pool="axon-jdbc-pool"}` on every run here.
         */
        private const val RETRY_POOL_SIZE = 30

        /**
         * The ONLY execution width on this branch: first attempts, retries and the saga's terminal
         * dispositions all run here. 112 = the parent's 82 + the 30 the retry lane no longer
         * executes on, which is what keeps the connection budget above identical to the parent's.
         *
         * `internal` rather than `private` so [PoolBudgetTest] can assert that arithmetic without
         * standing up a context.
         */
        internal const val COMMAND_POOL_SIZE = 112

        /** ceil(total-segments / replicas) at REPLICAS=1; see application.yaml. */
        internal const val SAGA_SEGMENT_THREADS = 60

        /** inventory-projection, order-projection, mock-kafka-publisher. reserve-metrics is
         *  SUBSCRIBING and runs on the appending thread, so it is already counted. */
        internal const val SINGLE_THREADED_PROJECTIONS = 3

        /** See the [RETRY_POOL_SIZE] doc: transaction + event-store connection. */
        internal const val CONNECTIONS_PER_BUSY_THREAD = 2
    }

    /**
     * Takes [sagaCommandExecutor] as a parameter rather than building its own: the retried command
     * must land on the SAME pool the first attempt used. No cycle — the executor knows nothing
     * about the scheduler.
     */
    @Bean(destroyMethod = "shutdownNow")
    fun retryTimerExecutor(meterRegistry: MeterRegistry): ScheduledExecutorService {
        val pool = ScheduledThreadPoolExecutor(RETRY_POOL_SIZE, named("retry-timer"))
        // Both gauges mean something DIFFERENT here than on any other ES branch, and that is the
        // point of publishing them:
        //   active — should sit at ~0. These threads only call execute(...) on the command pool.
        //            Anything sustained above 0 means work is running here that should not be,
        //            i.e. the hand-off is being bypassed (see inventory_retry_handoff_rejected).
        //   queued — retries currently serving out their backoff. A ScheduledThreadPoolExecutor
        //            holds delayed tasks in its DelayedWorkQueue, so this is the in-flight retry
        //            count, NOT threads waiting for a slot. On the parent branch the same series
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
     * makes the two indistinguishable in a dump. On this branch that question is the whole point.
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
     * Names match ES-4-NullLock-mod's exactly, so any panel built for that branch resolves here
     * too. Read `saga_pool_queued{pool="command"}`: on this branch it is where a retry waits
     * BEHIND newer first attempts, which is the cost the branch trades the wider lane for.
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
        // Kept alongside because ES-4-NullLock-mod and -A publish these names, so one panel spans
        // every instrumented ES variant.
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
