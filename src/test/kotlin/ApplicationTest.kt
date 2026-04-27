package pl.szymanski.wiktor

import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import pl.szymanski.wiktor.controller.InventoryController
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.service.InventoryService
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.ReserveItemCommand

@WebFluxTest(InventoryController::class)
class ApplicationTest {

    @MockkBean
    lateinit var inventoryService: InventoryService

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Test
    fun `POST inventory creates item and returns 201`() {
        coEvery { inventoryService.createItem(CreateItemCommand("ITEM-002", 500)) } returns
            InventoryItem("ITEM-002", 500, mapOf(), 0L)

        webTestClient.post().uri("/inventory")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"ITEM-002","availableQty":500}""")
            .exchange()
            .expectStatus().isCreated
    }

    @Test
    fun `POST inventory returns 409 when item already exists`() {
        coEvery { inventoryService.createItem(any()) } throws
            ItemAlreadyExistsException("Item ITEM-001 already exists")

        webTestClient.post().uri("/inventory")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"ITEM-001","availableQty":100}""")
            .exchange()
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `GET inventory item returns 200`() {
        coEvery { inventoryService.getItem("ITEM-001") } returns
            InventoryItem("ITEM-001", 1000, mapOf(), 0L)

        webTestClient.get().uri("/inventory/ITEM-001")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.itemId").isEqualTo("ITEM-001")
            .jsonPath("$.availableQty").isEqualTo(1000)
    }

    @Test
    fun `GET inventory item returns 404 when not found`() {
        coEvery { inventoryService.getItem("MISSING") } returns null

        webTestClient.get().uri("/inventory/MISSING")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `POST reserve returns 202 on success`() {
        coEvery { inventoryService.reserveItem(ReserveItemCommand("ITEM-001", "RES-1", 5)) } returns "RES-1"

        webTestClient.post().uri("/inventory/reserve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"ITEM-001","reservationId":"RES-1","quantity":5}""")
            .exchange()
            .expectStatus().isAccepted
    }

    @Test
    fun `POST reserve returns 404 when item not found`() {
        coEvery { inventoryService.reserveItem(any()) } throws
            NotFoundException("Item MISSING not found")

        webTestClient.post().uri("/inventory/reserve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"MISSING","reservationId":"RES-1","quantity":1}""")
            .exchange()
            .expectStatus().isNotFound
    }
}
