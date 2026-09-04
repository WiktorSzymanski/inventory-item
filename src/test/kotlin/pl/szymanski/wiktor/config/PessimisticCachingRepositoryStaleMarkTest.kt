package pl.szymanski.wiktor.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.axonframework.common.lock.NullLockFactory
import org.axonframework.eventhandling.GenericDomainEventMessage
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.GenericAggregateFactory
import org.axonframework.eventsourcing.NoSnapshotTriggerDefinition
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine
import org.axonframework.messaging.Message
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork
import org.axonframework.modelling.command.ConcurrencyException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import java.time.Duration
import java.util.UUID

/**
 * Fast, deterministic cover for the stale-mark repair strategy. No Spring, no container: an
 * in-memory event store plus a hand-built repository, so every case here runs in milliseconds.
 *
 * The races this logic exists for are NOT reproduced with threads — they are driven by hand:
 * a "foreign" append is published straight to the store, and a lost race is a UnitOfWork rolled
 * back with a [ConcurrencyException]. That is what makes these tests deterministic;
 * [pl.szymanski.wiktor.InventoryPessimisticConcurrencyTest] covers the real concurrent path.
 */
class PessimisticCachingRepositoryStaleMarkTest {

    private lateinit var eventStore: EmbeddedEventStore
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var repository: PessimisticCachingRepository<InventoryItem>

