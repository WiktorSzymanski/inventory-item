package pl.szymanski.wiktor.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import pl.szymanski.wiktor.exception.InsufficientStockException
import java.time.Clock
import java.util.UUID

data class ReserveResult(
    val updatedItem: InventoryItem,
    val reservation: Reservation,
    val event: InventoryReservedEvent,
)

@Table("inventory_state")
data class InventoryItem(
    @Id
    @Column("item_id")
    val id: String,
    val availableQty: Int,
    // Artificial per-reserve cost, in milliseconds, fixed at creation. Stands in for expensive
    // aggregate logic (pricing, eligibility, allocation) so the variants can be compared under
    // something more than a subtraction. Mirrors the ES branch's aggregate field of the same name.
    val reserveDelayMs: Int = 0,
    @Version
    val version: Long = 0L,
) {
    fun reserve(
        reservationId: String,
        quantity: Int,
        correlationId: UUID,
        clock: Clock,
    ): ReserveResult {
        if (quantity <= 0) {
            throw IllegalArgumentException("Quantity must be greater than 0")
        }

        if (quantity > availableQty) {
            throw InsufficientStockException(
                "Not enough stock of item $id (availableQty: $availableQty)" +
                        "for reservation $reservationId (requested: $quantity)"
            )
        }

        // Paid only once the reserve is known to succeed, and inside the caller's transaction:
        // the inventory_state row lock is already held and stays held for the duration. That is the
        // point — it is what makes this a model of slow aggregate logic rather than of slow IO.
        if (reserveDelayMs > 0) {
            Thread.sleep(reserveDelayMs.toLong())
        }

        return ReserveResult(
            updatedItem = copy(availableQty = availableQty - quantity),
            reservation = Reservation(itemId = id, reservationId = reservationId, quantity = quantity),
            event = InventoryReservedEvent(id, reservationId, quantity, correlationId, clock.instant()),
        )
    }

    companion object {
        fun create(
            id: String,
            availableQty: Int,
            correlationId: UUID,
            clock: Clock,
            additionalBytesSize: Int = 0,
            reserveDelayMs: Int = 0,
        ): Pair<InventoryItem, InventoryCreatedEvent> {
            val additionalBytes = if (additionalBytesSize > 0) "x".repeat(additionalBytesSize) else ""
            return Pair(
                InventoryItem(id = id, availableQty = availableQty, reserveDelayMs = reserveDelayMs),
                InventoryCreatedEvent(id, availableQty, correlationId, clock.instant(), additionalBytes, reserveDelayMs)
            )
        }
    }
}
