package pl.szymanski.wiktor.service.command

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.service.OutboxService
import java.util.UUID

data class ReserveItemCommand(
    val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID,
)

@Service
class ReserveInventoryItemCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val outboxService: OutboxService,
) {

    @Transactional(propagation = Propagation.REQUIRED)
    suspend fun handle(command: ReserveItemCommand): String {
        val (item, event) = inventoryRepo.findById(command.id).awaitSingle()?.reserve(command.reservationId, command.quantity, command.correlationId)
            ?: throw NotFoundException("Item ${command.id} not found")

        inventoryRepo.save(item).awaitSingle()
        outboxService.insertEntry(event.id, event.javaClass.simpleName, event)

        return command.reservationId
    }
}
