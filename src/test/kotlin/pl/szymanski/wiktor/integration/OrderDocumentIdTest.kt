package pl.szymanski.wiktor.integration

import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderItem
import pl.szymanski.wiktor.subscription.OrderProjectionUpdater
import java.time.Instant
import java.util.UUID

/**
 * The order projection must key its documents on `_id`, and `order.e2e.time` must actually be
 * recorded. Both are one bug, and it is the most expensive one this branch has hit.
 *
 * Spring Data maps a query field onto `_id` only when it names the ENTITY PROPERTY carrying
 * `@Id` -- `orderId` on OrderProjection, not `id`. Writing `where("id")` is not an error and
 * produces no warning: the name is passed through literally, so the upsert matches nothing,
 * MongoDB generates an ObjectId for `_id`, and the real order id lands in a stray `id` field
 * beside it.
 *
 * Everything then still LOOKS fine. The status updates keep working, because they match that
 * stray field. What breaks is the read-back by `_id` in OrderProjectionUpdater.readCreatedAt:
 * it finds nothing, so every `order.e2e.time` sample is skipped with a WARN -- and since
 * k6/bench/common.sh derives in-flight orders as `accepted - order_e2e_time_seconds_count`,
 * the drain phase never converges either. One field name cost the benchmark its headline
 * latency metric and its completion signal at the same time.
 *
 * Only a real run caught it. Every unit and integration test passed, including
 * SagaCommandFailureIT, which polls the order's status and so matched the stray field.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["axon.saga.total-segments=1", "snapshot.enabled=false"],
)
class OrderDocumentIdTest {

    @Autowired private lateinit var mongo: MongoOperations
    @Autowired private lateinit var updater: OrderProjectionUpdater
    @Autowired private lateinit var meterRegistry: io.micrometer.core.instrument.MeterRegistry

    @Test
    fun `the order document is keyed on _id, with no stray id field`() {
        val orderId = UUID.randomUUID().toString()
        val createdAt = Instant.now().minusSeconds(5)

        updater.on(
            OrderCreatedEvent(orderId, "user-1", listOf(OrderItem("item-1", 1)), UUID.randomUUID()),
            createdAt,
        )

        val raw = mongo.findOne(Query(where("_id").`is`(orderId)), Document::class.java, "orders")
        assertThat(raw)
            .`as`("the document must be addressable by _id = the order id; if this is null, the " +
                "upsert filter named a property that is not the @Id one and Mongo generated an ObjectId")
            .isNotNull
        assertThat(raw!!["id"])
            .`as`("a stray `id` field means the filter was not mapped onto _id")
            .isNull()
        assertThat(raw["createdAt"])
            .`as`("createdAt must be persisted -- readCreatedAt is what order.e2e.time is measured from")
            .isNotNull
    }

    @Test
    fun `a completed order records order_e2e_time rather than skipping it`() {
        // The consequence test, and the one that would actually have failed on the real run.
        // Asserting the document shape alone does not cover a readCreatedAt that queries the
        // wrong field; this asserts the metric the whole benchmark is built on.
        val orderId = UUID.randomUUID().toString()
        val createdAt = Instant.now().minusSeconds(3)

        val before = e2eCount("confirmed")
        updater.on(
            OrderCreatedEvent(orderId, "user-1", listOf(OrderItem("item-1", 1)), UUID.randomUUID()),
            createdAt,
        )
        updater.on(OrderCompletedEvent(orderId), createdAt.plusSeconds(3))

        assertThat(e2eCount("confirmed") - before)
            .`as`("order.e2e.time{outcome=confirmed} must gain a sample; 0 means readCreatedAt " +
                "returned null and the handler logged [E2E] missing created_at instead")
            .isEqualTo(1L)

        val stored = mongo.findOne(Query(where("_id").`is`(orderId)), Document::class.java, "orders")
        assertThat(stored!!["status"]).isEqualTo("CONFIRMED")
    }

    private fun e2eCount(outcome: String): Long =
        meterRegistry.find("order.e2e.time").tag("outcome", outcome).timer()?.count() ?: 0L

    companion object {
        @Container
        @JvmStatic
        val mongoDb: MongoDBContainer = MongoDBContainer("mongo:7")

        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.mongodb.uri") {
                "${mongoDb.getReplicaSetUrl("inventory")}?directConnection=true"
            }
        }
    }
}
