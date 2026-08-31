package pl.szymanski.wiktor.integration

import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.messaging.MessageHandlerInterceptor
import org.axonframework.modelling.command.ConcurrencyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.szymanski.wiktor.config.SagaIntakeGate
import pl.szymanski.wiktor.controller.CreateItemRequest
import pl.szymanski.wiktor.controller.CreateOrderRequest
import pl.szymanski.wiktor.controller.CreateOrderResponse
import pl.szymanski.wiktor.controller.OrderItemRequest
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import org.springframework.web.client.RestTemplate
import java.util.concurrent.Executor
import javax.sql.DataSource

private const val FAILING_ITEM = "ITEM-DOOMED"

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "axon.saga.total-segments=1",
        "axon.saga.replicas=1",
        // ES-4-bounded: the tightest bound the gate can take, so every test in this class runs
        // WITH the intake queue saturated. A bound that throttles is the branch; a bound that
        // strands an order is the defect, and the assertions below are the same ones either way.
        "axon.saga.intake-capacity=1",
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
    @Autowired private lateinit var meterRegistry: MeterRegistry

    @Autowired
    @Qualifier("sagaIntakeExecutor")
    private lateinit var intakeExecutor: Executor

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

    @Test
    fun `a saturated intake bound throttles new orders without stranding any of them`() {
        // The one thing a bound must never do. Everything upstream of it is recoverable — a
        // blocked segment thread just stops reading OrderCreatedEvents, and the backlog waits in
        // the event store — but a start that the gate drops appends no event, completes no gateway
        // callback and leaves the order PENDING forever with no counter and no log.
        //
        // capacity=1 (set on the class) means all but one arrival is blocked at any moment, which
        // is the state a real run only reaches under saturation. Unit tests cover the gate's
        // mechanics; only this covers it against a real TrackingEventProcessor, where the deadlock
        // a saga-lifetime bound would cause would show up as exactly this test timing out.
        val gate = intakeExecutor as SagaIntakeGate
        assertEquals(1, gate.capacity, "axon.saga.intake-capacity must reach the gate, not just bind")

        val jdbc = JdbcTemplate(dataSource)
        val items = (1..4).map { "ITEM-BOUNDED-$it" }
        items.forEach { rest.postForEntity(url("/inventory"), CreateItemRequest(it, 1000, 0), Void::class.java) }

        // Multi-line orders on purpose: a continuation that queued behind the gate instead of
        // bypassing it would deadlock here rather than merely slow down.
        val orderIds = (1..20).map { i ->
            rest.postForEntity(
                url("/inventory/orders"),
                CreateOrderRequest("user-1", items.map { OrderItemRequest(it, 1) }.take(1 + i % 4)),
                CreateOrderResponse::class.java,
            ).body!!.orderId
        }

        val pending = pollFor(90_000) {
            val placeholders = orderIds.joinToString(",") { "?" }
            jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE order_id IN ($placeholders) AND status = 'PENDING'",
                Long::class.java,
                *orderIds.toTypedArray(),
            )?.takeIf { it == 0L }
        }
        assertEquals(0L, pending, "the bound throttled these orders into never finishing")

        assertEquals(
            0.0,
            meterRegistry.counter("saga.intake.timeout").count(),
            "the gate gave up waiting and admitted anyway — this run did not measure capacity=1",
        )
        // Every start passed THROUGH the gate rather than around it. The unit tests assert the
        // routing against a mocked gateway; this asserts it against the real @StartSaga path,
        // where a missing @Qualifier would silently inject the ungated pool instead.
        assertTrue(
            (meterRegistry.find("saga.intake.wait").timer()?.count() ?: 0L) >= orderIds.size,
            "fewer intake waits than orders — some starts bypassed the gate",
        )
    }

    @Test
    fun `the intake series are actually scrapeable under the names a panel would query`() {
        // Micrometer's Java names are not its Prometheus names, and the gap is silent: a series
        // that never resolves draws an empty panel, not an error. This repo has already lost a
        // metric that way (a name ending in "created" is mangled by the Prometheus registry), and
        // saga_intake_wait is the headline series of this branch — it is the only thing that
        // distinguishes "the bound made it faster" from "the bound moved the wait somewhere I am
        // not looking".
        val scrape = rest.getForObject(url("/actuator/prometheus"), String::class.java) ?: ""

        for (series in listOf(
            "saga_intake_wait_seconds_bucket",   // the Timer's histogram, what percentiles need
            "saga_intake_wait_seconds_count",
            "saga_intake_permits_available",
            "saga_intake_blocked",
            "saga_intake_capacity",
            "saga_intake_timeout_total",         // Counter, so `_total` — not the Java name
        )) {
            assertTrue(scrape.contains(series), "$series is not scrapeable; a panel on it draws nothing")
        }
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
