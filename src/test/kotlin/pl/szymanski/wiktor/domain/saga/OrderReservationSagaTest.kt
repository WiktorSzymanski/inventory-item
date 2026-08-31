package pl.szymanski.wiktor.domain.saga

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.axonframework.commandhandling.gateway.CommandGateway
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

    /**
     * Which executor each entry in [sent] was submitted through — parallel to it by index.
     *
     * The whole ES-4-bounded branch rests on this routing and NOTHING in the command stream
     * reveals it: the same SagaReserveItemCommand is dispatched either way. See
     * [pl.szymanski.wiktor.config.SagaIntakeGate].
     */
    private val lanes = mutableListOf<String>()

    /** Set by [laneExecutor] for the duration of a task, so the gateway mock can tag what it sees. */
    private var currentLane = "direct"

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

    private val orderId = "ORDER-1"
    private val correlationId = UUID.randomUUID()
    private val items = listOf(OrderItem("ITEM-1", 2), OrderItem("ITEM-2", 3))

    @BeforeEach
    fun setUp() {
        sent.clear()
        lanes.clear()
        currentLane = "direct"
        failingItemId = null
        failComplete = false
        syncThrowFor = null
        failOrderApplied = true
        meters.clear()

        val captured = slot<Any>()
        every { gateway.send<Any?>(capture(captured)) } answers {
            val command = captured.captured
            sent.add(command)
            lanes.add(currentLane)
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
        setField(saga, "commandExecutor", laneExecutor("command"))
        setField(saga, "intakeExecutor", laneExecutor("intake"))
        setField(saga, "meterRegistry", meters)
    }

    /** Runs inline, as before, but records which lane the work went through. */
    private fun laneExecutor(name: String) = Executor { task ->
        val previous = currentLane
        currentLane = name
        try {
            task.run()
        } finally {
            currentLane = previous
        }
    }

    /** The lane the first command of [type] was submitted through. */
    private fun laneOfFirst(type: Class<*>): String {
        val index = sent.indexOfFirst { type.isInstance(it) }
        assertTrue(index >= 0, "no ${type.simpleName} was sent, got: $sent")
        return lanes[index]
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = OrderReservationSaga::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    @Test
    fun `fails the order when the first reservation command exhausts its retries`() {
        failingItemId = "ITEM-1"

        fixture.givenNoPriorActivity()
            .whenPublishingA(OrderCreatedEvent(orderId, "user-1", items, correlationId))

        val failCommands = sent.filterIsInstance<FailOrderCommand>()
        assertEquals(1, failCommands.size, "expected exactly one FailOrderCommand, got: $sent")
        assertEquals(orderId, failCommands.single().orderId)
        assertTrue(
            sent.filterIsInstance<ReleaseReservationCommand>().isEmpty(),
            "nothing was reserved yet, so nothing should be released",
        )
    }

    @Test
    fun `releases only the already-reserved lines when a later reservation fails`() {
        failingItemId = "ITEM-2"

        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .whenPublishingA(InventoryReservedEvent("ITEM-1", correlationId, 2))

        val releases = sent.filterIsInstance<ReleaseReservationCommand>()
        assertEquals(1, releases.size, "expected one release for ITEM-1 only, got: $sent")
        assertEquals("ITEM-1", releases.single().id)
        assertEquals(2, releases.single().quantity)
        assertEquals(1, sent.filterIsInstance<FailOrderCommand>().size)
    }

    @Test
    fun `ends the saga when the order is failed externally`() {
        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .whenPublishingA(OrderFailedEvent(orderId, "reserve command failed"))
            .expectActiveSagas(0)

        // Only this path can produce the tag: it needs the FailOrderCommand's OrderFailedEvent to
        // round-trip back through the processor, which a mocked gateway never does on its own.
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
        // releaseAll runs BEFORE sendFailOrder inside abandon(). If a synchronous throw escapes
        // it, the terminal disposition is skipped and the order is stranded PENDING with no
        // counter and no log — the original defect, reintroduced through a narrower door.
        failingItemId = "ITEM-2"
        syncThrowFor = ReleaseReservationCommand::class.java

        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .whenPublishingA(InventoryReservedEvent("ITEM-1", correlationId, 2))

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
        // The out-of-stock branch calls SagaLifecycle.end() inline, so by the time its
        // FailOrderCommand's OrderFailedEvent is read back, the saga and its orderId
        // association are already gone. If that inline end() were ever removed, the event
        // would land on @EndSaga and double-count the lifecycle under a second outcome.
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
        assertEquals(0.0, outcome("command_failed"), "@EndSaga must not fire for an already-ended saga")
        assertEquals(
            releasesAfterRejection,
            sent.filterIsInstance<ReleaseReservationCommand>().size,
            "compensation must not run a second time, got: $sent",
        )
    }

    @Test
    fun `counts a FailOrderCommand the aggregate ignored because the order was already terminal`() {
        // The future completes normally, so without inspecting the result this is
        // indistinguishable from success — while no OrderFailedEvent exists, @EndSaga never
        // fires, and the saga_entry row leaks. Unreachable today; a second sender of
        // FailOrderCommand would make it live.
        failingItemId = "ITEM-1"
        failOrderApplied = false

        fixture.givenNoPriorActivity()
            .whenPublishingA(OrderCreatedEvent(orderId, "user-1", items, correlationId))

        assertEquals(1, sent.filterIsInstance<FailOrderCommand>().size)
        assertEquals(1.0, counter("fail-order-ignored"), "an ignored FailOrderCommand must be visible")
        assertEquals(0.0, counter("fail-order"), "it did not fail — it was ignored; the two are different")
    }

    @Test
    fun `only the first reservation of a new order passes through the intake gate`() {
        // The property the branch exists for: incoming sagas are bounded, sagas already in
        // progress are not. Routing a continuation through the gate would put an in-flight saga
        // back behind the arrivals the gate is holding — the starvation this branch removes,
        // reintroduced from the inside. Routing the start AROUND it removes the bound entirely,
        // and both mistakes are invisible in the command stream.
        fixture.givenNoPriorActivity()
            .whenPublishingA(OrderCreatedEvent(orderId, "user-1", items, correlationId))

        assertEquals(1, sent.size, "the first reserve is the only command so far, got: $sent")
        assertEquals("intake", laneOfFirst(SagaReserveItemCommand::class.java))

        fixture.whenPublishingA(InventoryReservedEvent("ITEM-1", correlationId, 2))

        assertEquals(2, sent.size, "the second reserve should now have been sent, got: $sent")
        assertEquals("command", lanes[1], "a continuation must bypass the gate")
    }

    @Test
    fun `completion, failure and compensation all bypass the intake gate`() {
        // Already-admitted work. A terminal disposition queued behind the arrivals the gate is
        // holding would keep an order PENDING for exactly as long as the bound is saturated, and
        // abandon()'s own RejectedExecutionException fallback assumes the ungated pool's
        // never-rejecting contract.
        val singleLine = listOf(OrderItem("ITEM-1", 2))

        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", singleLine, correlationId))
            .whenPublishingA(InventoryReservedEvent("ITEM-1", correlationId, 2))
        assertEquals("command", laneOfFirst(CompleteOrderCommand::class.java))

        setUp()
        failingItemId = "ITEM-2"
        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .whenPublishingA(InventoryReservedEvent("ITEM-1", correlationId, 2))
        assertEquals("command", laneOfFirst(ReleaseReservationCommand::class.java))
        assertEquals("command", laneOfFirst(FailOrderCommand::class.java))
    }

    private fun counter(stage: String): Double =
        meters.counter("saga.command.failed", "stage", stage).count()

    private fun outcome(name: String): Double =
        meters.counter("saga.completed", "outcome", name).count()
}
