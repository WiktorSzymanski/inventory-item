package pl.szymanski.wiktor.integration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.axonframework.eventhandling.GenericDomainEventMessage
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.axonframework.modelling.command.ConcurrencyException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.szymanski.wiktor.config.MongoCollections
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import java.util.UUID

/**
 * The single most load-bearing assertion on this branch: **a losing append is reported as an
 * Axon [ConcurrencyException]**.
 *
 * On ES-2 that translation is `SQLStateResolver()` turning Postgres' 23505 into one. Here it is
 * the unique index over `{aggregateIdentifier, sequenceNumber}` plus
 * [pl.szymanski.wiktor.config.MongoConflictResolver]. Either way it is the ONLY conflict
 * detector the branch has, because `inventoryItemRepository` is built with `NullLockFactory` and
 * nothing serialises two commands against one aggregate.
 *
 * If this breaks, `ConcurrencyRetryScheduler.scheduleRetry` returns false, every losing command
 * fails terminally into the saga's abandon() path, and the run reports a high rejection rate
 * with no errors anywhere. It is asserted at the STORAGE ENGINE rather than through the gateway
 * on purpose, so a regression here cannot be masked -- or caused -- by the retry scheduler.
 *
 * [pl.szymanski.wiktor.config.MongoConflictResolverTest] covers the same resolver against
 * hand-built driver exceptions; this covers what a real server actually throws.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        // Nothing here needs the saga or the projections; keep the context cheap and the
        // token collection out of the way.
        "axon.saga.total-segments=1",
        "snapshot.enabled=false",
    ],
)
class MongoEventStoreConflictIT {

    @Autowired private lateinit var engine: EventStorageEngine
    @Autowired private lateinit var mongo: MongoOperations

    @Test
    fun `a second append at the same sequence number is a ConcurrencyException`() {
        val aggregateId = UUID.randomUUID().toString()
        val correlationId = UUID.randomUUID()

        engine.appendEvents(
            GenericDomainEventMessage(
                "InventoryItem", aggregateId, 0L,
                InventoryCreatedEvent(aggregateId, correlationId, quantity = 100),
            ),
        )

        // Exactly what two concurrent commands do without a lock: both load at sequence 0 and
        // both try to write sequence 1. A DIFFERENT payload, so nothing but the sequence number
        // can be what collides.
        engine.appendEvents(
            GenericDomainEventMessage(
                "InventoryItem", aggregateId, 1L,
                InventoryReservedEvent(aggregateId, correlationId, quantity = 1),
            ),
        )

        assertThatThrownBy {
            engine.appendEvents(
                GenericDomainEventMessage(
                    "InventoryItem", aggregateId, 1L,
                    InventoryReservedEvent(aggregateId, correlationId, quantity = 7),
                ),
            )
        }
            .`as`(
                "the loser must surface as ConcurrencyException -- ConcurrencyRetryScheduler " +
                    "retries nothing else, so anything else here makes every conflict terminal",
            )
            .isInstanceOf(ConcurrencyException::class.java)

        // The winner stands and the loser left nothing behind: the unique index rejected the
        // write rather than the two of them both landing under one sequence number.
        assertThat(
            mongo.count(
                Query(where("aggregateIdentifier").`is`(aggregateId).and("sequenceNumber").`is`(1L)),
                MongoCollections.DOMAIN_EVENTS,
            ),
        ).`as`("exactly one event survived at sequence 1").isEqualTo(1L)
    }

    @Test
    fun `the unique index that detects the conflict actually exists`() {
        // The assertion above would also pass if the index were missing and something else threw.
        // This one names the mechanism: MongoIndexInitializer replaces V6__axon_tables.sql's
        // UNIQUE (aggregate_identifier, sequence_number), and it has to run OUTSIDE a
        // transaction, which is the whole reason that class exists.
        val indexes = mongo.getCollection(MongoCollections.DOMAIN_EVENTS).listIndexes().toList()
        val unique = indexes.filter { it.getBoolean("unique", false) }
            .map { it.get("key", org.bson.Document::class.java).keys }

        assertThat(unique)
            .`as`("a UNIQUE index over {aggregateIdentifier, sequenceNumber} on %s", MongoCollections.DOMAIN_EVENTS)
            .anyMatch { it.containsAll(listOf("aggregateIdentifier", "sequenceNumber")) }
    }

    companion object {
        @Container
        @JvmStatic
        val mongoDb: MongoDBContainer = MongoDBContainer("mongo:7")

        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.mongodb.uri") {
                // directConnection=true, and it is required rather than tidy. MongoDBContainer
                // initiates the replica set advertising the CONTAINER's hostname, which does not
                // resolve from the host, so a discovering driver times out looking for a primary
                // it can reach. A direct connection to that primary still supports transactions,
                // which is what MongoTransactionManager needs.
                "${mongoDb.getReplicaSetUrl("inventory")}?directConnection=true"
            }
        }
    }
}
