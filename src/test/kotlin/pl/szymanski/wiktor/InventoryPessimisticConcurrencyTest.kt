package pl.szymanski.wiktor

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.eventhandling.GenericDomainEventMessage
import org.axonframework.eventsourcing.eventstore.EventStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.szymanski.wiktor.config.PessimisticCachingRepository
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservationFailedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the cached [PessimisticCachingRepository] under real contention against a Postgres event
 * store: many concurrent reserve commands hit ONE aggregate. Unlike the ES-3-optimistic variant, the
 * per-aggregate pessimistic lock serialises them inside the JVM, so no two commands ever compete for
 * the same sequence number. Verifies the mirror-image outcome of that branch's test: a correct,
 * consistent result with no over-reservation AND no conflicts at all — zero optimistic retries, zero
 * commands failing with exhausted retries, every reserve honoured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InventoryPessimisticConcurrencyTest {

    @Autowired lateinit var gateway: CommandGateway
    @Autowired lateinit var eventStore: EventStore
    @Autowired lateinit var meterRegistry: MeterRegistry
    @Autowired lateinit var inventoryItemRepository: PessimisticCachingRepository<InventoryItem>
    @Autowired @Qualifier("axonJdbcTemplate") lateinit var jdbc: NamedParameterJdbcTemplate

    @Test
    fun `concurrent reserves on one item are serialised by the lock with no conflicts`() {
        val itemId = UUID.randomUUID().toString()
        val initialStock = 100
        val concurrentReserves = 100
        gateway.sendAndWait<Any?>(CreateItemCommand(id = itemId, availableQty = initialStock))

        val pool = Executors.newFixedThreadPool(32)
        val start = CountDownLatch(1)
        val done = CountDownLatch(concurrentReserves)
        val rejected = AtomicInteger(0)
        repeat(concurrentReserves) {
            pool.submit {
                start.await()
                try {
                    gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 1))
                } catch (e: Exception) {
                    rejected.incrementAndGet() // must stay 0: the lock removes the conflicts entirely
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertThat(done.await(90, TimeUnit.SECONDS)).`as`("all reserve commands settled").isTrue()
        pool.shutdown()

        // Reduce the event store — the single source of truth — rather than the async projection.
        // readEvents(id, 0) reads the raw stream from sequence 0; the single-argument overload would
        // start at the newest snapshot and hide every event before it.
        var created = 0
        var reserved = 0
        var failed = 0
        var head = -1L
        val stream = eventStore.readEvents(itemId, 0L)
        while (stream.hasNext()) {
            val event = stream.next()
            head = event.sequenceNumber
            when (event.payload) {
                is InventoryCreatedEvent -> created++
                is InventoryReservedEvent -> reserved++
                is InventoryReservationFailedEvent -> failed++
            }
        }
        val availableQty = initialStock - reserved
        val retries = meterRegistry.get("inventory.optimistic.retry").counter().count()
        val exhausted = meterRegistry.get("inventory.optimistic.exhausted").counter().count()

        assertThat(created).`as`("exactly one creation event").isEqualTo(1)
        assertThat(availableQty).`as`("no over-reservation").isGreaterThanOrEqualTo(0)
        assertThat(reserved).`as`("cannot reserve more than stock").isLessThanOrEqualTo(initialStock)
        // Every non-conflicting outcome is persisted at exactly one sequence slot: 0=created, then reserved+failed.
        assertThat(reserved + failed).`as`("sequence integrity (no gaps/duplicates)").isEqualTo(head.toInt())
        // Cache holds only confirmed state and is advanced to the store head (never stale, never ahead).
        assertThat(inventoryItemRepository.cachedSequence(itemId)).`as`("cache == store head").isEqualTo(head)
        // The lock, not a retry loop, resolved the contention: no ConcurrencyException was ever raised,
        // so every one of the 100 reserves went through on the first attempt.
        assertThat(retries).`as`("no optimistic retries under the lock").isEqualTo(0.0)
        assertThat(exhausted).`as`("no command exhausted its retries").isEqualTo(0.0)
        assertThat(rejected.get()).`as`("no reserve command failed").isEqualTo(0)
        assertThat(reserved).`as`("every reserve honoured").isEqualTo(concurrentReserves)
        // Regression guard: serving loads from cache must NOT starve the event-count snapshot trigger.
        // Preparing a fresh trigger per hit resets its counter to 0, so with 100 events and a
        // threshold of 30 no snapshot would ever be written — the cache must carry the live trigger.
        assertThat(awaitSnapshot(itemId)).`as`("snapshots still triggered while serving from cache").isTrue()

        println(
            "[PES-IT] stock=$initialStock attempts=$concurrentReserves reserved=$reserved failed=$failed " +
                "rejected=${rejected.get()} head=$head retries=$retries cacheSeq=${inventoryItemRepository.cachedSequence(itemId)}",
        )
    }

    /**
     * The cache is never evicted and, after the first load, never misses — so [catchUp] on rollback
     * is the ONLY path that can repair a stale entry. This drives the real sequence: another writer
     * appends behind our back, our next command collides, the rollback runs catchUp, and the retry
     * succeeds against the advanced state. Without catchUp the retry would target the same taken
     * sequence number forever and the command would exhaust and REJECT.
     */
    @Test
    fun `catchUp repairs the cache after a foreign append, so the retry succeeds`() {
        val itemId = UUID.randomUUID().toString()
        gateway.sendAndWait<Any?>(CreateItemCommand(id = itemId, availableQty = 100))
        gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 1))
        val seqBefore = inventoryItemRepository.cachedSequence(itemId)
        assertThat(seqBefore).`as`("cache seeded by the first reserve").isEqualTo(1L)

        val catchupsBefore = meterRegistry.get("inventory.opt.catchup").counter().count()
        val failedBefore = meterRegistry.get("inventory.opt.catchup.failed").counter().count()

        // Simulate a second node: append straight to the store at the sequence our cached aggregate
        // will target next, leaving the cache one event behind the truth.
        eventStore.publish(
            GenericDomainEventMessage(
                "InventoryItem", itemId, seqBefore!! + 1,
                InventoryReservedEvent(itemId, UUID.randomUUID(), 1),
            ),
        )

        // Collides at seqBefore+1, rolls back, catchUp pulls in the foreign event, retry lands at +2.
        gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 1))

        val catchups = meterRegistry.get("inventory.opt.catchup").counter().count() - catchupsBefore
        val failures = meterRegistry.get("inventory.opt.catchup.failed").counter().count() - failedBefore
        assertThat(failures).`as`("catchUp must not have thrown").isEqualTo(0.0)
        assertThat(catchups).`as`("catchUp ran and advanced the cache").isGreaterThanOrEqualTo(1.0)
        assertThat(inventoryItemRepository.cachedSequence(itemId))
            .`as`("cache advanced past the foreign append and the retry")
            .isEqualTo(seqBefore + 2)
    }

    /** The snapshotter runs asynchronously, so poll rather than sleep a fixed amount. */
    private fun awaitSnapshot(itemId: String, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshots = jdbc.queryForObject(
                "SELECT count(*) FROM snapshot_event_entry WHERE aggregate_identifier = :id",
                mapOf("id" to itemId), Int::class.java,
            ) ?: 0
            if (snapshots > 0) return true
            Thread.sleep(250)
        }
        return false
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }
}
