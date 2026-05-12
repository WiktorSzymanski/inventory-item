package pl.szymanski.wiktor.domain

import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.ReservationForThatItemAlreadyExistsException
import java.util.UUID

data class InventoryItem(
    val id: String,
    val availableQty: Int,
    val reservations: Map<String, Int> = mapOf(),
    val revision: Long = NO_STREAM,
) {
    fun reserve(reservationId: String, quantity: Int, correlationId: UUID): Pair<InventoryItem, InventoryReservedEvent> {
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
            ), InventoryReservedEvent(id, correlationId, revision, reservationId, quantity))
    }

    companion object {
        const val NO_STREAM = -1L

        fun create(id: String, availableQty: Int, correlationId: UUID): Pair<InventoryItem, InventoryCreatedEvent> =
            Pair(
                InventoryItem(id, availableQty),
                InventoryCreatedEvent(id, correlationId, NO_STREAM, availableQty)
            )

        fun reconstruct(events: List<InventoryEvent>): InventoryItem {
            var id = ""
            var availableQty = 0
            var reservations = emptyMap<String, Int>()

            for (event in events) {
                when (event) {
                    is InventoryCreatedEvent -> {
                        id = event.id
                        availableQty = event.quantity
                    }
                    is InventoryReservedEvent -> {
                        availableQty -= event.quantity
                        reservations = reservations + (event.reservationId to event.quantity)
                    }
                }
            }
            return InventoryItem(id, availableQty, reservations, events.lastOrNull()?.revision ?: NO_STREAM)
        }
    }
}
