package pl.szymanski.wiktor.domain

import org.axonframework.commandhandling.CommandHandler
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.modelling.command.AggregateIdentifier
import org.axonframework.modelling.command.AggregateLifecycle
import org.axonframework.spring.stereotype.Aggregate
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.ReservationForThatItemAlreadyExistsException
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.ReserveItemCommand

@Aggregate
class InventoryItem {

    @AggregateIdentifier
    private lateinit var id: String
    private var availableQty = 0
    private val reservations = mutableMapOf<String, Int>()

    constructor()

    @CommandHandler
    constructor(command: CreateItemCommand) {
        AggregateLifecycle.apply(InventoryCreatedEvent(command.id, command.correlationId, command.availableQty))
    }

    @CommandHandler
    fun handle(command: ReserveItemCommand) {
        if (command.quantity > availableQty) {
            throw InsufficientStockException(
                "Not enough stock of item $id (availableQty: $availableQty) " +
                        "for reservation ${command.reservationId} (requested: ${command.quantity})"
            )
        }
        if (reservations.containsKey(command.reservationId)) {
            throw ReservationForThatItemAlreadyExistsException(
                "Reservation ${command.reservationId} already exists for item $id"
            )
        }
        AggregateLifecycle.apply(
            InventoryReservedEvent(command.id, command.correlationId, command.reservationId, command.quantity)
        )
    }

    @EventSourcingHandler
    fun on(event: InventoryCreatedEvent) {
        id = event.id
        availableQty = event.quantity
    }

    @EventSourcingHandler
    fun on(event: InventoryReservedEvent) {
        availableQty -= event.quantity
        reservations[event.reservationId] = event.quantity
    }
}
