package pl.szymanski.wiktor.domain

import java.time.Instant
import java.util.UUID

// createdAt has no default on purpose: it must be stamped explicitly at the event's
// production point with the shared Clock, mirroring Axon's @Timestamp (set at apply()).
data class InventoryCreatedEvent(
    val id: String,
    val quantity: Int,
    val correlationId: UUID,
    val createdAt: Instant,
    // Filler to inflate the serialized event payload for benchmarking; mirrors the ES branch's
    // additionalBytes so TO and ES can be load-tested at equal payload sizes.
    val additionalBytes: String = "",
)

data class InventoryReservedEvent(
    val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID,
    val createdAt: Instant,
)

data class ReservedItem(val itemId: String, val quantity: Int)

data class OrderReservationCreatedEvent(
    val orderId: String,
    val userId: String,
    val items: List<ReservedItem>,
    val correlationId: UUID,
    val createdAt: Instant,
)
