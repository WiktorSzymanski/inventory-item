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

/**
 * The inverse of [ReserveResult]. There is no reservation to hand back — compensation DELETES the
 * row this item's reserve inserted — so the caller gets the restored item and the event announcing
 * it, and deletes by (itemId, reservationId).
 */
data class ReleaseResult(
    val updatedItem: InventoryItem,
    val event: InventoryReservationReleasedEvent,
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
    // Benchmark padding, stored on the row rather than only on the creation event, so every
    // reserve's read-modify-write carries it. The TO counterpart to ES rehydrating and
    // snapshotting the same bytes on every aggregate load.
    val additionalBytes: String = "",
    @Version
    val version: Long = 0L,
) {
    fun reserve(
        reservationId: String,
        orderId: String,
        lineIndex: Int,
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

        // Paid only once the reserve is known to succeed. It used to be paid inside the caller's
        // transaction, with the inventory_state row lock already held and staying held for the
        // duration; the split reserve path runs this in its modify phase, so the sleep now holds
        // neither a lock nor a Hikari connection. Either way it is a model of slow aggregate logic
        // rather than of slow IO — what moved is who else has to wait for it.
        //
        // On this branch it is paid ONCE PER LINE and once per RETRY of a line, where TO-3 pays it
        // once per line inside one order-wide attempt. Same total per successful order, a different
        // amount per conflict: TO-3 re-sleeps every line of the order when any one of them
        // conflicts, this re-sleeps only the line that did.
        // See ReserveOrderItemCommandHandler for the phase boundaries.
        if (reserveDelayMs > 0) {
            Thread.sleep(reserveDelayMs.toLong())
        }

        return ReserveResult(
            updatedItem = copy(availableQty = availableQty - quantity),
            reservation = Reservation(itemId = id, reservationId = reservationId, quantity = quantity),
            event = InventoryReservedEvent(
                id, reservationId, quantity, correlationId, clock.instant(), orderId, lineIndex,
            ),
        )
    }

    /**
     * Compensation for one previously reserved line: puts the quantity back.
     *
     * No `reserveDelayMs` here, and no stock check — a release restores state this item itself
     * produced, so there is nothing to reject and nothing to model as expensive. Mirrors the ES
     * branch's `ReleaseReservationCommand`, which likewise only applies the released event.
     */
    fun release(
        reservationId: String,
        orderId: String,
        lineIndex: Int,
        quantity: Int,
        correlationId: UUID,
        clock: Clock,
    ): ReleaseResult = ReleaseResult(
        updatedItem = copy(availableQty = availableQty + quantity),
        event = InventoryReservationReleasedEvent(
            id, reservationId, quantity, correlationId, clock.instant(), orderId, lineIndex,
        ),
    )

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
                InventoryItem(
                    id = id,
                    availableQty = availableQty,
                    reserveDelayMs = reserveDelayMs,
                    additionalBytes = additionalBytes,
                ),
                InventoryCreatedEvent(id, availableQty, correlationId, clock.instant(), additionalBytes, reserveDelayMs)
            )
        }
    }
}
