package pl.szymanski.wiktor

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.eventsourcing.EventSourcingRepository
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
 * Drives the LOCK-FREE `inventoryItemRepository` under real contention against a Postgres event
 * store: many concurrent reserve commands hit ONE aggregate and collide on the
 * `UNIQUE (aggregate_identifier, sequence_number)` constraint. Verifies that optimistic concurrency
 * plus the gateway retry produces a correct, consistent result with no over-reservation and no lost
 * updates — and that the conflicts were actually exercised (retries > 0), i.e. nothing serialised
 * them away.
 *
 * That last assertion is the point of the class. `ES-2` had no repository bean at all and relied on
 * Axon's implicit `@Aggregate` registration, which locks pessimistically; a repository bean that the
 * annotation does not name is silently ignored, and a branch in that state looks identical at
 * runtime except that the conflicts never happen.
 *
 * The snapshot assertion guards the other half of the rewiring: `snapshotTriggerDefinition` moved
 * off `@Aggregate` and onto the repository bean, because Axon ignores the annotation attribute once
 * `repository` is set. Get that wrong and `ES-2` silently degrades into `ES-1`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InventoryLockFreeConcurrencyTest {

    @Autowired lateinit var gateway: CommandGateway
    @Autowired lateinit var eventStore: EventStore
    @Autowired lateinit var meterRegistry: MeterRegistry
    @Autowired lateinit var axonConfiguration: org.axonframework.config.Configuration
    @Autowired lateinit var inventoryItemRepository: EventSourcingRepository<InventoryItem>
    @Autowired @Qualifier("axonJdbcTemplate") lateinit var jdbc: NamedParameterJdbcTemplate

    /**
     * The cheap, deterministic half of the wiring check: if `@Aggregate`'s `repository` attribute is
     * dropped this fails immediately rather than through the statistics of the contention test below.
     *
     * **It is not sufficient on its own, and that is not hypothetical.** While
     * `AxonCustomizerConfig.configureInventoryItem` still registered a second, stock configuration
     * for this aggregate, this assertion passed — `Configuration.repository(...)` returned the
     * lock-free bean — while commands were handled through the pessimistic one whose
     * `AggregateAnnotationCommandHandler` had won the command-bus subscription. Only
     * `retries > 0` below caught it.
     */
    @Test
    fun `the aggregate is served by the lock-free repository bean`() {
        assertThat(axonConfiguration.repository(InventoryItem::class.java))
            .`as`("@Aggregate(repository = ...) routes to the NullLockFactory bean")
            .isSameAs(inventoryItemRepository)
    }

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
        // ES-2's identity: the event-count trigger (threshold 30) survived the move from @Aggregate
        // onto the repository bean. Without it this branch would silently be ES-1 with no lock.
        assertThat(awaitSnapshot(itemId)).`as`("snapshots still triggered from the repository bean").isTrue()

        println(
            "[OPT-IT] stock=$initialStock attempts=$concurrentReserves reserved=$reserved failed=$failed " +
                "rejected=${rejected.get()} head=$head retries=$retries",
        )
    }

    /**
     * A snapshot is only useful if a cold load can restore state from it. The stock check is
     * behavioural on purpose: with 20 units left, reserving 25 must be REFUSED — if the snapshot
     * restored a wrong quantity, that reserve would succeed instead. Sequential, so the lock-free
     * write path contributes no noise here.
     */
    @Test
    fun `a snapshot restores correct state on a cold load`() {
        val itemId = UUID.randomUUID().toString()
        gateway.sendAndWait<Any?>(CreateItemCommand(id = itemId, availableQty = 100))
        // 40 events, comfortably past the snapshot threshold of 30.
        repeat(40) { gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 2)) }
        assertThat(awaitSnapshot(itemId)).`as`("a snapshot was written").isTrue()

        // The single-argument overload starts at the newest snapshot. A head above sequence 0 proves
        // the snapshot is readable and is what a cold load would actually start from.
        assertThat(eventStore.readEvents(itemId).peek()!!.sequenceNumber)
            .`as`("cold reads start from the snapshot, not from sequence 0").isGreaterThan(0L)

        // 100 - (40 * 2) = 20 left, so this must be refused. It is persisted as an event, not thrown.
        gateway.sendAndWait<Any?>(SagaReserveItemCommand(id = itemId, quantity = 25))

        var reserved = 0
        var failed = 0
        val stream = eventStore.readEvents(itemId, 0L)
        while (stream.hasNext()) {
            when (stream.next().payload) {
                is InventoryReservedEvent -> reserved++
                is InventoryReservationFailedEvent -> failed++
            }
        }
        assertThat(reserved).`as`("the 25-unit reserve was refused").isEqualTo(40)
        assertThat(failed).`as`("insufficient stock recorded — snapshot restored the right quantity").isEqualTo(1)
    }

    /**
     * Snapshot creation is scheduled at `onPrepareCommit` and Axon's auto-configuration leaves the
     * snapshotter on `DirectExecutor`, so it actually runs on the command thread — but the commit
     * ordering still makes the row's visibility timing-dependent, so poll rather than assume it is
     * already there.
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
