package pl.szymanski.wiktor.domain.saga

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.common.Registration
import org.axonframework.eventhandling.EventMessage
import org.axonframework.eventhandling.gateway.EventGateway
import org.axonframework.messaging.MessageDispatchInterceptor
import org.axonframework.modelling.saga.ResourceInjector
import org.axonframework.test.saga.SagaTestFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.szymanski.wiktor.domain.InventoryReservationFailedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderFailedEvent
import pl.szymanski.wiktor.domain.OrderItem
import pl.szymanski.wiktor.domain.SagaReserveAbandonedEvent
import pl.szymanski.wiktor.service.command.CompleteOrderCommand
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.ReleaseReservationCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class OrderReservationSagaTest {

    private lateinit var fixture: SagaTestFixture<OrderReservationSaga>
    private val gateway: CommandGateway = mockk()

    /** Every command the saga dispatched, in order. */
    private val sent = mutableListOf<Any>()

    /** Every event the saga published out-of-band, in order. */
    private val published = mutableListOf<Any>()

    /** Reserve commands for this item id complete exceptionally; everything else succeeds. */
    private var failingItemId: String? = null

    /** When true, CompleteOrderCommand completes exceptionally. */
    private var failComplete: Boolean = false

    /** Commands of this type throw out of `send` itself, before any future exists. */
    private var syncThrowFor: Class<*>? = null

    /**
     * What OrderAggregate.handle(FailOrderCommand) returns: true when it applied the event,
     * false when the order was already terminal and it did nothing.
     */
    private var failOrderApplied: Boolean = true

    /** Held so tests can assert the metric names and tag values, not just the commands. */
    private val meters = SimpleMeterRegistry()

    /**
     * Captures what the saga publishes rather than routing it back into the fixture: an
     * abandonment has to survive a round trip through the event store before it reaches the saga
     * again, so the tests feed it back explicitly to keep that ordering visible.
     */
    private val eventGateway = object : EventGateway {
        override fun publish(events: MutableList<*>) {
            events.filterNotNull().forEach { published.add(it) }
        }

        override fun registerDispatchInterceptor(
            interceptor: MessageDispatchInterceptor<in EventMessage<*>>,
        ): Registration = Registration { true }
    }

    private val orderId = "ORDER-1"
    private val correlationId = UUID.randomUUID()
    private val items = listOf(OrderItem("ITEM-1", 2), OrderItem("ITEM-2", 3))

    @BeforeEach
    fun setUp() {
        sent.clear()
        published.clear()
        failingItemId = null
        failComplete = false
        syncThrowFor = null
        failOrderApplied = true
        meters.clear()

        val captured = slot<Any>()
        every { gateway.send<Any?>(capture(captured)) } answers {
            val command = captured.captured
            sent.add(command)
            if (syncThrowFor?.isInstance(command) == true) {
                throw IllegalStateException("injected synchronous dispatch failure")
            }
            // NOTE: SagaReserveItemCommand's aggregate id property is `id`, not `itemId`.
            // Only OrderItem uses `itemId`.
            val shouldFail = (command is SagaReserveItemCommand && command.id == failingItemId) ||
                (command is CompleteOrderCommand && failComplete)
            when {
                shouldFail -> CompletableFuture.failedFuture<Any?>(RuntimeException("injected append failure"))
                // Mirrors the aggregate's real return value, which the saga now inspects.
                command is FailOrderCommand -> CompletableFuture.completedFuture<Any?>(failOrderApplied)
                else -> CompletableFuture.completedFuture<Any?>(null)
            }
        }

        fixture = SagaTestFixture(OrderReservationSaga::class.java)
        fixture.registerResourceInjector(ResourceInjector { saga -> inject(saga) })
    }

    private fun inject(saga: Any) {
        setField(saga, "commandGateway", gateway)
        setField(saga, "eventGateway", eventGateway)
        setField(saga, "commandExecutor", Executor { it.run() })
        setField(saga, "meterRegistry", meters)
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = OrderReservationSaga::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    @Test
    fun `dispatches a reservation for every line before any of them reports back`() {
        fixture.givenNoPriorActivity()
            .whenPublishingA(OrderCreatedEvent(orderId, "user-1", items, correlationId))

        assertEquals(
            listOf("ITEM-1", "ITEM-2"),
            sent.filterIsInstance<SagaReserveItemCommand>().map { it.id },
            "every line must be in flight after the start event, got: $sent",
        )
    }

    @Test
    fun `completes the order only once the last line has reported`() {
        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .whenPublishingA(InventoryReservedEvent("ITEM-1", correlationId, 2))
            .expectActiveSagas(1)

        assertTrue(
            sent.filterIsInstance<CompleteOrderCommand>().isEmpty(),
            "ITEM-2 is still outstanding, so the order cannot be complete, got: $sent",
        )

        fixture.whenPublishingA(InventoryReservedEvent("ITEM-2", correlationId, 3))
            .expectActiveSagas(0)

        assertEquals(1, sent.filterIsInstance<CompleteOrderCommand>().size)
        assertEquals(1.0, outcome("completed"))
    }

    @Test
    fun `releases what actually reserved when the lines report out of order`() {
        // In flight simultaneously, so nothing guarantees ITEM-1 reports first. Compensation must
        // be built from the events that arrived, not from the order of the lines: releasing
        // `items[index]` would release ITEM-1's quantity for a reservation only ITEM-2 holds.
        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .andThenAPublished(InventoryReservedEvent("ITEM-2", correlationId, 3))
            .whenPublishingA(InventoryReservationFailedEvent("ITEM-1", correlationId, "insufficient stock"))
            .expectActiveSagas(0)

        val releases = sent.filterIsInstance<ReleaseReservationCommand>()
        assertEquals(1, releases.size, "only ITEM-2 reserved, got: $sent")
        assertEquals("ITEM-2", releases.single().id)
        assertEquals(3, releases.single().quantity)
    }

    @Test
    fun `waits for the in-flight lines before compensating a rejected order`() {
        // The whole reason the saga cannot end on the first failure any more: ending here would
        // drop the correlationId association while ITEM-2's reserve is still running, and its
        // InventoryReservedEvent would arrive with no saga left to release it.
        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .whenPublishingA(InventoryReservationFailedEvent("ITEM-1", correlationId, "insufficient stock"))
            .expectActiveSagas(1)

        assertTrue(
            sent.filterIsInstance<FailOrderCommand>().isEmpty(),
            "the order cannot be failed while a reservation is still in flight, got: $sent",
        )

        fixture.whenPublishingA(InventoryReservedEvent("ITEM-2", correlationId, 3))
            .expectActiveSagas(0)

        val releases = sent.filterIsInstance<ReleaseReservationCommand>()
        assertEquals(1, releases.size, "the late reservation must still be released, got: $sent")
        assertEquals("ITEM-2", releases.single().id)
        assertEquals(1, sent.filterIsInstance<FailOrderCommand>().size)
        assertEquals(1.0, outcome("failed"))
    }

    @Test
    fun `completes an order that has no lines at all`() {
        // The line count is now a countdown rather than an index, so an empty order settles at
        // zero instead of walking off the end of the list.
        fixture.givenNoPriorActivity()
            .whenPublishingA(OrderCreatedEvent(orderId, "user-1", emptyList(), correlationId))
            .expectActiveSagas(0)

        assertEquals(1, sent.filterIsInstance<CompleteOrderCommand>().size)
    }

    @Test
    fun `publishes an abandonment instead of failing the order behind the saga's back`() {
        // A reserve that exhausts its retries reports through no event of its own, so the saga
        // would wait for it forever. It cannot be settled from the pool thread either — that
        // thread is outside saga scope — so the disposition is published and read back.
        failingItemId = "ITEM-1"

        fixture.givenNoPriorActivity()
            .whenPublishingA(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .expectActiveSagas(1)

        val abandonments = published.filterIsInstance<SagaReserveAbandonedEvent>()
        assertEquals(1, abandonments.size, "the abandoned line must be published, got: $published")
        assertEquals("ITEM-1", abandonments.single().id)
        assertEquals(correlationId, abandonments.single().correlationId)
        assertTrue(
            sent.filterIsInstance<FailOrderCommand>().isEmpty(),
            "ITEM-2 is still in flight, so the order must not be failed yet, got: $sent",
        )
        assertEquals(1.0, counter("reserve"), "the abandoned dispatch must still be counted")
    }

    @Test
    fun `settles an abandoned line when its published event arrives`() {
        failingItemId = "ITEM-1"

        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .andThenAPublished(InventoryReservedEvent("ITEM-2", correlationId, 3))
            .whenPublishingA(published.filterIsInstance<SagaReserveAbandonedEvent>().single())
            .expectActiveSagas(0)

        val releases = sent.filterIsInstance<ReleaseReservationCommand>()
        assertEquals(1, releases.size, "ITEM-2's reservation must be released, got: $sent")
        assertEquals("ITEM-2", releases.single().id)
        assertEquals(1, sent.filterIsInstance<FailOrderCommand>().size)
        assertEquals(1.0, outcome("command_failed"), "a dispatch failure is not an out-of-stock rejection")
        assertEquals(0.0, outcome("failed"))
    }

    @Test
    fun `keeps the saga alive for in-flight lines when the order is failed from outside`() {
        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .whenPublishingA(OrderFailedEvent(orderId, "failed elsewhere"))
            .expectActiveSagas(1)

        fixture.andThenAPublished(InventoryReservedEvent("ITEM-1", correlationId, 2))
            .whenPublishingA(InventoryReservedEvent("ITEM-2", correlationId, 3))
            .expectActiveSagas(0)

        assertEquals(
            listOf("ITEM-1", "ITEM-2"),
            sent.filterIsInstance<ReleaseReservationCommand>().map { it.id },
            "both reservations must be released once they land, got: $sent",
        )
        assertTrue(
            sent.filterIsInstance<FailOrderCommand>().isEmpty(),
            "the order is already FAILED; failing it again would only be ignored, got: $sent",
        )
        assertEquals(
            1.0,
            meters.counter("saga.completed", "outcome", "command_failed").count(),
            "the terminal path must be tagged outcome=command_failed",
        )
    }

    @Test
    fun `fails and releases the whole order when the completion command fails`() {
        failComplete = true
        val singleLine = listOf(OrderItem("ITEM-1", 2))

        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", singleLine, correlationId))
            .whenPublishingA(InventoryReservedEvent("ITEM-1", correlationId, 2))

        val releases = sent.filterIsInstance<ReleaseReservationCommand>()
        assertEquals(1, releases.size, "the whole order should be released, got: $sent")
        assertEquals("ITEM-1", releases.single().id)
        assertEquals(1, sent.filterIsInstance<FailOrderCommand>().size)
    }

    @Test
    fun `still fails the order when compensation throws synchronously out of send`() {
        // releaseAll runs BEFORE sendFailOrder in the terminal disposition. If a synchronous throw
        // escapes it, the disposition is skipped and the order is stranded PENDING with no counter
        // and no log — the original defect, reintroduced through a narrower door.
        syncThrowFor = ReleaseReservationCommand::class.java

        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .andThenAPublished(InventoryReservedEvent("ITEM-1", correlationId, 2))
            .whenPublishingA(InventoryReservationFailedEvent("ITEM-2", correlationId, "insufficient stock"))

        assertEquals(
            1,
            sent.filterIsInstance<FailOrderCommand>().size,
            "a failed release must not prevent FailOrderCommand, got: $sent",
        )
        assertEquals(1.0, counter("release"), "the synchronous failure must be counted like an async one")
    }

    @Test
    fun `records the command-failure metric under the documented name and stage tags`() {
        // CLAUDE.md requires these names identical across all eight variant branches, and a typo
        // would otherwise pass every other test in this class.
        failingItemId = "ITEM-1"

        fixture.givenNoPriorActivity()
            .whenPublishingA(OrderCreatedEvent(orderId, "user-1", items, correlationId))

        assertEquals(1.0, counter("reserve"))
    }

    @Test
    fun `the out-of-stock path's own OrderFailedEvent does not re-enter the saga`() {
        // The out-of-stock branch calls SagaLifecycle.end() inline once every line has settled, so
        // by the time its FailOrderCommand's OrderFailedEvent is read back, the saga and its
        // orderId association are already gone. If that inline end() were ever removed, the event
        // would land on the external-failure handler and double-count the lifecycle.
        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .andThenAPublished(InventoryReservedEvent("ITEM-1", correlationId, 2))
            .whenPublishingA(InventoryReservationFailedEvent("ITEM-2", correlationId, "insufficient stock"))
            .expectActiveSagas(0)

        assertEquals(1.0, outcome("failed"), "out-of-stock must be tagged failed")
        assertEquals(0.0, outcome("command_failed"), "out-of-stock is not a command failure")
        val releasesAfterRejection = sent.filterIsInstance<ReleaseReservationCommand>().size

        // Replaying the event the saga itself caused must change nothing.
        fixture.whenPublishingA(OrderFailedEvent(orderId, "insufficient stock"))
            .expectActiveSagas(0)

        assertEquals(1.0, outcome("failed"), "the lifecycle must not be recorded twice")
        assertEquals(0.0, outcome("command_failed"), "the handler must not fire for an already-ended saga")
        assertEquals(
            releasesAfterRejection,
            sent.filterIsInstance<ReleaseReservationCommand>().size,
            "compensation must not run a second time, got: $sent",
        )
    }

    @Test
    fun `counts a FailOrderCommand the aggregate ignored because the order was already terminal`() {
        // The future completes normally, so without inspecting the result this is
        // indistinguishable from success — while no OrderFailedEvent exists, nothing else would
        // report that the order never reached a terminal status.
        failOrderApplied = false
        val singleLine = listOf(OrderItem("ITEM-1", 2))

        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", singleLine, correlationId))
            .whenPublishingA(InventoryReservationFailedEvent("ITEM-1", correlationId, "insufficient stock"))

        assertEquals(1, sent.filterIsInstance<FailOrderCommand>().size)
        assertEquals(1.0, counter("fail-order-ignored"), "an ignored FailOrderCommand must be visible")
        assertEquals(0.0, counter("fail-order"), "it did not fail — it was ignored; the two are different")
    }

    private fun counter(stage: String): Double =
        meters.counter("saga.command.failed", "stage", stage).count()

    private fun outcome(name: String): Double =
        meters.counter("saga.completed", "outcome", name).count()
}
