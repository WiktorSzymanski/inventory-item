package pl.szymanski.wiktor.config

import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.common.jdbc.DataSourceConnectionProvider
import org.axonframework.common.lock.PessimisticLockFactory
import org.axonframework.common.transaction.TransactionManager
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.GenericAggregateFactory
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventhandling.tokenstore.jdbc.JdbcTokenStore
import org.axonframework.eventhandling.tokenstore.jdbc.TokenSchema
import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition
import org.axonframework.eventsourcing.NoSnapshotTriggerDefinition
import org.axonframework.eventsourcing.SnapshotTriggerDefinition
import org.axonframework.eventsourcing.Snapshotter
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
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
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import pl.szymanski.wiktor.domain.InventoryItem
import javax.sql.DataSource

@Configuration
@EnableConfigurationProperties(SnapshotProperties::class, CacheProperties::class)
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
            // The in-JVM lock cannot cover a second node writing the same aggregate, so the
            // UNIQUE (aggregate_identifier, sequence_number) constraint stays the last line of defence.
            // Translate Postgres 23xxx (unique_violation 23505) into Axon's ConcurrencyException so the
            // gateway RetryScheduler fires instead of leaking a raw store exception. Kept identical to
            // ES-3-optimistic so both branches run the same storage engine configuration.
            .persistenceExceptionResolver(SQLStateResolver())
            // Kept from ES-3-optimistic so the two variants share identical infrastructure. A rolled-back
            // append burns a non-transactional BIGSERIAL global_index, leaving a PERMANENT gap; with
            // defaults (maxGapOffset=10000, gapTimeout=60s) the GapAwareTrackingToken carries ~10k gaps
            // (~41 kB) rewritten every batch, bloating the token_entry TOAST. Under pessimistic locking
            // such rollbacks are rare, so this is cheap insurance rather than a necessity here: record
            // gaps only within maxGapOffset of the head, and let gapCleaningThreshold + the short
            // gapTimeout purge them from the token continuously (on the fly).
            .maxGapOffset(maxGapOffset)
            .gapTimeout(gapTimeoutMs)
            .gapCleaningThreshold(gapCleaningThreshold)
            .build()
        return TimedEventStorageEngine(jdbc, meterRegistry)
    }

    @Bean
    fun inventorySnapshotTrigger(
        snapshotter: Snapshotter,
        snapshotProperties: SnapshotProperties,
    ): SnapshotTriggerDefinition =
        if (snapshotProperties.enabled)
            EventCountSnapshotTriggerDefinition(snapshotter, snapshotProperties.eventCount)
        else
            NoSnapshotTriggerDefinition.INSTANCE

    // ES-3-pesimistic: cached copy-on-write repository for the hot InventoryItem aggregate, locked with
    // Axon's default PessimisticLockFactory (same cache as ES-3-optimistic, opposite concurrency model).
    // Referenced by @Aggregate(repository = "inventoryItemRepository") on InventoryItem — wiring the
    // repository through the annotation is what makes the cache actually take effect, since @Aggregate
    // registration wins over any manual configurer.configureAggregate(...) for the same type.
    // OrderAggregate keeps Axon's default (pessimistic, uncached) repository.
    @Bean
    fun inventoryItemRepository(
        eventStore: EventStore,
        @Qualifier("inventorySnapshotTrigger") snapshotTrigger: SnapshotTriggerDefinition,
        @Qualifier("axonObjectMapper") axonObjectMapper: com.fasterxml.jackson.databind.ObjectMapper,
        meterRegistry: MeterRegistry,
        cacheProperties: CacheProperties,
    ): PessimisticCachingRepository<InventoryItem> {
        val builder = EventSourcingRepository.builder(InventoryItem::class.java)
            .eventStore(eventStore)
            .aggregateFactory(GenericAggregateFactory(InventoryItem::class.java))
            .snapshotTriggerDefinition(snapshotTrigger)
            // Explicit, though this is also the LockingRepository.Builder default: commands on one
            // aggregate are serialised in-JVM, so conflicting appends never reach the event store.
            .lockFactory(PessimisticLockFactory.usingDefaults())
        log.info(
            "InventoryItem -> PessimisticCachingRepository (PessimisticLockFactory, copy-on-write, cache.enabled={})",
            cacheProperties.enabled,
        )
        return PessimisticCachingRepository(
            builder = builder,
            eventStore = eventStore,
            aggregateType = InventoryItem::class.java,
            snapshotTriggerDefinition = snapshotTrigger,
            objectMapper = axonObjectMapper,
            meterRegistry = meterRegistry,
            cacheEnabled = cacheProperties.enabled,
        )
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
