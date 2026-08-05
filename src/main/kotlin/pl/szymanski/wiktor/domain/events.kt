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
    val additionalBytes: String = "",
    // Artificial per-reserve cost, in milliseconds. Replayed into aggregate state, so it applies
    // to every later reserve of this item. Mirrors the TO branch's inventory_state.reserve_delay_ms.
    val reserveDelayMs: Int = 0,
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
