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
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderFailedEvent
import pl.szymanski.wiktor.domain.OrderItem
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

    /** Reserve commands for this item id complete exceptionally; everything else succeeds. */
    private var failingItemId: String? = null

    private val orderId = "ORDER-1"
    private val correlationId = UUID.randomUUID()
    private val items = listOf(OrderItem("ITEM-1", 2), OrderItem("ITEM-2", 3))

    @BeforeEach
    fun setUp() {
        sent.clear()
        failingItemId = null

        val captured = slot<Any>()
        every { gateway.send<Any?>(capture(captured)) } answers {
            val command = captured.captured
            sent.add(command)
            // NOTE: SagaReserveItemCommand's aggregate id property is `id`, not `itemId`.
            // Only OrderItem uses `itemId`.
            val shouldFail = command is SagaReserveItemCommand && command.id == failingItemId
            if (shouldFail) CompletableFuture.failedFuture<Any?>(RuntimeException("injected append failure"))
            else CompletableFuture.completedFuture<Any?>(null)
        }

        fixture = SagaTestFixture(OrderReservationSaga::class.java)
        fixture.registerResourceInjector(ResourceInjector { saga -> inject(saga) })
    }

    private fun inject(saga: Any) {
        setField(saga, "commandGateway", gateway)
        setField(saga, "commandExecutor", Executor { it.run() })
        setField(saga, "meterRegistry", SimpleMeterRegistry())
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
    }
}
