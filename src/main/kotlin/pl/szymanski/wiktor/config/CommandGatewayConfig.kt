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
         * 23 and not 64 because 23 is the widest value that still provably fits the Axon JDBC pool.
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
         *     2 x (127 + 23)                       =  300
         *
         * which is exactly the configured 300. Raising this further is not a code-only change: the
         * pool would have to grow with it, and docker-compose passes AXON_JDBC_POOL_SIZE with a
         * default of 300 that OVERRIDES application.yaml, so the branch cannot fix that for itself.
         * Keep REPLICAS x (50 + AXON_JDBC_POOL_SIZE) <= PG_MAX_CONNECTIONS as well.
         *
         * Overrunning the pool does not fail cleanly, which is why the ceiling is respected rather
         * than gambled on: `axonDataSource` sets connectionTimeout = 5000, and
         * [ConcurrencyRetryScheduler] declines to retry anything without a ConcurrencyException in
         * its cause chain — a SQLTransientConnectionException is not one. A starved command
         * therefore stalls 5s and then fails TERMINALLY into the saga's abandon() path, whose own
         * compensating commands need the same exhausted pool. It shows up as latency and a
         * rejection rate, not as an obvious error. Watch
         * `hikaricp_connections_timeout_total{pool="axon-jdbc-pool"}`.
         */
        private const val RETRY_POOL_SIZE = 23

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
