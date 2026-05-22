package pl.szymanski.wiktor.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.ReservationForThatItemAlreadyExistsException
import java.util.UUID

@Table("inventory_state")
data class InventoryItem(
    @Id
    @Column("item_id")
    val id: String,
    val availableQty: Int,
    val reservations: Map<String, Int> = mapOf(),
    @Version
    val version: Long = 0L,
) {
    fun reserve(reservationId: String, quantity: Int, correlationId: UUID): Pair<InventoryItem, InventoryReservedEvent> {
        if (quantity <= 0) {
            throw IllegalArgumentException("Quantity must be greater than 0")
        }

        if (quantity > availableQty) {
            throw InsufficientStockException(
                "Not enough stock of item $id (availableQty: $availableQty)" +
                        "for reservation $reservationId (requested: $quantity)"
            )
        }

        if (reservations.containsKey(reservationId)) {
            throw ReservationForThatItemAlreadyExistsException("Reservation $reservationId already exists for item $id")
        }

        return Pair(
            copy(
                availableQty = availableQty - quantity,
                reservations = reservations + (reservationId to quantity),
            ), InventoryReservedEvent(id, reservationId, quantity, correlationId))
    }

    companion object {
        fun create(id: String, availableQty: Int, correlationId: UUID): Pair<InventoryItem, InventoryCreatedEvent> =
            Pair(
                InventoryItem(id, availableQty),
                InventoryCreatedEvent(id, availableQty, correlationId)
            )
    }
}
