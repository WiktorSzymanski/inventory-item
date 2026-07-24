package pl.szymanski.wiktor

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.eventsourcing.eventstore.EventStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
        var created = 0
        var reserved = 0
        var failed = 0
        var head = -1L
        val stream = eventStore.readEvents(itemId)
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

        println(
            "[PES-IT] stock=$initialStock attempts=$concurrentReserves reserved=$reserved failed=$failed " +
                "rejected=${rejected.get()} head=$head retries=$retries cacheSeq=${inventoryItemRepository.cachedSequence(itemId)}",
        )
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
