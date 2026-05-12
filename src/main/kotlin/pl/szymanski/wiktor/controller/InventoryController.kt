package pl.szymanski.wiktor.controller

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
        return ResponseEntity.ok(page.map { InventoryResponse(it.id, it.availableQty, it.lastEventRevision) })
    }

    @PostMapping
    suspend fun createItem(@RequestBody request: CreateItemRequest): ResponseEntity<CreateItemResponse> {
        val item = inventoryService.createItem(CreateItemCommand(request.id, request.availableQty, UUID.randomUUID()))
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateItemResponse(item.id, item.availableQty))
    }

    @GetMapping("/{itemId}")
    suspend fun getItem(@PathVariable itemId: String): ResponseEntity<InventoryResponse> {
        val item = inventoryService.getItem(itemId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(InventoryResponse(item.id, item.availableQty, item.lastEventRevision))
    }

    @PostMapping("/reserve")
    suspend fun reserve(@RequestBody request: ReserveItemRequest): ResponseEntity<Void> {
        inventoryService.reserveItem(ReserveItemCommand(request.id, request.reservationId, request.quantity, UUID.randomUUID()))
        return ResponseEntity.accepted().build()
    }
}
