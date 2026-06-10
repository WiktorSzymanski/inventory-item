package pl.szymanski.wiktor.service

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.CreateOrderReservationCommand
import pl.szymanski.wiktor.service.command.CreateOrderReservationCommandHandler
import pl.szymanski.wiktor.service.command.ReserveInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.ReserveItemCommand

@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val createInventoryItemCommandHandler: CreateInventoryItemCommandHandler,
    private val reserveInventoryItemCommandHandler: ReserveInventoryItemCommandHandler,
    private val createOrderReservationCommandHandler: CreateOrderReservationCommandHandler,
) {
    fun createItem(command: CreateItemCommand): InventoryItem =
        createInventoryItemCommandHandler.handle(command)

    fun getItem(itemId: String): InventoryItem? =
        inventoryRepository.findById(itemId).orElse(null)

    fun getItems(pageable: Pageable): Page<InventoryItem> =
        inventoryRepository.findAll(pageable)

    @Retryable(
        includes = [OptimisticLockingFailureException::class],
        maxRetries = 4,
        delay = 25,
        multiplier = 2.0,
        maxDelay = 500,
    )
    fun reserveItem(command: ReserveItemCommand): String =
        reserveInventoryItemCommandHandler.handle(command)

    @Retryable(
        includes = [OptimisticLockingFailureException::class, PessimisticLockingFailureException::class],
        maxRetries = 4,
        delay = 25,
        multiplier = 2.0,
        maxDelay = 500,
    )
    fun createOrderReservation(command: CreateOrderReservationCommand): String =
        createOrderReservationCommandHandler.handle(command)
}
