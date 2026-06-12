package pl.szymanski.wiktor

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.core.task.TaskRejectedException
import pl.szymanski.wiktor.controller.GlobalExceptionHandler
import pl.szymanski.wiktor.controller.InventoryController
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.service.InventoryService

class ApplicationTest {

    private val inventoryService: InventoryService = mockk()

    private val mockMvc = MockMvcBuilders.standaloneSetup(InventoryController(inventoryService))
        .setControllerAdvice(GlobalExceptionHandler(SimpleMeterRegistry()))
        .build()

    @Test
    fun `POST inventory creates item and returns 201`() {
        every { inventoryService.createItem(any()) } returns
            InventoryItem("ITEM-002", 500, 0L)

        mockMvc.perform(post("/inventory")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"id":"ITEM-002","availableQty":500}"""))
            .andExpect(status().isCreated)
    }

    @Test
    fun `POST inventory returns 409 when item already exists`() {
        every { inventoryService.createItem(any()) } throws
            ItemAlreadyExistsException("Item ITEM-001 already exists")

        mockMvc.perform(post("/inventory")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"id":"ITEM-001","availableQty":100}"""))
            .andExpect(status().isConflict)
    }

    @Test
    fun `GET inventory item returns 200`() {
        every { inventoryService.getItem("ITEM-001") } returns
            InventoryItem("ITEM-001", 1000, 0L)

        mockMvc.perform(get("/inventory/ITEM-001"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.itemId").value("ITEM-001"))
            .andExpect(jsonPath("$.availableQty").value(1000))
    }

    @Test
    fun `GET inventory item returns 404 when not found`() {
        every { inventoryService.getItem("MISSING") } returns null

        mockMvc.perform(get("/inventory/MISSING"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST orders returns 202 on success`() {
        every { inventoryService.acceptOrder(any()) } returns "ORDER-1"

        mockMvc.perform(post("/inventory/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":"USER-1","items":[{"itemId":"ITEM-001","quantity":5}]}"""))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.orderId").value("ORDER-1"))
    }

    @Test
    fun `POST orders returns 503 when worker queue is full`() {
        every { inventoryService.acceptOrder(any()) } throws
            TaskRejectedException("queue full")

        mockMvc.perform(post("/inventory/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":"USER-1","items":[{"itemId":"ITEM-001","quantity":5}]}"""))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `GET order returns 200 with status`() {
        every { inventoryService.getOrder("ORDER-1") } returns
            Order("ORDER-1", "USER-1", OrderStatus.REJECTED, "Item MISSING not found")

        mockMvc.perform(get("/inventory/orders/ORDER-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orderId").value("ORDER-1"))
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.failureReason").value("Item MISSING not found"))
    }

    @Test
    fun `GET order returns 404 when unknown`() {
        every { inventoryService.getOrder("MISSING") } returns null

        mockMvc.perform(get("/inventory/orders/MISSING"))
            .andExpect(status().isNotFound)
    }
}
