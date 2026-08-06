package pl.szymanski.wiktor.domain

import org.axonframework.test.aggregate.AggregateTestFixture
import org.axonframework.test.aggregate.FixtureConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import java.util.UUID

/**
 * RESERVE_DELAY_MS, the domain-work cost lever. It must be a provable no-op at 0: phase 1 of
 * the load-test campaign measures every variant with it at 0 and compares those numbers
 * against phase-2 runs on the same binaries.
 *
 * The delay lives in the @CommandHandler, deliberately not in the @EventSourcingHandler.
 * Putting it in the latter would charge it on every replay and every snapshot load, turning a
 * per-reserve cost into a startup cost — the opposite of what the lever is for, and invisible
 * to any test that only ever replays one event.
 *
 * Correlation IDs are fixed rather than random: the fixture compares expected and actual event
 * payloads with equals(), so a fresh UUID on either side would never match.
 */
class InventoryItemReserveDelayTest {

    private lateinit var fixture: FixtureConfiguration<InventoryItem>

    private val correlationId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0de")

    @BeforeEach
    fun setUp() {
        fixture = AggregateTestFixture(InventoryItem::class.java)
    }

    private fun created(delayMs: Int, qty: Int = 10) =
        InventoryCreatedEvent("ITEM-1", correlationId, qty, "", delayMs)

    private fun reserve(quantity: Int = 1) =
        SagaReserveItemCommand("ITEM-1", quantity, correlationId)

    private fun elapsedMsOf(block: () -> Unit): Long {
        val startedNs = System.nanoTime()
        block()
        return (System.nanoTime() - startedNs) / 1_000_000
    }

    @Test
    fun `the delay is carried on the creation event`() {
        fixture.givenNoPriorActivity()
            .`when`(CreateItemCommand("ITEM-1", 10, 0, 250, correlationId))
            .expectEvents(InventoryCreatedEvent("ITEM-1", correlationId, 10, "", 250))
    }

    @Test
    fun `a zero delay is carried as zero`() {
        fixture.givenNoPriorActivity()
            .`when`(CreateItemCommand("ITEM-1", 10, 0, 0, correlationId))
            .expectEvents(InventoryCreatedEvent("ITEM-1", correlationId, 10, "", 0))
    }

    @Test
    fun `a successful reserve sleeps for the configured delay`() {
        val elapsed = elapsedMsOf {
            fixture.given(created(delayMs = 300))
                .`when`(reserve())
                .expectEvents(InventoryReservedEvent("ITEM-1", correlationId, 1))
        }
        assertTrue(elapsed >= 300, "expected at least 300ms, took ${elapsed}ms")
    }

    @Test
    fun `a zero delay does not sleep`() {
        val elapsed = elapsedMsOf {
            fixture.given(created(delayMs = 0))
                .`when`(reserve())
                .expectEvents(InventoryReservedEvent("ITEM-1", correlationId, 1))
        }
        assertTrue(elapsed < 250, "expected no sleep, took ${elapsed}ms")
    }

    @Test
    fun `a reserve refused for insufficient stock does not sleep`() {
        // The delay models expensive domain logic, reached only once the reserve is known to
        // succeed. Paying it on the out-of-stock path would make the rejection rate a hidden
        // throughput lever — and on a DISTINCT_ITEMS=1 contention sweep, where most orders are
        // refused, it would dominate the measurement.
        val elapsed = elapsedMsOf {
            fixture.given(created(delayMs = 1000, qty = 1))
                .`when`(reserve(quantity = 5))
                .expectEvents(
                    InventoryReservationFailedEvent(
                        "ITEM-1", correlationId, "Insufficient stock: available=1 requested=5"
                    )
                )
        }
        assertTrue(elapsed < 500, "expected no sleep on refusal, took ${elapsed}ms")
    }

    @Test
    fun `replaying prior reserves does not pay the delay`() {
        // Four events are replayed as GIVEN state. Were the sleep in the @EventSourcingHandler
        // this would cost at least 4 x 300ms before the command under test even ran; only the
        // single command-path sleep is legitimate.
        val elapsed = elapsedMsOf {
            fixture.given(
                created(delayMs = 300, qty = 100),
                InventoryReservedEvent("ITEM-1", correlationId, 1),
                InventoryReservedEvent("ITEM-1", correlationId, 1),
                InventoryReservedEvent("ITEM-1", correlationId, 1),
            )
                .`when`(reserve())
                .expectEvents(InventoryReservedEvent("ITEM-1", correlationId, 1))
        }
        assertTrue(elapsed < 900, "replay appears to pay the delay: took ${elapsed}ms")
    }
}
