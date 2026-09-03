package pl.szymanski.wiktor.subscription

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.axonframework.eventhandling.Timestamp
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderFailedEvent
import pl.szymanski.wiktor.repository.OrderProjection
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * The order read model, and the source of `order.e2e.time`.
 *
 * **Every query below names `orderId`, never `id`, and that is not a style choice.** Spring Data
 * maps a query field to `_id` only when it is the ENTITY PROPERTY carrying `@Id` -- which on
 * [OrderProjection] is `orderId`. A filter written as `where("id")` is not an error and does not
 * warn: it is passed through as a literal field name, so the upsert below matched nothing, MongoDB
 * generated an ObjectId for `_id`, and the orderId was stored beside it in a stray `id` field.
 *
 * The damage was entirely in [readCreatedAt], which reads by `_id`: it found nothing, every
 * `order.e2e.time` sample was skipped with a WARN, and because the harness derives in-flight orders
 * as `accepted - order_e2e_time_seconds_count`, the drain never converged either. One field name
 * cost the benchmark its headline latency metric AND its completion signal, while the status
 * updates kept working (they matched the stray `id` field) and every integration test passed.
 * `OrderDocumentIdTest` is the regression guard.
 *
 * Same two departures from ES-2 as [InventoryProjectionUpdater]: the statements are Mongo
 * updates instead of SQL, and the per-handler transaction is gone because each handler writes
 * one document. Metric names, tags and the `outcome` values are untouched -- `order.e2e.time`
 * has to stay comparable with the TO branches as well as with ES-2.
 *
 * The `items` map needed a hand-built JSON string and a `::jsonb` cast on Postgres. It is just
 * a nested document here, so both are gone along with `JdbcConvertersConfig`.
 */
@Component
@ProcessingGroup("order-projection")
class OrderProjectionUpdater(
    private val mongo: MongoOperations,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private val log = LoggerFactory.getLogger(OrderProjectionUpdater::class.java)
    }

    private val projectionLagTimer: Timer = Timer.builder("order.projection.lag")
        .publishPercentileHistogram(true)
        .maximumExpectedValue(Duration.ofMinutes(10))
        .register(meterRegistry)

    // Admission-to-terminal latency, computed purely from event-store @Timestamp values
    // (OrderCreatedEvent -> OrderCompleted/FailedEvent), so it is replay-safe and excludes
    // projection lag. Metric name and `outcome` tag mirror the TO branches' order.e2e.time.
    private fun e2eTimer(outcome: String): Timer =
        Timer.builder("order.e2e.time")
            .tag("outcome", outcome)
            .publishPercentileHistogram(true)
            .maximumExpectedValue(Duration.ofMinutes(10))
            .register(meterRegistry)

    @EventHandler
    fun on(event: OrderCreatedEvent, @Timestamp timestamp: Instant) {
        // `INSERT ... ON CONFLICT DO NOTHING`. Every field is $setOnInsert, so a re-delivery
        // finds the document and changes nothing. Unlike the inventory create there is no
        // revision predicate in the filter, so this upsert can never collide on _id and needs
        // no duplicate-key arm.
        //
        // createdAt is the event's OWN timestamp rather than now(), so e2e is measured against
        // admission time and stays correct on replay.
        mongo.upsert(
            Query(where("orderId").`is`(event.orderId)),
            Update()
                .setOnInsert("userId", event.userId)
                .setOnInsert("status", "PENDING")
                .setOnInsert("items", event.items.associate { it.itemId to it.quantity })
                .setOnInsert("createdAt", timestamp),
            OrderProjection::class.java,
        )
        recordLag(timestamp, "OrderCreatedEvent")
    }

    @EventHandler
    fun on(event: OrderCompletedEvent, @Timestamp timestamp: Instant) {
        mongo.updateFirst(
            Query(where("orderId").`is`(event.orderId)),
            Update().set("status", "CONFIRMED"),
            OrderProjection::class.java,
        )
        recordE2e("confirmed", readCreatedAt(event.orderId), timestamp, event.orderId)
        recordLag(timestamp, "OrderCompletedEvent")
    }

    @EventHandler
    fun on(event: OrderFailedEvent, @Timestamp timestamp: Instant) {
        mongo.updateFirst(
            Query(where("orderId").`is`(event.orderId)),
            Update()
                .set("status", "REJECTED")
                .set("failureReason", event.reason),
            OrderProjection::class.java,
        )
        recordE2e("rejected", readCreatedAt(event.orderId), timestamp, event.orderId)
        recordLag(timestamp, "OrderFailedEvent")
    }

    /**
     * `SELECT created_at FROM orders WHERE order_id = :orderId`, and deliberately still a
     * projection of that one field rather than a `findById`.
     *
     * It reads into a raw [Document] instead of [OrderProjection] for a reason that would
     * otherwise be a runtime surprise: a field-limited query returns a partial document, and
     * mapping that onto a Kotlin data class whose `userId` is non-nullable fails. The raw read
     * also lets the value come back as either a BSON date or an Instant without caring which.
     */
    private fun readCreatedAt(orderId: String): Instant? {
        val query = Query(where("_id").`is`(orderId))
        query.fields().include("createdAt")
        return when (val value = mongo.findOne(query, Document::class.java, "orders")?.get("createdAt")) {
            is Date -> value.toInstant()
            is Instant -> value
            else -> null
        }
    }

    private fun recordE2e(outcome: String, createdAt: Instant?, terminalTs: Instant, orderId: String) {
        if (createdAt == null) {
            log.warn("[E2E] missing created_at orderId={} outcome={}, skipping order.e2e.time", orderId, outcome)
            return
        }
        val e2e = Duration.between(createdAt, terminalTs)
        e2eTimer(outcome).record(e2e)
        log.info("[E2E] orderId={} outcome={} e2e={}ms", orderId, outcome, e2e.toMillis())
    }

    private fun recordLag(timestamp: Instant, eventType: String) {
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info("[PROJECTION] collection=orders type={} lag={}ms", eventType, lag.toMillis())
        meterRegistry.counter("es.events.processed", "eventType", eventType).increment()
    }
}
