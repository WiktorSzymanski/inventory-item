package pl.szymanski.wiktor.service.command

import org.springframework.stereotype.Service
import pl.szymanski.wiktor.repository.EventStoreRepository
import java.util.UUID

data class ReserveItemCommand(
    val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID = UUID.randomUUID(),
)

@Service
class ReserveInventoryItemCommandHandler(
    private val eventStoreRepository: EventStoreRepository,
) {
    suspend fun handle(command: ReserveItemCommand): String {
        val item = eventStoreRepository.loadAggregate(command.id)
        val (_, event) = item.reserve(command.reservationId, command.quantity, command.correlationId)

        eventStoreRepository.appendEvent(event)
        return command.reservationId
    }
}
