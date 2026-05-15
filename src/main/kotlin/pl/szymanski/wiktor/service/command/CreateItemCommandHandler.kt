package pl.szymanski.wiktor.service.command

import io.kurrent.dbclient.WrongExpectedVersionException
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun handle(command: CreateItemCommand): InventoryItem {
        log.info("[CREATE] itemId={} availableQty={} correlationId={}", command.id, command.availableQty, command.correlationId)
        val (item, event) = InventoryItem.create(command.id, command.availableQty, command.correlationId)
        try {
            eventStoreRepository.appendEvent(event)
        } catch (_: WrongExpectedVersionException) {
            log.warn("[CREATE] conflict itemId={} already exists correlationId={}", command.id, command.correlationId)
            throw ItemAlreadyExistsException("Item ${command.id} already exists")
        }
        log.info("[CREATE] success itemId={} correlationId={}", item.id, command.correlationId)
        return item
    }
}