    private val itemId: String = UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        eventStore = EmbeddedEventStore.builder()
            .storageEngine(InMemoryEventStorageEngine())
            .build()
        meterRegistry = SimpleMeterRegistry()
        val objectMapper = ObjectMapper().apply {
            registerModule(kotlinModule())
            findAndRegisterModules()
        }
        val builder = EventSourcingRepository.builder(InventoryItem::class.java)
            .eventStore(eventStore as EventStore)
            .aggregateFactory(GenericAggregateFactory(InventoryItem::class.java))
            .snapshotTriggerDefinition(NoSnapshotTriggerDefinition.INSTANCE)
            .lockFactory(NullLockFactory.INSTANCE)
        repository = PessimisticCachingRepository(
            builder = builder,
            eventStore = eventStore,
            aggregateType = InventoryItem::class.java,
            snapshotTriggerDefinition = NoSnapshotTriggerDefinition.INSTANCE,
            objectMapper = objectMapper,
            meterRegistry = meterRegistry,
            cacheProperties = CacheProperties(enabled = true, ttl = Duration.ofMinutes(10), maximumSize = 1000),
        )
    }

    @AfterEach
    fun tearDown() {
        eventStore.shutDown()
        meterRegistry.close()
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Appends an event straight to the store, bypassing the repository — a "foreign" writer. */
    private fun foreignAppend(sequence: Long, payload: Any) {
        eventStore.publish(GenericDomainEventMessage("InventoryItem", itemId, sequence, payload))
    }

    private fun seedItem(quantity: Int = 100) =
        foreignAppend(0L, InventoryCreatedEvent(itemId, UUID.randomUUID(), quantity))

    private fun reserved(quantity: Int = 1) = InventoryReservedEvent(itemId, UUID.randomUUID(), quantity)

    /** Loads through the repository inside a UnitOfWork and commits — seeds/advances the cache. */
    private fun loadAndCommit() {
        val uow = DefaultUnitOfWork.startAndGet<Message<*>>(null)
        repository.load(itemId)
        uow.commit()
    }

    /** Loads, then rolls the UnitOfWork back with [cause] — the losing-command path. */
    private fun loadAndRollback(cause: Throwable) {
        val uow = DefaultUnitOfWork.startAndGet<Message<*>>(null)
        repository.load(itemId)
        uow.rollback(cause)
    }

    private fun counter(name: String): Double = meterRegistry.find(name).counter()?.count() ?: 0.0

    private fun catchupCount(outcome: String): Long =
        meterRegistry.find("inventory.opt.catchup.duration").tag("outcome", outcome).timer()?.count() ?: 0L

    // --- entry + merge semantics ---------------------------------------------------------------

    @Test
    fun `a newly seeded entry carries no stale mark`() {
        seedItem()
        loadAndCommit()

        assertThat(repository.cachedSequence(itemId)).isEqualTo(0L)
        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(UNKNOWN_SEQUENCE)
    }

    @Test
    fun `merge keeps the higher sequence and carries an unresolved mark forward`() {
        val old = Confirmed(root = "old", sequence = 4L, deleted = false,
            trigger = NoSnapshotTriggerDefinition.TRIGGER, knownStoreSequence = 7L)
        val candidate = Confirmed(root = "new", sequence = 5L, deleted = false,
            trigger = NoSnapshotTriggerDefinition.TRIGGER, knownStoreSequence = UNKNOWN_SEQUENCE)

        val merged = mergeConfirmed(old, candidate)

        assertThat(merged.root).isEqualTo("new")
        assertThat(merged.sequence).isEqualTo(5L)
        // 5 < 7: the store is still known to be ahead, so the mark must survive the advance.
        assertThat(merged.knownStoreSequence).isEqualTo(7L)
    }

    @Test
    fun `merge never moves the cache backwards`() {
        val old = Confirmed(root = "old", sequence = 9L, deleted = false,
            trigger = NoSnapshotTriggerDefinition.TRIGGER, knownStoreSequence = UNKNOWN_SEQUENCE)
        val candidate = Confirmed(root = "new", sequence = 5L, deleted = false,
            trigger = NoSnapshotTriggerDefinition.TRIGGER, knownStoreSequence = 3L)

        assertThat(mergeConfirmed(old, candidate)).isSameAs(old)
    }

    // --- marking on rollback -------------------------------------------------------------------

    @Test
    fun `a ConcurrencyException rollback marks the entry with the sequence it failed to insert`() {
        seedItem()
        loadAndCommit()                       // cache at 0
        foreignAppend(1L, reserved())         // a foreign writer takes sequence 1

        loadAndRollback(ConcurrencyException("simulated 23505"))

        // Loaded at 0, so it tried to insert 1 and lost: sequence 1 is proven to exist.
        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(1L)
        assertThat(repository.cachedSequence(itemId)).isEqualTo(0L)
        assertThat(counter("inventory.opt.cache.stale.mark")).isEqualTo(1.0)
    }

    @Test
    fun `a wrapped ConcurrencyException is still recognised`() {
        seedItem()
        loadAndCommit()

        loadAndRollback(IllegalStateException("wrapper", ConcurrencyException("simulated 23505")))

        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(1L)
    }

    @Test
    fun `a non-concurrency rollback leaves the entry unmarked`() {
        seedItem()
        loadAndCommit()

        // A business failure or a DB timeout proves NOTHING about the store head. Marking here
        // would strand the entry above its own sequence forever, costing a SELECT on every load.
        loadAndRollback(IllegalStateException("insufficient stock"))

        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(UNKNOWN_SEQUENCE)
        assertThat(counter("inventory.opt.cache.stale.mark")).isEqualTo(0.0)
    }

    @Test
    fun `a rollback no longer reads the store`() {
        seedItem()
        loadAndCommit()
        foreignAppend(1L, reserved())

        loadAndRollback(ConcurrencyException("simulated 23505"))

        // The whole point of the change: the losing command records one number and stops.
        assertThat(catchupCount("noop") + catchupCount("applied") + catchupCount("failed")).isZero()
    }

    @Test
    fun `the mark only ever moves up`() {
        seedItem()
        foreignAppend(1L, reserved())
        foreignAppend(2L, reserved())
        loadAndCommit()                       // cold miss -> cache at 2

        repository.markForTest(itemId, 3L)
        repository.markForTest(itemId, 2L)

        assertThat(repository.cachedKnownSequence(itemId)).isEqualTo(3L)
    }
}
