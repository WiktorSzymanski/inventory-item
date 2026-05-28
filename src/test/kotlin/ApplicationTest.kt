package pl.szymanski.wiktor

import io.micrometer.core.instrument.MeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.mockk.junit5.MockKExtension
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.szymanski.wiktor.controller.GlobalExceptionHandler
import pl.szymanski.wiktor.controller.InventoryController
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.exception.OptimisticLockExhaustedException
import pl.szymanski.wiktor.repository.InventoryProjection
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.InventoryService

@ExtendWith(MockKExtension::class)
class ApplicationTest {

    private val inventoryService: InventoryService = mockk()
    private val orderRepository: OrderRepository = mockk()
    private val meterRegistry: MeterRegistry = mockk(relaxed = true)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(InventoryController(inventoryService, orderRepository))
            .setControllerAdvice(GlobalExceptionHandler(meterRegistry))
            .build()
    }

    @Test
    fun `POST inventory creates item and returns 201`() {
        every { inventoryService.createItem(any()) } just runs

        mockMvc.post("/inventory") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id":"ITEM-002","availableQty":500}"""
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `POST inventory returns 409 with message when item already exists`() {
        every { inventoryService.createItem(any()) } throws
            ItemAlreadyExistsException("Item ITEM-001 already exists")

        mockMvc.post("/inventory") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id":"ITEM-001","availableQty":100}"""
        }.andExpect {
            status { isEqualTo(409) }
            jsonPath("$.message") { value("Item ITEM-001 already exists") }
        }
    }

    @Test
    fun `GET inventory item returns 200`() {
        every { inventoryService.getItem("ITEM-001") } returns
            InventoryProjection("ITEM-001", 1000)

        mockMvc.get("/inventory/ITEM-001")
            .andExpect {
                status { isOk() }
                jsonPath("$.itemId") { value("ITEM-001") }
                jsonPath("$.availableQty") { value(1000) }
            }
    }

    @Test
    fun `GET inventory item returns 404 when not found`() {
        every { inventoryService.getItem("MISSING") } returns null

        mockMvc.get("/inventory/MISSING")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `POST reserve returns 202 on success`() {
        every { inventoryService.reserveItem(any()) } just runs

        mockMvc.post("/inventory/reserve") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id":"ITEM-001","reservationId":"RES-1","quantity":5}"""
        }.andExpect { status { isAccepted() } }
    }

    @Test
    fun `POST reserve returns 404 with message when item not found`() {
        every { inventoryService.reserveItem(any()) } throws
            NotFoundException("Item MISSING not found")

        mockMvc.post("/inventory/reserve") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id":"MISSING","reservationId":"RES-1","quantity":1}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.message") { value("Item MISSING not found") }
        }
    }

    @Test
    fun `POST reserve returns 422 with message when stock is insufficient`() {
        every { inventoryService.reserveItem(any()) } throws
            InsufficientStockException("Not enough stock of item ITEM-001")

        mockMvc.post("/inventory/reserve") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id":"ITEM-001","reservationId":"RES-1","quantity":999}"""
        }.andExpect {
            status { isEqualTo(422) }
            jsonPath("$.message") { value("Not enough stock of item ITEM-001") }
        }
    }

    @Test
    fun `POST reserve returns 503 when optimistic lock is exhausted`() {
        every { inventoryService.reserveItem(any()) } throws
            OptimisticLockExhaustedException("Optimistic lock exhausted for item ITEM-001")

        mockMvc.post("/inventory/reserve") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id":"ITEM-001","reservationId":"RES-1","quantity":1}"""
        }.andExpect {
            status { isEqualTo(503) }
            jsonPath("$.message") { value("Optimistic lock exhausted for item ITEM-001") }
        }
    }
}
