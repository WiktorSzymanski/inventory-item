package pl.szymanski.wiktor.domain

import com.fasterxml.jackson.annotation.JsonAutoDetect
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.modelling.command.AggregateIdentifier
import org.axonframework.modelling.command.AggregateLifecycle
import org.axonframework.spring.stereotype.Aggregate
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.ReleaseReservationCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand

// ES-2: Jackson cannot access private Kotlin fields by default — field visibility lets it serialize all aggregate state into the snapshot
// ES-3-optimistic: routed to the lock-free copy-on-write "inventoryItemRepository" bean (NullLockFactory);
// snapshot trigger is configured on that repository, not here.
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Aggregate(repository = "inventoryItemRepository")
class InventoryItem {

    @AggregateIdentifier
    private lateinit var id: String
    private var availableQty = 0
    private var additionalBytes: String = ""

    constructor()

    @CommandHandler
    constructor(command: CreateItemCommand) {
        val additionalBytes = if (command.additionalBytesSize > 0) "x".repeat(command.additionalBytesSize) else ""
        AggregateLifecycle.apply(
            InventoryCreatedEvent(command.id, command.correlationId, command.availableQty, additionalBytes)
        )
    }

    @EventSourcingHandler
    fun on(event: InventoryCreatedEvent) {
        id = event.id
        availableQty = event.quantity
        additionalBytes = event.additionalBytes
    }

    @CommandHandler
    fun handle(command: SagaReserveItemCommand) {
        if (command.quantity > availableQty) {
            AggregateLifecycle.apply(
                InventoryReservationFailedEvent(id, command.correlationId, "Insufficient stock: available=$availableQty requested=${command.quantity}")
            )
            return
        }
        AggregateLifecycle.apply(InventoryReservedEvent(id, command.correlationId, command.quantity))
    }

    @EventSourcingHandler
    fun on(event: InventoryReservationFailedEvent) { /* no state change on failure */ }

    @CommandHandler
    fun handle(command: ReleaseReservationCommand) {
        AggregateLifecycle.apply(InventoryReservationReleasedEvent(id, command.correlationId, command.quantity))
    }

    @EventSourcingHandler
    fun on(event: InventoryReservedEvent) {
        availableQty -= event.quantity
    }

    @EventSourcingHandler
    fun on(event: InventoryReservationReleasedEvent) {
        availableQty += event.quantity
    }
}
