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
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
// cache + snapshotTrigger are declared HERE rather than via a second manual
// configureAggregate(...): that produced two registrations for the same aggregate, whose
// command handlers both subscribed and were resolved by last-wins, leaving the effective
// cache dependent on Spring init order and Configuration.repository() pointing at the
// UNCACHED one. ES-3 is the cached variant — that must not be an accident of ordering.
@Aggregate(snapshotTriggerDefinition = "inventorySnapshotTrigger", cache = "inventoryItemCache")
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
