package pl.szymanski.wiktor.service.command

import io.kurrent.dbclient.WrongExpectedVersionException
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.repository.EventStoreRepository
import java.util.UUID

data class CreateItemCommand(
    val id: String,
    val availableQty: Int,
    val correlationId: UUID = UUID.randomUUID(),
)

@Service
class CreateInventoryItemCommandHandler(
    private val eventStoreRepository: EventStoreRepository,
) {
    suspend fun handle(command: CreateItemCommand): InventoryItem {
        val (item, event) = InventoryItem.create(command.id, command.availableQty, command.correlationId)
        try {
            eventStoreRepository.appendEvent(event)
        } catch (_: WrongExpectedVersionException) {
            throw ItemAlreadyExistsException("Item ${command.id} already exists")
        }
        return item
    }
}
