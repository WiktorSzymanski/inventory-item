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
    val reservationId: String,
    val quantity: Int,
) : InventoryEvent(id, correlationId)
