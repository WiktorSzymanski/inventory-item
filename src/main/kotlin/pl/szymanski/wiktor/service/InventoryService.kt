package pl.szymanski.wiktor.service

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.ReserveItemCommand

@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val retryableInventoryCommandExecutor: RetryableInventoryCommandExecutor,
) {
    fun createItem(command: CreateItemCommand): InventoryItem =
        retryableInventoryCommandExecutor.createItem(command)

    fun getItem(itemId: String): InventoryItem? =
        inventoryRepository.findById(itemId).orElse(null)

    fun getItems(pageable: Pageable): Page<InventoryItem> =
        inventoryRepository.findAll(pageable)

    fun reserveItem(command: ReserveItemCommand): String =
        retryableInventoryCommandExecutor.reserveItem(command)
}
