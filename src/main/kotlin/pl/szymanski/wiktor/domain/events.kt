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

// `orderId` and `lineIndex` are what make this event a SAGA STEP RESULT rather than only a record
// that stock moved. The saga correlates on orderId (there is no association store to look one up
// in) and resumes at lineIndex + 1, so both have to survive the round trip through
// event_publication — an in-memory index would not survive a redelivery after a crash.
//
// `reservationId` is equal to `orderId` today, exactly as it was on TO-3, because a reservation is
// keyed (item_id, reservation_id). It is kept as its own field because it is what the reservations
// TABLE is written with, and the two would have to be told apart the moment a reservation key ever
// stops being the order id.
data class InventoryReservedEvent(
    val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID,
    val createdAt: Instant,
    val orderId: String,
    val lineIndex: Int,
)

// The saga's failure signal, and the reason a failed reserve is not simply an exception that dies
// on a worker thread: it has to reach the saga through the outbox, in a transaction of its own,
// because the reserve transaction that produced it has already rolled back. Mirrors the ES branch's
// InventoryReservationFailedEvent, plus the TO-mandatory createdAt and the step coordinates.
data class InventoryReservationFailedEvent(
    val id: String,
    val orderId: String,
    val lineIndex: Int,
    val reason: String,
    val correlationId: UUID,
    val createdAt: Instant,
)

// One compensating step. Emitted per released line rather than once per order, so compensation is
// resumable at exactly the granularity it is performed at.
data class InventoryReservationReleasedEvent(
    val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID,
    val createdAt: Instant,
    val orderId: String,
    val lineIndex: Int,
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
