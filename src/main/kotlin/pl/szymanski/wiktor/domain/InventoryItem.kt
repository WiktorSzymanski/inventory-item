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
    // Benchmark padding, stored on the row rather than only on the creation event, so every
    // reserve's read-modify-write carries it. The TO counterpart to ES rehydrating and
    // snapshotting the same bytes on every aggregate load.
    val additionalBytes: String = "",
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
        fun create(
            id: String,
            availableQty: Int,
            correlationId: UUID,
            clock: Clock,
            additionalBytesSize: Int = 0,
        ): Pair<InventoryItem, InventoryCreatedEvent> {
            val additionalBytes = if (additionalBytesSize > 0) "x".repeat(additionalBytesSize) else ""
            return Pair(
                InventoryItem(
                    id = id,
                    availableQty = availableQty,
                    additionalBytes = additionalBytes,
                ),
                InventoryCreatedEvent(id, availableQty, correlationId, clock.instant(), additionalBytes)
            )
        }
    }
}
