package pl.szymanski.wiktor.service

import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.modelling.command.ConcurrencyException
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.repository.InventoryProjection
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.ReserveItemCommand

@Service
class InventoryService(
    private val commandGateway: CommandGateway,
    private val inventoryRepository: InventoryRepository,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun createItem(command: CreateItemCommand) {
        log.info("[CREATE] itemId={} correlationId={}", command.id, command.correlationId)
        try {
            commandGateway.sendAndWait<Any?>(command)
        } catch (e: ConcurrencyException) {
            log.warn("[CREATE] conflict itemId={} already exists correlationId={}", command.id, command.correlationId)
            throw ItemAlreadyExistsException("Item ${command.id} already exists")
        }
        log.info("[CREATE] success itemId={} correlationId={}", command.id, command.correlationId)
    }

    fun getItem(itemId: String): InventoryProjection? =
        inventoryRepository.findById(itemId).orElse(null)

    fun getItems(pageable: Pageable): Page<InventoryProjection> {
        val items = inventoryRepository.findAllBy(pageable)
        val total = inventoryRepository.count()
        return PageImpl(items, pageable, total)
    }

    @Retryable(
        includes = [ConcurrencyException::class],
        maxRetries = 4,
        delay = 25,
        multiplier = 2.0,
        maxDelay = 500,
    )
    fun reserveItem(command: ReserveItemCommand) {
        log.info("[RESERVE] itemId={} reservationId={} correlationId={}", command.id, command.reservationId, command.correlationId)
        commandGateway.sendAndWait<Any?>(command)
        log.info("[RESERVE] success itemId={} reservationId={}", command.id, command.reservationId)
    }
}
