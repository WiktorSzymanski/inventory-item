package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.repository.InventoryRepository
import java.time.Clock
import java.util.UUID

data class CreateItemCommand(
    val id: String,
    val availableQty: Int,
    val correlationId: UUID = UUID.randomUUID(),
)

@Service
class CreateInventoryItemCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    // Name must not end in "created": the Prometheus client strips that suffix
    // (reserved for OpenMetrics created-timestamp series), mangling the metric.
    private val itemCreatedCounter: Counter = meterRegistry.counter("inventory.item.create.success")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(command: CreateItemCommand): InventoryItem {
        log.info("[CREATE] itemId={} availableQty={} correlationId={}", command.id, command.availableQty, command.correlationId)
        val (item, event) = InventoryItem.create(command.id, command.availableQty, command.correlationId, clock)
        try {
            inventoryRepo.save(item)
        } catch (e: DuplicateKeyException) {
            log.warn("[CREATE] conflict itemId={} already exists correlationId={}", command.id, command.correlationId)
            throw ItemAlreadyExistsException("Item ${command.id} already exists")
        }
        applicationEventPublisher.publishEvent(event)
        itemCreatedCounter.increment()
        log.info("[CREATE] success itemId={} correlationId={}", item.id, command.correlationId)
        return item
    }
}
