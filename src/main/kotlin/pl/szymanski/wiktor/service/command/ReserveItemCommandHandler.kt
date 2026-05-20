package pl.szymanski.wiktor.service.command

import org.axonframework.modelling.command.TargetAggregateIdentifier
import java.util.UUID

data class ReserveItemCommand(
    @TargetAggregateIdentifier val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID = UUID.randomUUID(),
)
