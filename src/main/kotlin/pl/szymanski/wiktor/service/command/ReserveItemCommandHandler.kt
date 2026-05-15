package pl.szymanski.wiktor.service.command

import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun handle(command: ReserveItemCommand): String {
        log.info("[RESERVE] itemId={} reservationId={} quantity={} correlationId={}", command.id, command.reservationId, command.quantity, command.correlationId)
        val item = eventStoreRepository.loadAggregate(command.id)
        val (_, event) = item.reserve(command.reservationId, command.quantity, command.correlationId)

        eventStoreRepository.appendEvent(event)
        log.info("[RESERVE] success itemId={} reservationId={} correlationId={}", command.id, command.reservationId, command.correlationId)
        return command.reservationId
    }
}
