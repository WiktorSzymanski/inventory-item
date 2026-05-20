package pl.szymanski.wiktor.domain

import java.time.Instant
import java.util.UUID

data class InventoryCreatedEvent(
    val id: String,
    val quantity: Int,
    val correlationId: UUID,
    val createdAt: Instant = Instant.now(),
)

data class InventoryReservedEvent(
    val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID,
    val createdAt: Instant = Instant.now(),
)
