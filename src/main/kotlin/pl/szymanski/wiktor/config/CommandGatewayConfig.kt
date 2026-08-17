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
         * The retry pool is NOT a timer, which is why four threads was never a harmless default.
         * Axon's `RetryingCallback.RetryDispatch.run()` calls `commandBus.dispatch()` INLINE, and
         * the autoconfigured `SimpleCommandBus` handles on the calling thread — so a retried
         * command's aggregate load, `reserveDelayMs` sleep and event append all execute HERE, not
         * back on [sagaCommandExecutor]. At 4 that made the retry path a 16x narrower waist than
         * the first-attempt path, worst on the `*-NullLock` variants where nothing serialises
         * writers in the JVM and the event store's UNIQUE (aggregate_identifier, sequence_number)
         * plus this retry is what resolves contention — i.e. most contended work lands here.
         *
         * 30 was reached from 4 via 23. One busy thread can hold TWO `axon-jdbc-pool` connections
         * AT THE SAME TIME:
         *   1. its command's Spring transaction — [AxonConfig] builds the only TransactionManager
         *      as SpringTransactionManager over `axonDataSource`;
         *   2. one more per event-store read/append, because the storage engine is wired with a
         *      plain DataSourceConnectionProvider, NOT wrapped in
         *      UnitOfWorkAwareConnectionProviderWrapper, so it calls getConnection() itself and
         *      never joins the transaction from (1).
         * With [COMMAND_POOL_SIZE] first-attempt threads, ceil(total-segments / replicas) = 60 saga
         * segment threads at REPLICAS=1, and 3 single-threaded projections (inventory-projection,
         * order-projection, mock-kafka-publisher — reserve-metrics is SUBSCRIBING and runs on the
         * appending thread, already counted):
         *
         *     2 x (COMMAND_POOL_SIZE + RETRY_POOL_SIZE + 60 + 3) <= axon.jdbc.pool.size
         *     2 x (82 + 30 + 60 + 3)                              =  350   vs a pool of 350
         *
         * i.e. the pool is consumed EXACTLY, which is why [COMMAND_POOL_SIZE] is 82 and not the 64
         * it was: 82 is what the leftover buys, not a width chosen on its own merits. The 350 comes
         * from docker-compose's `AXON_JDBC_POOL_SIZE` default, which OVERRIDES the 300 in
         * application.yaml — the branch cannot set this for itself, so a run that exports a lower
         * value silently reopens the shortfall. Keep
         * REPLICAS x (50 + AXON_JDBC_POOL_SIZE) <= PG_MAX_CONNECTIONS (default 600, so REPLICAS=1
         * fits; raise it above that).
         *
         * **TOMCAT IS NOT IN THAT SUM, AND IT IS A REAL DEMANDER.**
         * `InventoryService.createOrderReservation` calls `sendAndWait` on the Tomcat thread and
         * the autoconfigured `SimpleCommandBus` handles it THERE, so every in-flight POST holds the
         * same two connections as any other command thread. `server.tomcat.threads.max` is 99 (it
         * was Boot's default 200), so the true peak demand is 2 x (99 + 175) = 548 against a pool
         * of 350 — the accept path is running on the difference between peak and actual concurrency.
         * The pool is only adequate because offered load, not the thread cap, bounds how many POSTs
         * are in flight. Closing it means capping Tomcat low and deriving the pool from the full
         * sum — NO BRANCH ACTUALLY DOES THIS; the ES-4-NullLock-A notes in variants.env claim it,
         * but that branch has no `server.tomcat` block in code. Here it is a known, deliberate gap.
         *
         * Starvation does not fail cleanly, which is why the budget is written down rather than left
         * to the run: `axonDataSource` sets connectionTimeout = 5000, and [ConcurrencyRetryScheduler]
         * declines to retry anything without a ConcurrencyException in its cause chain — a
         * SQLTransientConnectionException is not one. A starved command therefore stalls 5s and then
         * fails TERMINALLY into the saga's abandon() path, whose own compensating commands need the
         * same exhausted pool. It shows up as latency and a rejection rate, not as an obvious error.
         * Watch `hikaricp_connections_timeout_total{pool="axon-jdbc-pool"}` on every run here.
         */
        private const val RETRY_POOL_SIZE = 30

        /**
         * The first-attempt width. 82, up from 64: it is the residue of the connection budget above
         * once the retry, saga and projection lanes are paid for, so it moves whenever any of those
         * or the pool size does. Named rather than inlined so that arithmetic cannot silently drift
         * away from the bean below.
         */
        private const val COMMAND_POOL_SIZE = 82
    }

    /**
     * The pool is built here rather than inline so it stays a concrete [ScheduledThreadPoolExecutor]
     * and [monitorPool] can read it — `Executors.newScheduledThreadPool` hides `activeCount` and the
     * queue behind the interface.
     */
    @Bean(destroyMethod = "shutdownNow")
    fun retryCommandExecutor(meterRegistry: MeterRegistry): ScheduledExecutorService {
        // Named `retry-command`, not `retry-timer`: on this branch these threads RUN the retried
        // command in full (see the RETRY_POOL_SIZE doc). ES-4-NullLock-oneExec names its
        // equivalent `retry-timer` because there they only hand the task to the command pool, so
        // the thread name alone tells you which topology a dump came from.
        val pool = ScheduledThreadPoolExecutor(RETRY_POOL_SIZE, named("retry-command"))
        // `active` here is threads executing a retried command — a real second execution lane, and
        // the number to read when asking whether 30 was enough. `queued` conflates two things on
        // this branch: retries still serving out their backoff (held in the DelayedWorkQueue) and
        // retries waiting for a thread. Prefer `active` for saturation; a sustained value at
        // RETRY_POOL_SIZE means the run measured retry width rather than the persistence model.
        monitorPool(meterRegistry, "retry", "retry-command", pool)
        return pool
    }

    @Bean
    fun retryScheduler(
        meterRegistry: MeterRegistry,
        retryCommandExecutor: ScheduledExecutorService,
    ): RetryScheduler = ConcurrencyRetryScheduler(
        retryExecutor = retryCommandExecutor,
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
        // ThreadPoolExecutor stays visible to monitorPool below.
        val pool = ThreadPoolExecutor(
            COMMAND_POOL_SIZE,
            COMMAND_POOL_SIZE,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            named("saga-command"),
        )
        // FIRST ATTEMPTS ONLY on this branch. A command that has conflicted once never comes back
        // here — it executes on retryCommandExecutor — so `queued` is new work waiting, never
        // retried work.
        monitorPool(meterRegistry, "command", "saga-command", pool)
        return pool
    }

    /**
     * Named threads so a `jcmd <pid> Thread.print` answers "where did this retry execute?" by
     * inspection, and so logback's `[%thread]` pattern carries it into every line. Both pools used
     * Executors' default factory before, which names everything `pool-N-thread-M` and makes the two
     * indistinguishable in a dump.
     */
    private fun named(prefix: String): ThreadFactory {
        val counter = AtomicInteger(1)
        return ThreadFactory { runnable -> Thread(runnable, "$prefix-${counter.getAndIncrement()}") }
    }

    /**
     * Without these the pools publish nothing — they are raw Executors rather than
     * ThreadPoolTaskExecutor beans, so Boot's executor auto-instrumentation does not bind them
     * either, and this branch had no pool visibility at all.
     *
     * Names match ES-4-NullLock-mod's and ES-4-NullLock-oneExec's exactly, so one panel covers all
     * of them and `--only ES-4-NullLock,ES-4-NullLock-oneExec` is instrumented on BOTH sides. That
     * pair asks whether merging the lanes helps or merely moves the backlog from one queue to the
     * next — unanswerable if only one half reports its queues.
     *
     * Note `size` is threads ALIVE, not the configured width: a fixed pool fills lazily, so the
     * series ramps under load and a mid-run value below the width means only that many threads were
     * ever needed at once.
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
