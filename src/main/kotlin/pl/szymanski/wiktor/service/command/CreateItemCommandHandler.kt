package pl.szymanski.wiktor.service.command

import org.axonframework.modelling.command.TargetAggregateIdentifier
import java.util.UUID

data class CreateItemCommand(
    @TargetAggregateIdentifier val id: String,
    val availableQty: Int,
    val correlationId: UUID = UUID.randomUUID(),
)
