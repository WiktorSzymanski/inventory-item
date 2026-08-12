package pl.szymanski.wiktor.config

import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.common.jdbc.DataSourceConnectionProvider
import org.axonframework.common.lock.NullLockFactory
import org.axonframework.common.transaction.TransactionManager
import org.axonframework.eventhandling.tokenstore.jdbc.JdbcTokenStore
import org.axonframework.eventhandling.tokenstore.jdbc.TokenSchema
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.GenericAggregateFactory
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventsourcing.eventstore.jdbc.EventSchema
import org.axonframework.eventsourcing.eventstore.jdbc.JdbcEventStorageEngine
import org.axonframework.eventsourcing.eventstore.jpa.SQLStateResolver
import org.axonframework.modelling.saga.repository.jdbc.JdbcSagaStore
import org.axonframework.modelling.saga.repository.jdbc.PostgresSagaSqlSchema
import org.axonframework.modelling.saga.repository.jdbc.SagaSchema
import org.axonframework.serialization.Serializer
import org.axonframework.spring.messaging.unitofwork.SpringTransactionManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import pl.szymanski.wiktor.domain.InventoryItem
import javax.sql.DataSource

@Configuration
class AxonConfig {

    private val log = LoggerFactory.getLogger(AxonConfig::class.java)

    @Bean
    fun axonObjectMapper(): com.fasterxml.jackson.databind.ObjectMapper =
        com.fasterxml.jackson.databind.ObjectMapper().apply {
            registerModule(kotlinModule())
            findAndRegisterModules()
        }

    // Dedicated pool for Axon so it never contends with the app's JDBC pool
    // (Spring Data JDBC repos + NamedParameterJdbcTemplate share the primary DataSource).
    @Bean(name = ["axonDataSource"])
    fun axonDataSource(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
        @Value("\${axon.jdbc.pool.size:50}") poolSize: Int,
    ): DataSource = HikariDataSource().apply {
        jdbcUrl = url
        this.username = username
        this.password = password
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = poolSize
        connectionTimeout = 5_000
        poolName = "axon-jdbc-pool"
    }

    @Bean
    fun axonTransactionManager(@Qualifier("axonDataSource") axonDataSource: DataSource): TransactionManager =
        SpringTransactionManager(DataSourceTransactionManager(axonDataSource))

