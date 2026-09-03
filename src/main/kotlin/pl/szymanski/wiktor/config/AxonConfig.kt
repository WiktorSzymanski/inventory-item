package pl.szymanski.wiktor.config

import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.common.lock.NullLockFactory
import org.axonframework.common.transaction.TransactionManager
import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.GenericAggregateFactory
import org.axonframework.eventsourcing.NoSnapshotTriggerDefinition
import org.axonframework.eventsourcing.SnapshotTriggerDefinition
import org.axonframework.eventsourcing.Snapshotter
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.extensions.mongo.eventhandling.saga.repository.MongoSagaStore
import org.axonframework.extensions.mongo.eventsourcing.eventstore.MongoEventStorageEngine
import org.axonframework.extensions.mongo.eventsourcing.eventstore.StorageStrategy
import org.axonframework.extensions.mongo.eventsourcing.eventstore.documentperevent.DocumentPerEventStorageStrategy
import org.axonframework.extensions.mongo.eventsourcing.eventstore.documentperevent.EventEntryConfiguration
import org.axonframework.extensions.mongo.eventsourcing.tokenstore.MongoTokenStore
import org.axonframework.serialization.Serializer
import org.axonframework.spring.messaging.unitofwork.SpringTransactionManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import pl.szymanski.wiktor.domain.InventoryItem
import java.time.Duration
import org.axonframework.extensions.mongo.MongoTemplate as AxonMongoTemplate

/**
 * Every Axon store on this branch, on MongoDB.
 *
 * This is the whole of the ES-2 -> ES-2-mongo delta on the write path. The aggregate, the saga,
 * the processor topology, the thread widths, the retry curve, the snapshot trigger and every
 * Micrometer name are untouched, which is the point: an ES-2 run and an ES-2-mongo run at the
 * same workload point differ in the store and in nothing else.
 *
 * Three things that were in the Postgres version are GONE rather than translated, and each
 * absence is deliberate:
 *
 *  - **The second connection pool.** ES-2 builds `axonDataSource`, a Hikari pool separate from
 *    the app's, so Axon never contends with the Spring Data repositories. The premise there is
 *    that a JDBC connection is held for the length of a transaction, so a busy thread pins two.
 *    The MongoDB driver checks a connection out per OPERATION and returns it, so there is
 *    nothing for a second pool to protect against; one client with a wide pool is both simpler
 *    and the only shape Micrometer's `mongodb.driver.pool.*` gauges can report on. See
 *    [CommandGatewayConfig] for what that does to the budget arithmetic.
 *
 *  - **`EventSchema` / `TokenSchema` / `PostgresSagaSqlSchema`.** Column-name mapping has no
 *    counterpart; a document carries its own field names. Only the COLLECTION names are chosen,
 *    in [MongoCollections], and they deliberately match the Postgres table names.
 *
 *  - **The three gap knobs.** `max-gap-offset`, `gap-timeout-ms` and `gap-cleaning-threshold`
 *    exist on ES-2 because `global_index` is a non-transactional `BIGSERIAL`: a rolled-back
 *    append burns a value and leaves a permanent hole that `GapAwareTrackingToken` must carry.
 *    `MongoTrackingToken` is not indexed off a sequence at all -- it is a timestamp plus the
 *    identifiers seen inside a look-back window -- so the concept does not transfer. Its
 *    analogous CORRECTNESS knob is `lookBackTime` below, and it inherits the same warning:
 *    a non-zero `completion_ratio_inverse` on any run here should send you to that value first.
 */
@Configuration
@EnableConfigurationProperties(SnapshotProperties::class)
class AxonConfig {

    private val log = LoggerFactory.getLogger(AxonConfig::class.java)

    @Bean
    fun axonObjectMapper(): com.fasterxml.jackson.databind.ObjectMapper =
        com.fasterxml.jackson.databind.ObjectMapper().apply {
            registerModule(kotlinModule())
            findAndRegisterModules()
        }

    /**
     * Explicit and `@Primary` for the same reason ES-2 declares its own: Spring Boot's
     * auto-configuration backs off as soon as any `PlatformTransactionManager` bean exists, and
     * [SpringTransactionManager] below needs one to hand to Axon.
     *
     * This is where the single-node replica set earns its keep. `MongoTransactionManager` opens
     * a real MongoDB session, so an Axon unit of work that appends several events, advances a
     * token and writes a saga commits all of it or none of it. Against a standalone `mongod` the
     * same manager would fail at the first `startTransaction`, which is why
     * `docker-compose.yml` runs `--replSet rs0` and blocks the api on the init container.
     */
    @Bean("transactionManager")
    @Primary
    fun transactionManager(factory: MongoDatabaseFactory): PlatformTransactionManager =
        MongoTransactionManager(factory)

    @Bean
    fun axonTransactionManager(transactionManager: PlatformTransactionManager): TransactionManager =
        SpringTransactionManager(transactionManager)

    /**
     * The collections the Axon stores read and write, session-aware so their writes join the
     * transaction opened above. [SessionAwareMongoTemplate] explains why this is hand-written
     * rather than taken from the extension's Spring Boot starter.
     */
    @Bean("axonMongoTemplate")
    fun axonMongoTemplate(factory: MongoDatabaseFactory): AxonMongoTemplate =
        SessionAwareMongoTemplate(factory)

