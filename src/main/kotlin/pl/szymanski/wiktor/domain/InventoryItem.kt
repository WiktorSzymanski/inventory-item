package pl.szymanski.wiktor.domain

import org.axonframework.commandhandling.CommandHandler
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.modelling.command.AggregateIdentifier
import org.axonframework.modelling.command.AggregateLifecycle
import org.axonframework.spring.stereotype.Aggregate
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.ReleaseReservationCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand

// ES-1-NullLock: routed to the "inventoryItemRepository" bean, which is built LOCK-FREE with
// NullLockFactory. Naming the repository here is not decoration: without this attribute the bean is
// ignored and the aggregate keeps Axon's default pessimistic lock. Equally, nothing may register a
// SECOND configuration for this type (`configurer.configureAggregate(...)`) — ES-2 did, and its
// competing AggregateAnnotationCommandHandler won the command-bus subscription, so commands went to
// a stock pessimistic repository while Configuration.repository() still returned the lock-free bean.
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
        // would make the delay a startup cost instead of a per-reserve one.
        //
        // NOTE the lever means something different here than on TO or on a lock-holding ES branch.
        // This aggregate is loaded LOCK-FREE, so the sleep blocks nothing: concurrent commands on the
        // same item sleep in parallel and then all try to append the same sequence number. It widens
        // the conflict window rather than serialising anything, so raising it drives optimistic
        // retries up, not queueing. k6/README's framing of RESERVE_DELAY_MS as ES's analogue of TO's
        // held row lock does not apply to this branch.
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
