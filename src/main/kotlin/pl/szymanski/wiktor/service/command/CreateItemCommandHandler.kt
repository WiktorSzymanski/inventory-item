package pl.szymanski.wiktor.service.command

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.service.OutboxService
import java.util.UUID

data class CreateItemCommand(
    val id: String,
    val availableQty: Int,
    val correlationId: UUID,
)

@Service
class CreateInventoryItemCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val outboxService: OutboxService,
) {

    @Transactional(propagation = Propagation.REQUIRED)
    suspend fun handle(command: CreateItemCommand): InventoryItem {
        val (item, event) = InventoryItem.create(command.id, command.availableQty, command.correlationId)

        inventoryRepo.save(item).awaitSingle()
        outboxService.insertEntry(event.id, event.javaClass.simpleName, event)

        return item
    }
}