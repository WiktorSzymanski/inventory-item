package pl.szymanski.wiktor.controller

import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.exception.OptimisticLockExhaustedException
import pl.szymanski.wiktor.exception.ReservationForThatItemAlreadyExistsException
import pl.szymanski.wiktor.service.InventoryService
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.ReserveItemCommand
import java.util.UUID

data class CreateItemRequest(val id: String, val availableQty: Int)
data class ReserveItemRequest(val id: String, val reservationId: String, val quantity: Int)

data class InventoryResponse(val itemId: String, val availableQty: Int, val version: Long)
data class ReserveResponse(val itemId: String, val reservationId: String, val quantity: Int)
data class CreateItemResponse(val itemId: String, val availableQty: Int)

@RestController
@RequestMapping("/inventory")
class InventoryController(
    private val inventoryService: InventoryService,
) {

    @GetMapping
    suspend fun getItems(pageable: Pageable): ResponseEntity<Page<InventoryResponse>> {
        val page = inventoryService.getItems(pageable)
        return ResponseEntity.ok(page.map { InventoryResponse(it.id, it.availableQty, it.version) })
    }

    @PostMapping
    suspend fun createItem(@RequestBody request: CreateItemRequest): ResponseEntity<CreateItemResponse> {
        val command = CreateItemCommand(request.id, request.availableQty, UUID.randomUUID())
        return try {
            val item = inventoryService.createItem(command)
            ResponseEntity.status(HttpStatus.CREATED).body(CreateItemResponse(item.id, item.availableQty))
        } catch (e: DuplicateKeyException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    @GetMapping("/{itemId}")
    suspend fun getItem(@PathVariable itemId: String): ResponseEntity<InventoryResponse> {
        val item = inventoryService.getItem(itemId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(InventoryResponse(item.id, item.availableQty, item.version))
    }

    @PostMapping("/reserve")
    suspend fun reserve(@RequestBody request: ReserveItemRequest): ResponseEntity<ReserveResponse> {
        val command = ReserveItemCommand(request.id, request.reservationId, request.quantity, UUID.randomUUID())
        return try {
            inventoryService.reserveItem(command)
            ResponseEntity.status(HttpStatus.ACCEPTED).build()
        } catch (e: NotFoundException) {
            ResponseEntity.notFound().build()
        } catch (e: InsufficientStockException) {
            ResponseEntity.unprocessableEntity().build()
        } catch (e: ReservationForThatItemAlreadyExistsException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        } catch (e: OptimisticLockExhaustedException) {
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }
}
