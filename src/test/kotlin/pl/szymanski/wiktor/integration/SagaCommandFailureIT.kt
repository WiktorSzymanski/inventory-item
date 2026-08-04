package pl.szymanski.wiktor.integration

import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.messaging.MessageHandlerInterceptor
import org.axonframework.modelling.command.ConcurrencyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.szymanski.wiktor.controller.CreateItemRequest
import pl.szymanski.wiktor.controller.CreateOrderRequest
import pl.szymanski.wiktor.controller.CreateOrderResponse
import pl.szymanski.wiktor.controller.OrderItemRequest
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import org.springframework.web.client.RestTemplate
import javax.sql.DataSource

private const val FAILING_ITEM = "ITEM-DOOMED"

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "axon.saga.total-segments=1",
        "axon.saga.replicas=1",
        "axon.jdbc.pool.size=10",
        "spring.datasource.hikari.maximum-pool-size=10",
        "snapshot.enabled=false",
    ],
)
class SagaCommandFailureIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("inventory")
            .withUsername("inventory")
            .withPassword("inventory")

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
        }
    }

    // Static nested @TestConfiguration classes are picked up automatically by @SpringBootTest.
    @TestConfiguration
    class FaultInjection {
        @Autowired
        fun failReservationsForDoomedItem(commandBus: CommandBus) {
            commandBus.registerHandlerInterceptor(
                MessageHandlerInterceptor { unitOfWork, chain ->
                    val payload = unitOfWork.message.payload
                    // `id`, not `itemId` — see SagaReserveItemCommand's declaration.
                    if (payload is SagaReserveItemCommand && payload.id == FAILING_ITEM) {
                        // Same exception SQLStateResolver produces from a 23505, so this exercises
                        // the retry-then-give-up path rather than a bare dispatch error.
                        throw ConcurrencyException("injected conflict for $FAILING_ITEM")
                    }
                    chain.proceed()
                },
            )
        }
    }

    // Spring Boot 4.0 dropped TestRestTemplate from spring-boot-starter-test (it now lives in the
    // separate spring-boot-restclient-test module), so this drives the random port directly.
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var commandGateway: CommandGateway

    private val rest = RestTemplate()
    private fun url(path: String) = "http://localhost:$port$path"

    @Test
    fun `an order whose reservation never succeeds ends REJECTED with no saga left behind`() {
        val jdbc = JdbcTemplate(dataSource)

        rest.postForEntity(url("/inventory"), CreateItemRequest(FAILING_ITEM, 100, 0), Void::class.java)

        val orderId = rest.postForEntity(
            url("/inventory/orders"),
            CreateOrderRequest("user-1", listOf(OrderItemRequest(FAILING_ITEM, 1))),
            CreateOrderResponse::class.java,
        ).body!!.orderId

        val status = pollFor(60_000) {
            jdbc.query("SELECT status FROM orders WHERE order_id = ?", { rs, _ -> rs.getString(1) }, orderId)
                .firstOrNull()
                ?.takeIf { it != "PENDING" }
        }
        assertEquals("REJECTED", status, "order $orderId never reached a terminal status")

        val sagaRows = pollFor(30_000) {
            jdbc.queryForObject("SELECT count(*) FROM saga_entry", Long::class.java)?.takeIf { it == 0L }
        }
        assertEquals(0L, sagaRows, "the abandoned saga was never ended")
    }

    @Test
    fun `Axon propagates the FailOrderCommand result, so an ignored command is distinguishable`() {
        // The saga's `applied == false` branch is what stops an already-terminal order from
        // leaking its saga_entry row silently. Every unit test around it mocks the gateway, so
        // only this can prove the aggregate's Boolean actually survives the command bus rather
        // than arriving as null or Unit — if it did, that branch would be dead code and the
        // failure it guards would present exactly like the original defect.
        val itemId = "ITEM-FAIL-RESULT"
        rest.postForEntity(url("/inventory"), CreateItemRequest(itemId, 10, 0), Void::class.java)
        val orderId = rest.postForEntity(
            url("/inventory/orders"),
            CreateOrderRequest("user-1", listOf(OrderItemRequest(itemId, 1))),
            CreateOrderResponse::class.java,
        ).body!!.orderId

        val first = commandGateway.sendAndWait<Any?>(FailOrderCommand(orderId, "first"))
        assertEquals(true, first, "a PENDING order must report that the event was applied")

        val second = commandGateway.sendAndWait<Any?>(FailOrderCommand(orderId, "second"))
        assertEquals(false, second, "an already-terminal order must report that it did nothing")
    }

    /** Polls [supplier] until it returns non-null or [timeoutMs] elapses. */
    private fun <T> pollFor(timeoutMs: Long, supplier: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }
}
