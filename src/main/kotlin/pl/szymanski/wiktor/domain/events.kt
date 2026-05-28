package pl.szymanski.wiktor.domain

import java.util.UUID

abstract class InventoryEvent(
    open val id: String,
    open val correlationId: UUID,
)

data class InventoryCreatedEvent(
    override val id: String,
    override val correlationId: UUID,
    val quantity: Int,
) : InventoryEvent(id, correlationId)

data class InventoryReservedEvent(
    override val id: String,
    override val correlationId: UUID,
    val quantity: Int,
) : InventoryEvent(id, correlationId)

data class InventoryReservationReleasedEvent(
    override val id: String,
    override val correlationId: UUID,
    val quantity: Int,
) : InventoryEvent(id, correlationId)

data class OrderItem(val itemId: String, val quantity: Int)

data class InventoryReservationFailedEvent(
    override val id: String,
    override val correlationId: UUID,
    val reason: String,
) : InventoryEvent(id, correlationId)

data class OrderCreatedEvent(val orderId: String, val userId: String, val items: List<OrderItem>, val correlationId: UUID)
data class OrderCompletedEvent(val orderId: String)
data class OrderFailedEvent(val orderId: String, val reason: String)
