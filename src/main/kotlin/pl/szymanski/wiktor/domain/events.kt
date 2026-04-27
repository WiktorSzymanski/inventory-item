package pl.szymanski.wiktor.domain

import java.util.UUID

data class InventoryCreatedEvent(
    val id: String,
    val quantity: Int,
    val correlationId: UUID,
)

data class InventoryReservedEvent(
    val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID,
)
