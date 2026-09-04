package pl.szymanski.wiktor.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.beans.factory.annotation.Autowired
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.springframework.web.client.RestTemplate
import pl.szymanski.wiktor.controller.CreateItemRequest
import pl.szymanski.wiktor.controller.CreateOrderRequest
import pl.szymanski.wiktor.controller.CreateOrderResponse
import pl.szymanski.wiktor.controller.InventoryResponse
import pl.szymanski.wiktor.controller.OrderItemRequest
import javax.sql.DataSource

/**
 * What this branch changes, driven through the real command pool and event store rather than the
 * saga fixture's same-thread executor.
 *
 * Both properties are also covered by OrderReservationSagaTest, which is where they were driven
 * from; these exist because the saga's two halves run on different threads in production and the
 * unit tests collapse that distinction by construction.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "axon.saga.total-segments=1",
        "axon.saga.replicas=1",
        // Wide enough for every line of an order to hold its two connections at once — a reserve
        // takes one for its transaction and one for the event-store append. At the 10 the other
        // IT uses, five parallel lines would sit at the pool's limit and the test would measure
        // connection starvation rather than dispatch shape.
        "axon.jdbc.pool.size=40",
        "spring.datasource.hikari.maximum-pool-size=10",
        "snapshot.enabled=false",
    ],
)
class ParallelReservationIT {

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

        private const val RESERVE_DELAY_MS = 400
        private const val LINES = 5
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var dataSource: DataSource

    private val rest = RestTemplate()
    private fun url(path: String) = "http://localhost:$port$path"

    @Test
    fun `an order costs about one reservation delay, not one per line`() {
        val items = (1..LINES).map { "PARALLEL-$it" }
        items.forEach { createItem(it, availableQty = 10, reserveDelayMs = RESERVE_DELAY_MS) }
        // The first order through a fresh context pays for class loading, aggregate creation and
        // the processor's first token claim; measuring it would measure that.
        createItem("PARALLEL-WARMUP", availableQty = 10, reserveDelayMs = 0)
        awaitStatus(placeOrder(listOf("PARALLEL-WARMUP" to 1)))

        val start = System.currentTimeMillis()
        val status = awaitStatus(placeOrder(items.map { it to 1 }))
        val elapsed = System.currentTimeMillis() - start

        assertEquals("CONFIRMED", status)
        // Sequentially this order cannot finish in under LINES * RESERVE_DELAY_MS = 2000ms, and
        // that is before the L round trips through the event store it also needs. In parallel the
        // delays overlap, so the floor is one of them. The threshold sits between the two with
        // room for a slow machine rather than close to either.
        assertTrue(
            elapsed < 1500,
            "a $LINES-line order took ${elapsed}ms; ${LINES * RESERVE_DELAY_MS}ms would mean the " +
                "lines were reserved one after another",
        )
    }

    @Test
    fun `releases the reservations that land after a line has already been rejected`() {
        // The fast line is rejected while the two slow ones are still inside their reserve delay.
        // A saga that ended on that rejection would drop its correlationId association before
        // their InventoryReservedEvents existed, and the stock they took would never come back.
        val slow = listOf("LATE-1", "LATE-2")
        slow.forEach { createItem(it, availableQty = 5, reserveDelayMs = RESERVE_DELAY_MS) }
        createItem("LATE-EMPTY", availableQty = 0, reserveDelayMs = 0)

        val orderId = placeOrder(slow.map { it to 2 } + listOf("LATE-EMPTY" to 1))

        assertEquals("REJECTED", awaitStatus(orderId))

        slow.forEach { itemId ->
            val available = pollFor(30_000) { availableQty(itemId).takeIf { it == 5 } }
            assertEquals(5, available, "$itemId is still holding the reservation of a rejected order")
        }

        val jdbc = JdbcTemplate(dataSource)
        val sagaRows = pollFor(30_000) {
            jdbc.queryForObject("SELECT count(*) FROM saga_entry", Long::class.java)?.takeIf { it == 0L }
        }
        assertEquals(0L, sagaRows, "the saga outlived the order it decided")
    }

    private fun createItem(itemId: String, availableQty: Int, reserveDelayMs: Int) {
        rest.postForEntity(
            url("/inventory"),
            CreateItemRequest(itemId, availableQty, 0, reserveDelayMs),
            Void::class.java,
        )
    }

    private fun placeOrder(lines: List<Pair<String, Int>>): String =
        rest.postForEntity(
            url("/inventory/orders"),
            CreateOrderRequest("user-1", lines.map { OrderItemRequest(it.first, it.second) }),
            CreateOrderResponse::class.java,
        ).body!!.orderId

    private fun availableQty(itemId: String): Int? =
        rest.getForEntity(url("/inventory/$itemId"), InventoryResponse::class.java).body?.availableQty

    private fun awaitStatus(orderId: String): String? {
        val jdbc = JdbcTemplate(dataSource)
        return pollFor(60_000) {
            jdbc.query("SELECT status FROM orders WHERE order_id = ?", { rs, _ -> rs.getString(1) }, orderId)
                .firstOrNull()
                ?.takeIf { it != "PENDING" }
        }
    }

    /** Polls [supplier] until it returns non-null or [timeoutMs] elapses. */
    private fun <T> pollFor(timeoutMs: Long, supplier: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(20)
        }
        return null
    }
}