    /**
     * How an event becomes a document. `DocumentPerEvent` -- one document per event -- rather
     * than `DocumentPerCommit`, because it is the strategy that keeps the store queryable the
     * way `domain_event_entry` is, and because with a transaction manager wired the atomicity
     * that `DocumentPerCommit` would buy structurally is already there.
     *
     * `lookBackTime` is the [MongoTrackingToken] equivalent of ES-2's `max-gap-offset`: how far
     * back a tracking processor re-reads to catch events whose write landed after it had already
     * advanced past their timestamp. 1000 ms is the extension's own default. Overridable so it
     * can be swept, exactly as `max-gap-offset` was on ES-2.
     */
    @Bean
    fun eventStorageStrategy(
        @Value("\${axon.eventstore.mongo.look-back-time-ms:1000}") lookBackTimeMs: Long,
    ): StorageStrategy = DocumentPerEventStorageStrategy(
        EventEntryConfiguration.getDefault(),
        Duration.ofMillis(lookBackTimeMs),
    )

    /**
     * Runs before the stores that need the indexes, by being their constructor argument. See
     * [MongoIndexInitializer] for why the DDL cannot simply be `ensureIndexes()` on the beans
     * below.
     */
    @Bean
    fun mongoIndexInitializer(
        factory: MongoDatabaseFactory,
        eventStorageStrategy: StorageStrategy,
        @Qualifier("eventSerializer") eventSerializer: Serializer,
    ): MongoIndexInitializer =
        MongoIndexInitializer(DirectMongoTemplate(factory), eventStorageStrategy, eventSerializer)

    @Bean
    fun tokenStore(
        @Qualifier("axonMongoTemplate") mongoTemplate: AxonMongoTemplate,
        axonTransactionManager: TransactionManager,
        serializer: Serializer,
        @Suppress("UNUSED_PARAMETER") mongoIndexInitializer: MongoIndexInitializer,
    ): MongoTokenStore = MongoTokenStore.builder()
        .mongoTemplate(mongoTemplate)
        .serializer(serializer)
        .transactionManager(axonTransactionManager)
        // Already created by mongoIndexInitializer, outside any transaction. Leaving this true
        // would re-run createIndexes inside the MongoTransactionManager's session, which
        // MongoDB refuses on an existing collection -- so the app would start once on a virgin
        // database and fail on every restart afterwards.
        .ensureIndexes(false)
        .build()

    @Bean
    fun eventStorageEngine(
        @Qualifier("axonMongoTemplate") mongoTemplate: AxonMongoTemplate,
        axonTransactionManager: TransactionManager,
        eventStorageStrategy: StorageStrategy,
        @Qualifier("eventSerializer") eventSerializer: Serializer,
        meterRegistry: MeterRegistry,
        @Suppress("UNUSED_PARAMETER") mongoIndexInitializer: MongoIndexInitializer,
    ): EventStorageEngine {
        val mongo = MongoEventStorageEngine.builder()
            .mongoTemplate(mongoTemplate)
            .storageStrategy(eventStorageStrategy)
            .transactionManager(axonTransactionManager)
            .eventSerializer(eventSerializer)
            .snapshotSerializer(eventSerializer)
            // The counterpart of ES-2's SQLStateResolver(), and load-bearing for the same
            // reason: with NullLockFactory below, a conflict that is not reported as a
            // ConcurrencyException is never retried and fails terminally. The engine installs a
            // duplicate-key-only resolver by default; [MongoConflictResolver] also recognises
            // the WriteConflict that the same race produces once appends run in a transaction.
            .persistenceExceptionResolver(MongoConflictResolver)
            .build()
        return TimedEventStorageEngine(mongo, meterRegistry)
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

    /**
     * Unchanged from ES-2, including every reason it is spelled out rather than left implicit.
     *
     * `@Aggregate(repository = "inventoryItemRepository")` is what makes this bean take effect;
     * a bean the annotation does not name is silently ignored and the aggregate keeps Axon's
     * pessimistic default. The snapshot trigger has to be set HERE rather than on the
     * annotation, because Axon ignores `snapshotTriggerDefinition` once `repository` is set --
     * and snapshotting at 30 events is the one thing that makes ES-2 ES-2.
     *
     * [NullLockFactory] overrides `LockingRepository`'s pessimistic default: concurrent commands
     * on one aggregate all load at sequence N and all try to append N+1. Exactly one wins; the
     * losers get a ConcurrencyException (via [MongoConflictResolver] above) and are retried by
     * [ConcurrencyRetryScheduler], which reloads from the newest snapshot plus its tail and so
     * sees the winner's event.
     */
    @Bean
    fun inventoryItemRepository(
        eventStore: EventStore,
        @Qualifier("inventorySnapshotTrigger") snapshotTrigger: SnapshotTriggerDefinition,
    ): EventSourcingRepository<InventoryItem> {
        log.info("InventoryItem -> EventSourcingRepository (NullLockFactory, uncached, snapshots via inventorySnapshotTrigger)")
        return EventSourcingRepository.builder(InventoryItem::class.java)
            .eventStore(eventStore)
            .aggregateFactory(GenericAggregateFactory(InventoryItem::class.java))
            .snapshotTriggerDefinition(snapshotTrigger)
            .lockFactory(NullLockFactory.INSTANCE)
            .build()
    }

    /**
     * One collection, not two. `JdbcSagaStore` needs `saga_entry` AND `association_value_entry`
     * plus two indexes to join them; [MongoSagaStore] keeps a saga's association values as an
     * array inside its own document and queries them with a single predicate. The lookup index
     * is created by [MongoIndexInitializer].
     */
    @Bean
    fun sagaStore(
        @Qualifier("axonMongoTemplate") mongoTemplate: AxonMongoTemplate,
        axonTransactionManager: TransactionManager,
        serializer: Serializer,
        @Suppress("UNUSED_PARAMETER") mongoIndexInitializer: MongoIndexInitializer,
    ): MongoSagaStore = MongoSagaStore.builder()
        .mongoTemplate(mongoTemplate)
        .serializer(serializer)
        .transactionManager(axonTransactionManager)
        .build()
}
