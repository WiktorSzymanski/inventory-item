package pl.szymanski.wiktor.repository

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * The order read model, collection `orders` to match ES-2's table.
 *
 * `items` was `JSONB` on Postgres and needed a pair of `PGobject` converters
 * (`JdbcConvertersConfig`, deleted on this branch) to survive the round trip. A BSON document
 * holds a nested map natively, so the converters have no counterpart.
 *
 * `createdAt` is NEW as a mapped field. The Postgres table always had the column -- it is what
 * `order.e2e.time` measures against -- but the entity never declared it, because every read and
 * write of it went through hand-written SQL. Here the document is the schema, so the field has
 * to exist. It is set from the event's own timestamp, never `now()`, so the metric stays
 * replay-safe.
 */
@Document(collection = "orders")
data class OrderProjection(
    @Id val orderId: String,
    val userId: String,
    val status: String = "PENDING",
    val items: Map<String, Int> = mapOf(),
    val createdAt: Instant? = null,
    val failureReason: String? = null,
)

@Repository
interface OrderRepository : MongoRepository<OrderProjection, String>
