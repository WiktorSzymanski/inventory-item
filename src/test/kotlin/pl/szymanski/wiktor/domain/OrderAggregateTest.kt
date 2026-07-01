package pl.szymanski.wiktor.domain

import org.axonframework.test.aggregate.AggregateTestFixture
import org.axonframework.test.aggregate.FixtureConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.szymanski.wiktor.service.command.CompleteOrderCommand
import pl.szymanski.wiktor.service.command.FailOrderCommand
import java.util.UUID

class OrderAggregateTest {

    private lateinit var fixture: FixtureConfiguration<OrderAggregate>

    private val orderId = "ORDER-1"
    private val correlationId = UUID.randomUUID()
    private val items = listOf(OrderItem("ITEM-1", 5))

    @BeforeEach
    fun setUp() {
        fixture = AggregateTestFixture(OrderAggregate::class.java)
    }

    @Test
    fun `completes a pending order`() {
        fixture.given(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .`when`(CompleteOrderCommand(orderId))
            .expectEvents(OrderCompletedEvent(orderId))
    }

    @Test
    fun `fails a pending order`() {
        fixture.given(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .`when`(FailOrderCommand(orderId, "Insufficient stock"))
            .expectEvents(OrderFailedEvent(orderId, "Insufficient stock"))
    }

    @Test
    fun `ignores a duplicate complete command`() {
        fixture.given(
            OrderCreatedEvent(orderId, "user-1", items, correlationId),
            OrderCompletedEvent(orderId),
        )
            .`when`(CompleteOrderCommand(orderId))
            .expectNoEvents()
    }

    @Test
    fun `does not fail an already completed order`() {
        fixture.given(
            OrderCreatedEvent(orderId, "user-1", items, correlationId),
            OrderCompletedEvent(orderId),
        )
            .`when`(FailOrderCommand(orderId, "too late"))
            .expectNoEvents()
    }

    @Test
    fun `does not complete an already failed order`() {
        fixture.given(
            OrderCreatedEvent(orderId, "user-1", items, correlationId),
            OrderFailedEvent(orderId, "Insufficient stock"),
        )
            .`when`(CompleteOrderCommand(orderId))
            .expectNoEvents()
    }
}
