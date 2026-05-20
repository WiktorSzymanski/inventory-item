package pl.szymanski.wiktor.controller

import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(this::class.java)

    @GetMapping
    suspend fun getItems(pageable: Pageable): ResponseEntity<Page<InventoryResponse>> {
        log.debug("GET /inventory page={} size={}", pageable.pageNumber, pageable.pageSize)
        val page = inventoryService.getItems(pageable)
        log.debug("GET /inventory returned {} items (total={})", page.numberOfElements, page.totalElements)
        return ResponseEntity.ok(page.map { InventoryResponse(it.id, it.availableQty, it.lastEventRevision) })
    }

    @PostMapping
    suspend fun createItem(@RequestBody request: CreateItemRequest): ResponseEntity<CreateItemResponse> {
        log.info("POST /inventory itemId={} availableQty={}", request.id, request.availableQty)
        inventoryService.createItem(CreateItemCommand(request.id, request.availableQty, UUID.randomUUID()))
        log.info("POST /inventory success itemId={}", request.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateItemResponse(request.id, request.availableQty))
    }

    @GetMapping("/{itemId}")
    suspend fun getItem(@PathVariable itemId: String): ResponseEntity<InventoryResponse> {
        log.debug("GET /inventory/{}", itemId)
        val item = inventoryService.getItem(itemId)
            ?: run {
                log.info("GET /inventory/{} not found", itemId)
                return ResponseEntity.notFound().build()
            }
        log.debug("GET /inventory/{} found availableQty={}", itemId, item.availableQty)
        return ResponseEntity.ok(InventoryResponse(item.id, item.availableQty, item.lastEventRevision))
    }

    @PostMapping("/reserve")
    suspend fun reserve(@RequestBody request: ReserveItemRequest): ResponseEntity<Void> {
        log.info("POST /inventory/reserve itemId={} reservationId={} quantity={}", request.id, request.reservationId, request.quantity)
        inventoryService.reserveItem(ReserveItemCommand(request.id, request.reservationId, request.quantity, UUID.randomUUID()))
        log.info("POST /inventory/reserve accepted itemId={} reservationId={}", request.id, request.reservationId)
        return ResponseEntity.accepted().build()
    }
}
