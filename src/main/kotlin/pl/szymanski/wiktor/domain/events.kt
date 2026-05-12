package pl.szymanski.wiktor.domain

import java.util.UUID

abstract class InventoryEvent(
    open val id: String,
    open val correlationId: UUID,
    open val revision: Long,
)

data class InventoryCreatedEvent(
    override val id: String,
    override val correlationId: UUID,
    override val revision: Long,
    val quantity: Int,
) : InventoryEvent(id, correlationId, revision)

data class InventoryReservedEvent(
    override val id: String,
    override val correlationId: UUID,
    override val revision: Long,
    val reservationId: String,
    val quantity: Int,
) : InventoryEvent(id, correlationId, revision)
