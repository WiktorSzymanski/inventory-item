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
 * Drives the cached, LOCK-FREE [PessimisticCachingRepository] under real contention against a
 * Postgres event store: many concurrent reserve commands hit ONE aggregate and collide on the
 * `UNIQUE (aggregate_identifier, sequence_number)` constraint. Verifies that optimistic concurrency
 * plus the gateway retry produces a correct, consistent result with no over-reservation and no lost
 * updates — and that the conflicts were actually exercised (retries > 0), i.e. nothing serialised
 * them away. (The class name predates the switch to `NullLockFactory`; see the repository's KDoc.)
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
    fun `concurrent reserves on one item stay consistent with no over-reservation`() {
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
                    // Retries exhausted under contention. Acceptable without a lock — the command is
                    // refused, never half-applied — so this is counted, not asserted to be zero.
                    rejected.incrementAndGet()
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
        // Contention actually happened and was resolved by optimistic retry, not by a lock. This is
        // the assertion that fails if the repository is ever given a real LockFactory again.
        assertThat(retries).`as`("optimistic retries were exercised").isGreaterThan(0.0)
        // Stock covers every request, so nothing may be refused for lack of it: any command that did
        // not land was a lost race, and it left no event behind.
        assertThat(failed).`as`("no reserve refused for stock").isEqualTo(0)
        assertThat(reserved + rejected.get())
            .`as`("every command either appended exactly one event or failed outright")
            .isEqualTo(concurrentReserves)
        assertThat(rejected.get().toDouble())
            .`as`("the only reason a command failed was exhausting its retries")
            .isEqualTo(exhausted)
        // Regression guard: serving loads from cache must NOT starve the event-count snapshot trigger.
        // Preparing a fresh trigger per hit resets its counter to 0, so with 100 events and a
        // threshold of 30 no snapshot would ever be written — the cache must carry the live trigger.
        assertThat(awaitSnapshot(itemId)).`as`("snapshots still triggered while serving from cache").isTrue()

        // What a cache hit costs in place of a store round trip. Recorded as a phase of
        // state_load_time so it reads on the same axis as the `replay` and `total` phases the store
        // path emits — the whole point being to compare the copy against the replay it replaces.
        val copies = meterRegistry.find("state_load_time").tag("phase", "copy")
            .tag("aggregate", "InventoryItem").timer()
        assertThat(copies).`as`("cache hits record a copy phase").isNotNull
        assertThat(copies!!.count())
            .`as`("one copy sample per cache hit, and this run is served from cache")
            .isGreaterThan(0L)
        assertThat(copies.totalTime(TimeUnit.NANOSECONDS)).`as`("copies take non-zero time").isGreaterThan(0.0)

        // The write path's whole state-load cost, hits and misses pooled — the series that answers
        // "what does loading state cost before the append" without first knowing the hit rate. It is
        // the envelope around `copy`, so it can never be the rarer of the two.
        val loads = meterRegistry.find("state_load_time").tag("phase", "load")
            .tag("aggregate", "InventoryItem").tag("path", "command").timer()
        assertThat(loads).`as`("every write-path load is timed").isNotNull
        assertThat(loads!!.count())
            .`as`("one load sample per doLoadWithLock, covering the hit arm copy() also counted")
            .isGreaterThanOrEqualTo(copies.count())
        // The repair reads the store on the same thread, but only after the append already failed.
        // Counting it as a write-path load is exactly the confusion the path tag exists to remove.
        assertThat(meterRegistry.find("state_load_time").tag("phase", "load").tag("path", "repair").timer())
            .`as`("a cache repair is never a write-path load")
            .isNull()
        // Contention here produces far more empty probes than repairs; both must be timed, apart.
        assertThat(catchupDurationCount("noop") + catchupDurationCount("applied"))
            .`as`("every rollback's repair attempt was timed")
            .isGreaterThan(0L)

        println(
            "[OPT-IT] stock=$initialStock attempts=$concurrentReserves reserved=$reserved failed=$failed " +
                "rejected=${rejected.get()} head=$head retries=$retries cacheSeq=${inventoryItemRepository.cachedSequence(itemId)}",
        )
    }

    /**
     * Without a lock this is the ordinary single-node path, not an exotic one, and it is what the
     * previous test exercises in bulk — here it is driven deterministically. A hot entry is never
     * evicted and never misses, so `catchUp` on rollback is the ONLY thing that can repair it: another
     * writer appends behind our back, our next command collides, the rollback runs catchUp, and the
     * retry succeeds against the advanced state. Without catchUp the retry would target the same taken
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
        val appliedBefore = catchupDurationCount("applied")

        // Stand in for the command that wins the race (another thread here, another node in a
        // multi-replica run): append straight to the store at the sequence our cached aggregate will
        // target next, leaving the cache one event behind the truth.
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

        // The repair is the work sitting between the conflict and the retry, so it is timed as one
        // operation. Tagged by outcome because an empty probe and a real replay are the same call
        // and differ by orders of magnitude: pooled, the median would describe the probe.
        assertThat(catchupDurationCount("applied") - appliedBefore)
            .`as`("the repair that advanced the cache was timed under outcome=applied")
            .isGreaterThanOrEqualTo(1L)
        assertThat(catchupDurationTotalNanos("applied"))
            .`as`("a timed repair records a non-zero duration")
            .isGreaterThan(0.0)
    }

    private fun catchupDurationCount(outcome: String): Long =
        meterRegistry.find("inventory.opt.catchup.duration").tag("outcome", outcome).timer()?.count() ?: 0L

    private fun catchupDurationTotalNanos(outcome: String): Double =
        meterRegistry.find("inventory.opt.catchup.duration").tag("outcome", outcome)
            .timer()?.totalTime(TimeUnit.NANOSECONDS) ?: 0.0

    /**
     * The one test that can catch a broken [pl.szymanski.wiktor.config.CacheFedSnapshotter].
     *
     * That snapshotter hand-builds the snapshot message from cached state instead of replaying the
     * store, so a wrong aggregate type name or sequence produces a snapshot that is silently
     * unusable — and nothing notices, because a cache that rarely evicts almost never performs the
     * cold load that would read it back. The benchmark cannot catch this. This test forces the cold
     * load and asserts the restored state is right.
     *
     * The stock check is behavioural on purpose: with 20 units left, reserving 25 must be REFUSED.
     * If the snapshot restored a wrong quantity, that reserve would succeed instead.
     */
    @Test
    fun `a cache-fed snapshot restores correct state on a cold load`() {
        val itemId = UUID.randomUUID().toString()
        gateway.sendAndWait<Any?>(CreateItemCommand(id = itemId, availableQty = 100))
        // 40 events, comfortably past the snapshot threshold of 30.
        repeat(40) { gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 2)) }
        assertThat(awaitSnapshot(itemId)).`as`("a snapshot was written").isTrue()

        // The snapshot came from the cache, not from a store replay — this is what proves the new
        // path actually ran rather than silently falling back to the stock snapshotter.
        val fromCache = meterRegistry.get("inventory.opt.snapshot.duration").tag("source", "cache").timer().count()
        assertThat(fromCache).`as`("snapshot built from cached state").isGreaterThanOrEqualTo(1L)

        // The single-argument overload starts at the newest snapshot. A head above sequence 0 proves
        // the snapshot is readable and is what a cold load would actually start from.
        assertThat(eventStore.readEvents(itemId).peek()!!.sequenceNumber)
            .`as`("cold reads start from the snapshot, not from sequence 0").isGreaterThan(0L)

        // Force the cold path: drop the entry so the next load replays snapshot + tail.
        val missesBefore = meterRegistry.get("inventory.opt.cache.miss").counter().count()
        inventoryItemRepository.evict(itemId)
        assertThat(inventoryItemRepository.cachedSequence(itemId)).`as`("entry dropped").isNull()

        // 100 - (40 * 2) = 20 left, so this must be refused. It is persisted as an event, not thrown.
        gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 25))

        var reserved = 0
        var failed = 0
        var head = -1L
        val stream = eventStore.readEvents(itemId, 0L)
        while (stream.hasNext()) {
            val event = stream.next()
            head = event.sequenceNumber
            when (event.payload) {
                is InventoryReservedEvent -> reserved++
                is InventoryReservationFailedEvent -> failed++
            }
        }

        assertThat(meterRegistry.get("inventory.opt.cache.miss").counter().count() - missesBefore)
            .`as`("the load took the cold-replay path").isGreaterThanOrEqualTo(1.0)
        assertThat(reserved).`as`("the 25-unit reserve was refused").isEqualTo(40)
        assertThat(failed).`as`("insufficient stock recorded — snapshot restored the right quantity").isEqualTo(1)
        assertThat(inventoryItemRepository.cachedSequence(itemId))
            .`as`("the miss re-seeded the cache at the store head").isEqualTo(head)
    }

    /**
     * Eviction must cost a replay, never correctness: the cache is a pure accelerator over the event
     * store, so a dropped entry is just a miss that reloads authoritative state from it.
     */
    @Test
    fun `an evicted entry reloads from the store and keeps reserving correctly`() {
        val itemId = UUID.randomUUID().toString()
        gateway.sendAndWait<Any?>(CreateItemCommand(id = itemId, availableQty = 10))
        gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 4))
        val seqBefore = inventoryItemRepository.cachedSequence(itemId)

        inventoryItemRepository.evict(itemId)
        assertThat(inventoryItemRepository.cachedSequence(itemId)).`as`("entry dropped").isNull()

        // Reload from the store and keep going: 4 already taken, 6 left, so 6 succeeds and 1 fails.
        gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 6))
        gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 1))

        var reserved = 0
        var failed = 0
        val stream = eventStore.readEvents(itemId, 0L)
        while (stream.hasNext()) {
            when (stream.next().payload) {
                is InventoryReservedEvent -> reserved++
                is InventoryReservationFailedEvent -> failed++
            }
        }
        assertThat(reserved).`as`("both valid reserves honoured after the eviction").isEqualTo(2)
        assertThat(failed).`as`("the over-reserve was refused, so stock survived the eviction").isEqualTo(1)
        assertThat(inventoryItemRepository.cachedSequence(itemId))
            .`as`("cache re-seeded and advanced past the pre-eviction sequence").isGreaterThan(seqBefore!!)
    }

    /**
     * Snapshot creation is scheduled at `onPrepareCommit` and Axon's auto-configuration leaves the
     * snapshotter on `DirectExecutor`, so it actually runs on the command thread — but the projection
     * and the commit ordering still make the row's visibility timing-dependent, so poll rather than
     * assume it is already there.
     */
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
