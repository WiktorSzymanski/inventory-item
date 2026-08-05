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
// ES-4: routed to the cached copy-on-write "inventoryItemRepository" bean, which locks with
// Axon's default PessimisticLockFactory; snapshot trigger is configured on that repository, not here.
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Aggregate(repository = "inventoryItemRepository")
class InventoryItem {

    @AggregateIdentifier
    private lateinit var id: String
    private var availableQty = 0
    private var additionalBytes: String = ""

    // Artificial per-reserve cost, in milliseconds, fixed at creation. Stands in for expensive
    // aggregate logic (pricing, eligibility, allocation) so the variants can be compared under
    // something more than a subtraction. The counterpart to additionalBytes, which inflates the
    // payload but not the time a reserve takes. Mirrors the TO branch's field of the same name.
    private var reserveDelayMs: Int = 0

    constructor()

    @CommandHandler
    constructor(command: CreateItemCommand) {
        val additionalBytes = if (command.additionalBytesSize > 0) "x".repeat(command.additionalBytesSize) else ""
        AggregateLifecycle.apply(
            InventoryCreatedEvent(command.id, command.correlationId, command.availableQty, additionalBytes, command.reserveDelayMs)
        )
    }

    @EventSourcingHandler
    fun on(event: InventoryCreatedEvent) {
        id = event.id
        availableQty = event.quantity
        additionalBytes = event.additionalBytes
        reserveDelayMs = event.reserveDelayMs
    }

    @CommandHandler
    fun handle(command: SagaReserveItemCommand) {
        if (command.quantity > availableQty) {
            AggregateLifecycle.apply(
                InventoryReservationFailedEvent(id, command.correlationId, "Insufficient stock: available=$availableQty requested=${command.quantity}")
            )
            return
        }

        // Paid only once the reserve is known to succeed, and inside the command handler rather
        // than the event-sourcing handler: the latter runs on every replay and snapshot load, which
        // would make the delay a startup cost instead of a per-reserve one. The aggregate's
        // pessimistic lock is held throughout — that is the point, it models slow domain logic.
        if (reserveDelayMs > 0) {
            Thread.sleep(reserveDelayMs.toLong())
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
