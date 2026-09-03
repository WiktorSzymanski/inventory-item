package pl.szymanski.wiktor.config

import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.common.lock.NullLockFactory
import org.axonframework.common.transaction.TransactionManager
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.GenericAggregateFactory
import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition
import org.axonframework.eventsourcing.NoSnapshotTriggerDefinition
import org.axonframework.eventsourcing.SnapshotTriggerDefinition
import org.axonframework.eventsourcing.Snapshotter
import org.axonframework.eventsourcing.SnapshotterSpanFactory
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.extensions.mongo.eventhandling.saga.repository.MongoSagaStore
import org.axonframework.extensions.mongo.eventsourcing.eventstore.MongoEventStorageEngine
import org.axonframework.extensions.mongo.eventsourcing.eventstore.StorageStrategy
import org.axonframework.extensions.mongo.eventsourcing.eventstore.documentperevent.DocumentPerEventStorageStrategy
import org.axonframework.extensions.mongo.eventsourcing.eventstore.documentperevent.EventEntryConfiguration
import org.axonframework.extensions.mongo.eventsourcing.tokenstore.MongoTokenStore
import org.axonframework.messaging.annotation.HandlerDefinition
import org.axonframework.messaging.annotation.ParameterResolverFactory
import org.axonframework.modelling.command.Repository
import org.axonframework.modelling.command.RepositoryProvider
import org.axonframework.spring.eventsourcing.SpringAggregateSnapshotter
import org.springframework.beans.factory.ObjectProvider
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
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import pl.szymanski.wiktor.domain.InventoryItem
import java.time.Duration
import org.axonframework.extensions.mongo.MongoTemplate as AxonMongoTemplate

