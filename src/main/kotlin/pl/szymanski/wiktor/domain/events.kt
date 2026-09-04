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

/**
 * A reserve command that never reached the item aggregate — dispatch failed, or the retries ran
 * out. Published by the saga rather than applied by an aggregate: nothing happened to inventory,
 * and the aggregate that would have appended it is the one that could not be reached.
 *
 * It exists because the saga has to hear about that line. A command failure surfaces on a
 * command-pool thread, outside saga scope, where SagaLifecycle cannot be touched; without an event
 * carrying the correlationId back into the saga, the line never settles and the saga waits for it
 * forever. No projection handles it — it is a signal between the saga's two halves.
 */
data class SagaReserveAbandonedEvent(
    override val id: String,
    override val correlationId: UUID,
    val reason: String,
) : InventoryEvent(id, correlationId)
