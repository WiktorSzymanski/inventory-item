package pl.szymanski.wiktor.service.command

import org.axonframework.modelling.command.TargetAggregateIdentifier
import pl.szymanski.wiktor.domain.OrderItem
import java.util.UUID

data class CreateOrderCommand(
    @TargetAggregateIdentifier val orderId: String,
    val userId: String,
    val items: List<OrderItem>,
    val correlationId: UUID = UUID.randomUUID(),
)

data class CompleteOrderCommand(
    @TargetAggregateIdentifier val orderId: String,
)

data class FailOrderCommand(
    @TargetAggregateIdentifier val orderId: String,
    val reason: String,
)

data class ReleaseReservationCommand(
    @TargetAggregateIdentifier val id: String,
    val reservationId: String,
    val correlationId: UUID = UUID.randomUUID(),
)

data class CreateOrderReservationCommand(
    val userId: String,
    val items: List<OrderItem>,
    val correlationId: UUID = UUID.randomUUID(),
)

data class SagaReserveItemCommand(
    @TargetAggregateIdentifier val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID = UUID.randomUUID(),
)
