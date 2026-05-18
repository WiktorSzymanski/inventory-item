package pl.szymanski.wiktor.service.command

import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
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
    val correlationId: UUID = UUID.randomUUID(),
)

@Service
class CreateInventoryItemCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val outboxService: OutboxService,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Transactional(propagation = Propagation.REQUIRED)
    suspend fun handle(command: CreateItemCommand): InventoryItem {
        log.info("[CREATE] itemId={} availableQty={} correlationId={}", command.id, command.availableQty, command.correlationId)
        val (item, event) = InventoryItem.create(command.id, command.availableQty, command.correlationId)
        try {
            inventoryRepo.save(item).awaitSingle()
        } catch (e: DuplicateKeyException) {
            log.warn("[CREATE] conflict itemId={} already exists correlationId={}", command.id, command.correlationId)
            throw ItemAlreadyExistsException("Item ${command.id} already exists")
        }
        outboxService.insertEntry(event.id, event.javaClass.simpleName, event)
        log.info("[CREATE] success itemId={} correlationId={}", item.id, command.correlationId)
        return item
    }
}