/**
 * Every Axon store on this branch, on MongoDB.
 *
 * This is the whole of the ES-4 -> ES-4-mongo delta on the write path. The aggregate, the saga,
 * the processor topology, the thread widths, the retry curve, the snapshot trigger, the
 * confirmed-state cache ([PessimisticCachingRepository] + [CacheFedSnapshotter]) and every
 * Micrometer name are untouched, which is the point: an ES-4 run and an ES-4-mongo run at the
 * same workload point differ in the store and in nothing else.
 *
 * The same substitution was made on ES-2-mongo, from an ES-2 whose AxonConfig is structurally
 * identical to this one; keep the two in step.
 *
 * Three things that were in the Postgres version are GONE rather than translated, and each
 * absence is deliberate:
 *
 *  - **The second connection pool.** ES-4 builds `axonDataSource`, a Hikari pool separate from
 *    the app's, so Axon never contends with the Spring Data repositories. One MongoClient with a
 *    pool as wide as ES-4's two combined replaces it: the driver checks a connection out per
 *    operation rather than pinning one per pool, and a single client is also the only shape
 *    Micrometer's `mongodb.driver.pool.*` gauges can report on. The SEPARATION of the Axon
 *    stores' transactions from the command's survives -- see [axonTransactionManager], where it
 *    is the difference between a working repair path and a silently stale cache.
 *
 *  - **`EventSchema` / `TokenSchema` / `PostgresSagaSqlSchema`.** Column-name mapping has no
 *    counterpart; a document carries its own field names. Only the COLLECTION names are chosen,
 *    in [MongoCollections], and they deliberately match the Postgres table names.
 *
 *  - **The three gap knobs.** `max-gap-offset`, `gap-timeout-ms` and `gap-cleaning-threshold`
 *    exist on ES-4 because `global_index` is a non-transactional `BIGSERIAL`: a rolled-back
 *    append burns a value and leaves a permanent hole that `GapAwareTrackingToken` must carry.
 *    `MongoTrackingToken` is not indexed off a sequence at all -- it is a timestamp plus the
 *    identifiers seen inside a look-back window -- so the concept does not transfer. Its
 *    analogous CORRECTNESS knob is `lookBackTime` below, and it inherits the same warning:
 *    a non-zero `completion_ratio_inverse` on any run here should send you to that value first.
 */
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

    /**
     * The transaction manager the Axon STORES run in, and `REQUIRES_NEW` is the load-bearing part.
     *
     * ES-4 wires its storage engine with a plain `DataSourceConnectionProvider` that is
     * deliberately NOT wrapped in `UnitOfWorkAwareConnectionProviderWrapper`, so every event-store
     * read and append takes its own connection and its own transaction, entirely separate from the
     * one the command's unit of work opened. That is not an accident of the JDBC API -- it is what
     * makes the repair path work, and it has to be reproduced here explicitly.
     *
     * **What happens without it, and it fails silently.** With the default `REQUIRED`, an
     * event-store call joins whatever transaction is already open on the thread. When an append
     * loses the optimistic race the unit of work rolls back, and
     * [PessimisticCachingRepository.catchUp] then reads the delta from `onRollback` -- on the same
     * thread, inside the session the server has just aborted. MongoDB answers
     * `NoSuchTransaction (251)`, `repair()` catches it, increments
     * `inventory_opt_catchup_failed` and logs a WARN, and the cache stays stale. Every later
     * command on that aggregate then conflicts too. Both concurrency tests catch this; a run would
     * only show it as a rejection rate.
     *
     * A second reason, independent of the cache: a Mongo transaction reads at its own start
     * timestamp, so even an unaborted joined session could not see the winner's just-committed
     * event. A repair read has to be a NEW transaction to see it at all.
     */
    @Bean
    fun axonTransactionManager(transactionManager: PlatformTransactionManager): TransactionManager =
        SpringTransactionManager(
            transactionManager,
            DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW),
        )

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

    /**
     * Replaces Axon's auto-configured snapshotter (`AxonAutoConfiguration.aggregateSnapshotter`,
     * which backs off via `@ConditionalOnMissingBean(Snapshotter.class)` as soon as this bean
     * exists) with one that builds InventoryItem snapshots from cached confirmed state — see
     * [CacheFedSnapshotter]. Collaborators are exactly the ones the auto-configuration passed, so
     * the fallback replay path behaves identically to stock.
     *
     * [ConfirmedStateSource] arrives as an [ObjectProvider] to break the bean cycle
     * `aggregateSnapshotter -> inventoryItemRepository -> inventorySnapshotTrigger ->
     * aggregateSnapshotter`; it is resolved per snapshot task, not at construction.
     */
    @Bean
    fun aggregateSnapshotter(
        configuration: org.axonframework.config.Configuration,
        handlerDefinition: HandlerDefinition,
        parameterResolverFactory: ParameterResolverFactory,
        eventStore: EventStore,
        axonTransactionManager: TransactionManager,
        spanFactory: SnapshotterSpanFactory,
        stateSource: ObjectProvider<ConfirmedStateSource>,
        meterRegistry: MeterRegistry,
    ): Snapshotter = CacheFedSnapshotter(
        builder = SpringAggregateSnapshotter.builder()
            // Spelled out rather than `configuration::repository`: RepositoryProvider's method is
            // itself generic, which a Kotlin method reference cannot SAM-convert.
            .repositoryProvider(object : RepositoryProvider {
                override fun <A : Any> repositoryFor(aggregateType: Class<A>): Repository<A> =
                    configuration.repository(aggregateType)
            })
            .transactionManager(axonTransactionManager)
            .eventStore(eventStore)
            .parameterResolverFactory(parameterResolverFactory)
            .handlerDefinition(handlerDefinition)
            .spanFactory(spanFactory) as SpringAggregateSnapshotter.Builder,
        eventStore = eventStore,
        stateSource = stateSource,
        meterRegistry = meterRegistry,
    )

    @Bean
    fun inventorySnapshotTrigger(
        snapshotter: Snapshotter,
        snapshotProperties: SnapshotProperties,
    ): SnapshotTriggerDefinition =
        if (snapshotProperties.enabled)
            EventCountSnapshotTriggerDefinition(snapshotter, snapshotProperties.eventCount)
        else
            NoSnapshotTriggerDefinition.INSTANCE

    // ES-4: cached copy-on-write repository for the hot InventoryItem aggregate, built LOCK-FREE with
    // NullLockFactory. Nothing serialises writers to one InventoryItem, in this JVM or across nodes;
    // conflicts are detected by the event store's unique index over {aggregateIdentifier,
    // sequenceNumber} and resolved by retry.
    // Referenced by @Aggregate(repository = "inventoryItemRepository") on InventoryItem — wiring the
    // repository through the annotation is what makes the cache actually take effect, since @Aggregate
    // registration wins over any manual configurer.configureAggregate(...) for the same type.
    // OrderAggregate keeps Axon's default (pessimistic, uncached) repository.
    //
    // PessimisticCachingRepository is store-agnostic and is carried over from ES-4 byte for byte;
    // only the sentence above about WHAT detects a conflict changes, from a UNIQUE constraint to a
    // unique index. Its correctness rests on that detection exactly as before.
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
            // Overrides the LockingRepository.Builder default (PessimisticLockFactory): concurrent
            // commands on one aggregate all load at sequence N and all try to append N+1. Exactly one
            // wins; the losers get a ConcurrencyException (MongoConflictResolver above) and are
            // retried by ConcurrencyRetryScheduler against the caught-up cache.
            .lockFactory(NullLockFactory.INSTANCE)
        log.info(
            "InventoryItem -> PessimisticCachingRepository (NullLockFactory, copy-on-write, " +
                "cache.enabled={}, ttl={}, maxSize={})",
            cacheProperties.enabled, cacheProperties.ttl, cacheProperties.maximumSize,
        )
        return PessimisticCachingRepository(
            builder = builder,
            eventStore = eventStore,
            aggregateType = InventoryItem::class.java,
            snapshotTriggerDefinition = snapshotTrigger,
            objectMapper = axonObjectMapper,
            meterRegistry = meterRegistry,
            cacheProperties = cacheProperties,
        )
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
