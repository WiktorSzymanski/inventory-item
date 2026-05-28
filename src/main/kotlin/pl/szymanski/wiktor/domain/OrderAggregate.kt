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

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Aggregate
class OrderAggregate {

    @AggregateIdentifier
    private lateinit var orderId: String

    constructor()

    @CommandHandler
    constructor(command: CreateOrderCommand) {
        AggregateLifecycle.apply(OrderCreatedEvent(command.orderId, command.userId, command.items, command.correlationId))
    }

    @CommandHandler
    fun handle(command: CompleteOrderCommand) {
        AggregateLifecycle.apply(OrderCompletedEvent(command.orderId))
    }

    @CommandHandler
    fun handle(command: FailOrderCommand) {
        AggregateLifecycle.apply(OrderFailedEvent(command.orderId, command.reason))
    }

    @EventSourcingHandler fun on(event: OrderCreatedEvent)   { orderId = event.orderId }
    @EventSourcingHandler fun on(event: OrderCompletedEvent) {}
    @EventSourcingHandler fun on(event: OrderFailedEvent)    {}
}
