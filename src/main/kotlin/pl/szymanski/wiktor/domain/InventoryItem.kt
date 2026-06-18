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

        return ReserveResult(
            updatedItem = copy(availableQty = availableQty - quantity),
            reservation = Reservation(itemId = id, reservationId = reservationId, quantity = quantity),
            event = InventoryReservedEvent(id, reservationId, quantity, correlationId, clock.instant()),
        )
    }

    companion object {
        fun create(id: String, availableQty: Int, correlationId: UUID, clock: Clock): Pair<InventoryItem, InventoryCreatedEvent> =
            Pair(
                InventoryItem(id, availableQty),
                InventoryCreatedEvent(id, availableQty, correlationId, clock.instant())
            )
    }
}
