package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.gateway.DefaultCommandGateway
import org.axonframework.commandhandling.gateway.RetryScheduler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
         * are in flight. ES-4-NullLock-A is the variant that closes this properly, by cutting Tomcat
         * to 12 and deriving the pool from the sum; here it is a known, deliberate gap.
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

    @Bean
    fun retryScheduler(meterRegistry: MeterRegistry): RetryScheduler = ConcurrencyRetryScheduler(
        retryExecutor = Executors.newScheduledThreadPool(RETRY_POOL_SIZE),
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

    @Bean
    @Qualifier("sagaCommandExecutor")
    fun sagaCommandExecutor(): Executor = Executors.newFixedThreadPool(COMMAND_POOL_SIZE)
}
