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
    // Artificial per-reserve cost the item was created with. Carried on the event so the outbox
    // record describes the item fully, and so the payload matches the ES branch's event.
    val reserveDelayMs: Int = 0,
)

data class InventoryReservedEvent(
    val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID,
    val createdAt: Instant,
)

data class ReservedItem(val itemId: String, val quantity: Int)

// Emitted when an order is admitted (PENDING). Carries the requested lines so the reservation
// listener is self-contained: it can reserve every item from the event payload alone, without
// re-reading the order (there is no order line-item table). Mirrors the ES branch's stored
// OrderCreated event.
data class OrderCreatedEvent(
    val orderId: String,
    val userId: String,
    val items: List<ReservedItem>,
    val correlationId: UUID,
    val createdAt: Instant,
    // Filler to inflate the serialized payload for benchmarking; mirrors InventoryCreatedEvent
    // so TO and ES can be load-tested at equal payload sizes.
    val additionalBytes: String = "",
)

// Terminal order events mirror the ES branch's OrderCompletedEvent/OrderFailedEvent payloads,
// plus the TO-mandatory createdAt (ES gets the timestamp from event-store metadata instead).
data class OrderCompletedEvent(
    val orderId: String,
    val createdAt: Instant,
)

data class OrderFailedEvent(
    val orderId: String,
    val reason: String,
    val createdAt: Instant,
)
