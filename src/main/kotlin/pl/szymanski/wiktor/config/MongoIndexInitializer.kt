package pl.szymanski.wiktor.config

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.axonframework.common.transaction.NoTransactionManager
import org.axonframework.extensions.mongo.MongoTemplate
import org.axonframework.extensions.mongo.eventsourcing.eventstore.MongoEventStorageEngine
import org.axonframework.extensions.mongo.eventsourcing.eventstore.StorageStrategy
import org.axonframework.extensions.mongo.eventsourcing.tokenstore.MongoTokenStore
import org.axonframework.serialization.Serializer
import org.slf4j.LoggerFactory

/**
 * Creates every index this branch depends on, and is the whole of what replaces Flyway.
 *
 * `V6__axon_tables.sql` is gone with the rest of `db/migration/`, but one line of it was never
 * merely schema: `UNIQUE (aggregate_identifier, sequence_number)`. With `NullLockFactory` on
 * the InventoryItem repository that constraint is the ONLY thing that detects two commands
 * writing the same version, so its Mongo counterpart -- a unique index on
 * `{aggregateIdentifier, sequenceNumber}` -- is a correctness requirement, not an optimisation.
 * Without it the store silently accepts both writes and the aggregate's history forks.
 *
 * **Why this runs the framework's own `ensureIndexes` through throwaway instances rather than
 * being called on the real beans.** MongoDB refuses `createIndexes` inside a multi-document
 * transaction on a collection that already exists, and both
 * [MongoEventStorageEngine.ensureIndexes] and [MongoTokenStore]'s constructor-time index
 * creation route through the Axon `TransactionManager` they are configured with. The real
 * stores are wired to a `MongoTransactionManager` -- deliberately, since transactions are what
 * make a multi-event append atomic -- so calling `ensureIndexes` on them succeeds on a virgin
 * database and then fails on every restart afterwards. Building one extra instance of each
 * against [DirectMongoTemplate] and [NoTransactionManager] keeps the index definitions the
 * framework's (no hand-copied field names to drift) while keeping the DDL out of a
 * transaction. The throwaway instances are discarded immediately; they hold no resources.
 *
 * The saga index is the one exception: [org.axonframework.extensions.mongo.eventhandling.saga.repository.MongoSagaStore]
 * ships no `ensureIndexes` of its own, so it is spelled out here. It matters more on this
 * branch than the count suggests -- `findSagas` looks a saga up by association value on every
 * single `InventoryReservedEvent`, which at 60 saga segments is the hottest read in the run.
 */
@Suppress("DEPRECATION")  // ensureIndexes() is deprecated in favour of letting the stores
                          // create their own indexes at construction -- which is exactly the
                          // thing that cannot work here, because construction happens under a
                          // MongoTransactionManager. Explicit, out-of-transaction DDL is the
                          // point of this class.
class MongoIndexInitializer(
    directTemplate: MongoTemplate,
    storageStrategy: StorageStrategy,
    serializer: Serializer,
) {
    init {
        // Events + snapshots: unique {aggregateIdentifier, sequenceNumber} on both, plus the
        // ordered stream index the tracking processors scan.
        MongoEventStorageEngine.builder()
            .mongoTemplate(directTemplate)
            .storageStrategy(storageStrategy)
            .eventSerializer(serializer)
            .snapshotSerializer(serializer)
            .transactionManager(NoTransactionManager.INSTANCE)
            .build()
            .ensureIndexes()

        // Tokens: unique {processorName, segment}.
        MongoTokenStore.builder()
            .mongoTemplate(directTemplate)
            .serializer(serializer)
            .transactionManager(NoTransactionManager.INSTANCE)
            .ensureIndexes(false)
            .build()
            .ensureIndexes()

        val sagas = directTemplate.sagaCollection()
        // Unique per saga instance. MongoSagaStore addresses a saga by this field, never by _id.
        sagas.createIndex(
            Indexes.ascending("sagaIdentifier"),
            IndexOptions().unique(true).name("uniqueSagaIdentifierIndex"),
        )
        // The association lookup: {sagaType, associations.key, associations.value}. Field names
        // are SagaEntry's; they are private constants there, which is why this one index is the
        // only place in this class with hand-written names. A rename in the extension would show
        // up as a collection scan on the hottest read, not as an error -- see the saga.lifetime
        // and order.e2e.time percentiles if this branch is inexplicably slow.
        sagas.createIndex(
            Indexes.ascending("sagaType", "associations.key", "associations.value"),
            IndexOptions().name("sagaAssociationIndex"),
        )

        log.info(
            "[INDEXES] ensured on {}, {}, {}, {}",
            MongoCollections.DOMAIN_EVENTS,
            MongoCollections.SNAPSHOT_EVENTS,
            MongoCollections.TRACKING_TOKENS,
            MongoCollections.SAGAS,
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(MongoIndexInitializer::class.java)
    }
}
