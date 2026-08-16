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
         * 30 OVER-SUBSCRIBES THE AXON JDBC POOL BY DESIGN — this was 23, which was the widest
         * value that still provably fit it, and it is now deliberately past that ceiling.
         * One busy thread can hold TWO `axon-jdbc-pool` connections AT THE SAME TIME:
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
         *     2 x (64 + RETRY_POOL_SIZE + 60 + 3) <= axon.jdbc.pool.size
         *     2 x (127 + 30)                       =  314   vs a pool of 300
         *
         * i.e. 14 connections short. Closing that is not a code-only change — docker-compose passes
         * AXON_JDBC_POOL_SIZE with a default of 300 that OVERRIDES application.yaml, so the branch
         * cannot fix it for itself. **Export `AXON_JDBC_POOL_SIZE=320` for any run at this width**,
         * and keep REPLICAS x (50 + AXON_JDBC_POOL_SIZE) <= PG_MAX_CONNECTIONS (default 600, so
         * REPLICAS=1 fits; raise it above that).
         *
         * Forgetting that export does not fail cleanly, which is why it is called out here rather
         * than left to the run: `axonDataSource` sets connectionTimeout = 5000, and
         * [ConcurrencyRetryScheduler] declines to retry anything without a ConcurrencyException in
         * its cause chain — a SQLTransientConnectionException is not one. A starved command
         * therefore stalls 5s and then fails TERMINALLY into the saga's abandon() path, whose own
         * compensating commands need the same exhausted pool. It shows up as latency and a
         * rejection rate, not as an obvious error. Watch
         * `hikaricp_connections_timeout_total{pool="axon-jdbc-pool"}`.
         */
        private const val RETRY_POOL_SIZE = 30

        /**
         * The first-attempt width, unchanged. Named rather than inlined so the connection-budget
         * arithmetic above cannot silently drift away from the bean below.
         */
        private const val COMMAND_POOL_SIZE = 64
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
