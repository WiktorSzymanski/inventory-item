package pl.szymanski.wiktor

import com.ninjasquad.springmockk.MockkBean
import io.micrometer.core.instrument.MeterRegistry
import io.mockk.coEvery
import io.mockk.just
import io.mockk.runs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import pl.szymanski.wiktor.controller.InventoryController
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.exception.ItemAlreadyExistsException
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.exception.OptimisticLockExhaustedException
import pl.szymanski.wiktor.repository.InventoryProjection
import pl.szymanski.wiktor.service.InventoryService

@WebFluxTest(InventoryController::class)
class ApplicationTest {

    @MockkBean(relaxed = true)
    lateinit var meterRegistry: MeterRegistry

    @MockkBean
    lateinit var inventoryService: InventoryService

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Test
    fun `POST inventory creates item and returns 201`() {
        coEvery { inventoryService.createItem(any()) } just runs

        webTestClient.post().uri("/inventory")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"ITEM-002","availableQty":500}""")
            .exchange()
            .expectStatus().isCreated
    }

    @Test
    fun `POST inventory returns 409 with message when item already exists`() {
        coEvery { inventoryService.createItem(any()) } throws
            ItemAlreadyExistsException("Item ITEM-001 already exists")

        webTestClient.post().uri("/inventory")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"ITEM-001","availableQty":100}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.message").isEqualTo("Item ITEM-001 already exists")
    }

    @Test
    fun `GET inventory item returns 200`() {
        coEvery { inventoryService.getItem("ITEM-001") } returns
            InventoryProjection("ITEM-001", 1000)

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
        coEvery { inventoryService.reserveItem(any()) } just runs

        webTestClient.post().uri("/inventory/reserve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"ITEM-001","reservationId":"RES-1","quantity":5}""")
            .exchange()
            .expectStatus().isAccepted
    }

    @Test
    fun `POST reserve returns 404 with message when item not found`() {
        coEvery { inventoryService.reserveItem(any()) } throws
            NotFoundException("Item MISSING not found")

        webTestClient.post().uri("/inventory/reserve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"MISSING","reservationId":"RES-1","quantity":1}""")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.message").isEqualTo("Item MISSING not found")
    }

    @Test
    fun `POST reserve returns 422 with message when stock is insufficient`() {
        coEvery { inventoryService.reserveItem(any()) } throws
            InsufficientStockException("Not enough stock of item ITEM-001")

        webTestClient.post().uri("/inventory/reserve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"ITEM-001","reservationId":"RES-1","quantity":999}""")
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.message").isEqualTo("Not enough stock of item ITEM-001")
    }

    @Test
    fun `POST reserve returns 503 when optimistic lock is exhausted`() {
        coEvery { inventoryService.reserveItem(any()) } throws
            OptimisticLockExhaustedException("Optimistic lock exhausted for item ITEM-001")

        webTestClient.post().uri("/inventory/reserve")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":"ITEM-001","reservationId":"RES-1","quantity":1}""")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.message").isEqualTo("Optimistic lock exhausted for item ITEM-001")
    }
}