    // Explicit primary transaction manager — Spring Boot's auto-config backs off when any
    // PlatformTransactionManager bean is present, so we must register this ourselves.
    @Bean("transactionManager")
    @Primary
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)

    // Spring PlatformTransactionManager backed by axonDataSource — used by projection updaters
    // so their @Transactional writes draw from the Axon pool instead of the primary Spring pool.
    @Bean("axonSpringTransactionManager")
    fun axonSpringTransactionManager(@Qualifier("axonDataSource") axonDataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(axonDataSource)

    @Bean("axonJdbcTemplate")
    fun axonJdbcTemplate(@Qualifier("axonDataSource") axonDataSource: DataSource): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(axonDataSource)

    @Bean
    fun eventSchema(): EventSchema = EventSchema.builder()
        .eventTable("domain_event_entry")
        .snapshotTable("snapshot_event_entry")
        .globalIndexColumn("global_index")
        .timestampColumn("time_stamp")
        .eventIdentifierColumn("event_identifier")
        .aggregateIdentifierColumn("aggregate_identifier")
        .sequenceNumberColumn("sequence_number")
        .typeColumn("type")
        .payloadTypeColumn("payload_type")
        .payloadRevisionColumn("payload_revision")
        .payloadColumn("payload")
        .metaDataColumn("meta_data")
        .build()

    @Bean
    fun tokenStore(
        @Qualifier("axonDataSource") axonDataSource: DataSource,
        serializer: Serializer,
    ): JdbcTokenStore = JdbcTokenStore.builder()
        .connectionProvider(DataSourceConnectionProvider(axonDataSource))
        .serializer(serializer)
        .schema(
            TokenSchema.builder()
                .setTokenTable("token_entry")
                .setProcessorNameColumn("processor_name")
                .setSegmentColumn("segment")
                .setTokenColumn("token")
                .setTokenTypeColumn("token_type")
                .setTimestampColumn("timestamp")
                .setOwnerColumn("owner")
                .build()
        )
        .build()

    @Bean
    fun eventStorageEngine(
        @Qualifier("axonDataSource") axonDataSource: DataSource,
        axonTransactionManager: TransactionManager,
        eventSchema: EventSchema,
        @Qualifier("eventSerializer") eventSerializer: Serializer,
        meterRegistry: MeterRegistry,
        // Gap-handling tuning. Overridable so the benchmark can A/B the default
        // (maxGapOffset=10000, gapTimeout=60000) against the tightened values below.
        @Value("\${axon.eventstore.max-gap-offset:500}") maxGapOffset: Int,
        @Value("\${axon.eventstore.gap-timeout-ms:5000}") gapTimeoutMs: Int,
        @Value("\${axon.eventstore.gap-cleaning-threshold:250}") gapCleaningThreshold: Int,
    ): EventStorageEngine {
        val jdbc = JdbcEventStorageEngine.builder()
            .connectionProvider(DataSourceConnectionProvider(axonDataSource))
            .transactionManager(axonTransactionManager)
            .schema(eventSchema)
            .eventSerializer(eventSerializer)
            .snapshotSerializer(eventSerializer)
            // With NullLockFactory on the InventoryItem repository the UNIQUE
            // (aggregate_identifier, sequence_number) constraint is the ONLY conflict detector — not a
            // multi-node backstop but the primary mechanism on every node. Translate Postgres 23xxx
            // (unique_violation 23505) into Axon's ConcurrencyException so ConcurrencyRetryScheduler
            // fires instead of leaking a raw store exception. Without this the engine falls back to
            // JdbcSQLErrorCodesResolver, which is blind to pgjdbc and would make every conflict
            // terminal.
            .persistenceExceptionResolver(SQLStateResolver())
            // A rolled-back append burns a non-transactional BIGSERIAL global_index, leaving a
            // PERMANENT gap; with defaults (maxGapOffset=10000, gapTimeout=60s) the
            // GapAwareTrackingToken carries ~10k gaps (~41 kB) rewritten every batch, bloating the
            // token_entry TOAST. Lock-free, such rollbacks are routine rather than rare, so this is
            // load-bearing rather than insurance: record gaps only within maxGapOffset of the head,
            // and let gapCleaningThreshold + the short gapTimeout purge them from the token
            // continuously (on the fly). Values copied from ES-3/ES-4; the ES-1 baseline runs Axon's
            // defaults, so this is the SECOND thing this branch changes. See application.yaml for why
            // maxGapOffset is also a correctness knob.
            .maxGapOffset(maxGapOffset)
            .gapTimeout(gapTimeoutMs)
            .gapCleaningThreshold(gapCleaningThreshold)
            .build()
        return TimedEventStorageEngine(jdbc, meterRegistry)
    }

    // ES-1-NullLock: uncached event-sourcing repository for the hot InventoryItem aggregate, built
    // LOCK-FREE with NullLockFactory. Nothing serialises writers to one InventoryItem, in this JVM or
    // across nodes; conflicts are detected by the event store's unique constraint and resolved by
    // retry.
    //
    // ES-1 declares no repository bean at all and lets Axon's implicit @Aggregate registration build
    // an EventSourcingRepository on LockingRepository's pessimistic default — there is no lockFactory
    // line to flip, so the repository has to be introduced here AND named on the annotation.
    // @Aggregate(repository = "inventoryItemRepository") is what makes this bean take effect: a bean
    // the annotation does not name is silently ignored and the aggregate keeps its lock. There must
    // also be no SECOND registration for this type — a `configurer.configureAggregate(...)` builds a
    // stock, pessimistically-locked repository whose command handler wins the command-bus
    // subscription, and Configuration.repository() keeps returning this bean while nothing uses it.
    // ES-2 had exactly that; see AxonCustomizerConfig on ES-2-NullLock.
    //
    // No snapshotTriggerDefinition, deliberately: ES-1 has no such bean and its identity is that every
    // command replays its stream from event 0. OrderAggregate keeps Axon's default (pessimistic,
    // uncached) repository.
    @Bean
    fun inventoryItemRepository(eventStore: EventStore): EventSourcingRepository<InventoryItem> {
        log.info("InventoryItem -> EventSourcingRepository (NullLockFactory, uncached, no snapshots)")
        return EventSourcingRepository.builder(InventoryItem::class.java)
            .eventStore(eventStore)
            .aggregateFactory(GenericAggregateFactory(InventoryItem::class.java))
            // Overrides the LockingRepository.Builder default (PessimisticLockFactory): concurrent
            // commands on one aggregate all load at sequence N and all try to append N+1. Exactly one
            // wins; the losers get a ConcurrencyException (SQLStateResolver above) and are retried by
            // ConcurrencyRetryScheduler, which reloads the aggregate from the store and so sees the
            // winner's event.
            .lockFactory(NullLockFactory.INSTANCE)
            .build()
    }

    @Bean
    fun sagaStore(
        @Qualifier("axonDataSource") axonDataSource: DataSource,
        serializer: Serializer,
    ): JdbcSagaStore = JdbcSagaStore.builder()
        .connectionProvider(DataSourceConnectionProvider(axonDataSource))
        .serializer(serializer)
        .sqlSchema(PostgresSagaSqlSchema(SagaSchema.builder()
            .sagaEntryTable("saga_entry")
            .associationValueEntryTable("association_value_entry")
            .sagaIdColumn("saga_id")
            .revisionColumn("revision")
            .serializedSagaColumn("serialized_saga")
            .sagaTypeColumn("saga_type")
            .associationKeyColumn("association_key")
            .associationValueColumn("association_value")
            .build()))
        .build()

}
