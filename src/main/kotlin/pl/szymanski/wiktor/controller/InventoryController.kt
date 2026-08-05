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
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.service.InventoryService
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.CreateOrderCommand
import pl.szymanski.wiktor.service.command.OrderItem
import java.util.UUID

data class CreateItemRequest(val id: String, val availableQty: Int, val additionalBytesSize: Int = 0, val reserveDelayMs: Int = 0)
data class OrderItemRequest(val itemId: String, val quantity: Int)
data class CreateOrderRequest(val userId: String, val items: List<OrderItemRequest>, val additionalBytesSize: Int = 0)

data class InventoryResponse(val itemId: String, val availableQty: Int, val version: Long)
data class CreateItemResponse(val itemId: String, val availableQty: Int)
data class CreateOrderResponse(val orderId: String)
data class OrderStatusResponse(val orderId: String, val status: OrderStatus, val failureReason: String?)

@RestController
@RequestMapping("/inventory")
class InventoryController(
    private val inventoryService: InventoryService,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @GetMapping
    fun getItems(pageable: Pageable): ResponseEntity<Page<InventoryResponse>> {
        log.debug("GET /inventory page={} size={}", pageable.pageNumber, pageable.pageSize)
        val page = inventoryService.getItems(pageable)
        log.debug("GET /inventory returned {} items (total={})", page.numberOfElements, page.totalElements)
        return ResponseEntity.ok(page.map { InventoryResponse(it.id, it.availableQty, it.version) })
    }

    @PostMapping
    fun createItem(@RequestBody request: CreateItemRequest): ResponseEntity<CreateItemResponse> {
        log.info("POST /inventory itemId={} availableQty={} additionalBytesSize={} reserveDelayMs={}", request.id, request.availableQty, request.additionalBytesSize, request.reserveDelayMs)
        val item = inventoryService.createItem(
            CreateItemCommand(
                id = request.id,
                availableQty = request.availableQty,
                correlationId = UUID.randomUUID(),
                additionalBytesSize = request.additionalBytesSize,
                reserveDelayMs = request.reserveDelayMs,
            )
        )
        log.info("POST /inventory success itemId={}", item.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateItemResponse(item.id, item.availableQty))
    }

    @GetMapping("/{itemId}")
    fun getItem(@PathVariable itemId: String): ResponseEntity<InventoryResponse> {
        log.debug("GET /inventory/{}", itemId)
        val item = inventoryService.getItem(itemId)
            ?: run {
                log.info("GET /inventory/{} not found", itemId)
                return ResponseEntity.notFound().build()
            }
        log.debug("GET /inventory/{} found availableQty={}", itemId, item.availableQty)
        return ResponseEntity.ok(InventoryResponse(item.id, item.availableQty, item.version))
    }

    @PostMapping("/orders")
    fun createOrder(@RequestBody request: CreateOrderRequest): ResponseEntity<CreateOrderResponse> {
        log.info("POST /inventory/orders userId={} itemCount={}", request.userId, request.items.size)
        val orderId = inventoryService.acceptOrder(
            CreateOrderCommand(
                userId = request.userId,
                items = request.items.map { OrderItem(it.itemId, it.quantity) },
                additionalBytesSize = request.additionalBytesSize,
            )
        )
        log.info("POST /inventory/orders accepted orderId={}", orderId)
        return ResponseEntity.accepted().body(CreateOrderResponse(orderId))
    }

    @GetMapping("/orders/{orderId}")
    fun getOrder(@PathVariable orderId: String): ResponseEntity<OrderStatusResponse> {
        log.debug("GET /inventory/orders/{}", orderId)
        val order = inventoryService.getOrder(orderId)
            ?: run {
                log.info("GET /inventory/orders/{} not found", orderId)
                return ResponseEntity.notFound().build()
            }
        return ResponseEntity.ok(OrderStatusResponse(order.orderId, order.status, order.failureReason))
    }
}
