package pl.szymanski.wiktor.domain

import com.fasterxml.jackson.annotation.JsonAutoDetect
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.modelling.command.AggregateIdentifier
import org.axonframework.modelling.command.AggregateLifecycle
import org.axonframework.spring.stereotype.Aggregate
import pl.szymanski.wiktor.service.command.CompleteOrderCommand
import pl.szymanski.wiktor.service.command.CreateOrderCommand
import pl.szymanski.wiktor.service.command.FailOrderCommand

enum class OrderStatus { PENDING, COMPLETED, FAILED }

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Aggregate
class OrderAggregate {

    @AggregateIdentifier
    private lateinit var orderId: String
    private lateinit var items: List<OrderItem>
    private var status: OrderStatus = OrderStatus.PENDING

    constructor()

    @CommandHandler
    constructor(command: CreateOrderCommand) {
        AggregateLifecycle.apply(OrderCreatedEvent(command.orderId, command.userId, command.items, command.correlationId))
    }

    @CommandHandler
    fun handle(command: CompleteOrderCommand) {
        if (status != OrderStatus.PENDING) return
        AggregateLifecycle.apply(OrderCompletedEvent(command.orderId))
    }

    /**
     * Returns whether the event was actually applied. The saga needs to tell "the order is now
     * FAILED" apart from "the order was already terminal, so I did nothing": in the second case
     * no OrderFailedEvent is produced, so the saga's @EndSaga handler never fires and its
     * saga_entry row would leak. The future completes normally either way, so without this the
     * two are indistinguishable at the call site.
     */
    @CommandHandler
    fun handle(command: FailOrderCommand): Boolean {
        if (status != OrderStatus.PENDING) return false
        AggregateLifecycle.apply(OrderFailedEvent(command.orderId, command.reason))
        return true
    }

    @EventSourcingHandler fun on(event: OrderCreatedEvent)   { orderId = event.orderId; items = event.items; status = OrderStatus.PENDING }
    @EventSourcingHandler fun on(event: OrderCompletedEvent) { status = OrderStatus.COMPLETED }
    @EventSourcingHandler fun on(event: OrderFailedEvent)    { status = OrderStatus.FAILED }
}
