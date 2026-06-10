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
import pl.szymanski.wiktor.controller.GlobalExceptionHandler
import pl.szymanski.wiktor.controller.InventoryController
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.exception.NotFoundException
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
    fun `POST reserve returns 202 on success`() {
        every { inventoryService.reserveItem(any()) } returns "RES-1"

        mockMvc.perform(post("/inventory/reserve")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"id":"ITEM-001","reservationId":"RES-1","quantity":5}"""))
            .andExpect(status().isAccepted)
    }

    @Test
    fun `POST reserve returns 404 when item not found`() {
        every { inventoryService.reserveItem(any()) } throws
            NotFoundException("Item MISSING not found")

        mockMvc.perform(post("/inventory/reserve")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"id":"MISSING","reservationId":"RES-1","quantity":1}"""))
            .andExpect(status().isNotFound)
    }
}
