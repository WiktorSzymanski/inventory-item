package pl.szymanski.wiktor.subscription

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.axonframework.eventhandling.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderFailedEvent
import java.sql.Timestamp as SqlTimestamp
import java.time.Duration
import java.time.Instant

@Component
@ProcessingGroup("order-projection")
class OrderProjectionUpdater(
    @Qualifier("axonJdbcTemplate") private val jdbcTemplate: NamedParameterJdbcTemplate,
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
    @Transactional("axonSpringTransactionManager")
    fun on(event: OrderCreatedEvent, @Timestamp timestamp: Instant) {
        val itemsJson = event.items.joinToString(",", "{", "}") { "\"${it.itemId}\":${it.quantity}" }
        // created_at is set from the event's own timestamp (not now()) so e2e is measured against
        // admission time and stays correct on replay.
        jdbcTemplate.update(
            "INSERT INTO orders (order_id, user_id, status, items, created_at) VALUES (:orderId, :userId, 'PENDING', :items::jsonb, :createdAt) ON CONFLICT DO NOTHING",
            mapOf("orderId" to event.orderId, "userId" to event.userId, "items" to itemsJson, "createdAt" to SqlTimestamp.from(timestamp))
        )
        recordLag(timestamp, "OrderCreatedEvent")
    }

    @EventHandler
    @Transactional("axonSpringTransactionManager")
    fun on(event: OrderCompletedEvent, @Timestamp timestamp: Instant) {
        jdbcTemplate.update(
            "UPDATE orders SET status = 'CONFIRMED' WHERE order_id = :orderId",
            mapOf("orderId" to event.orderId)
        )
        recordE2e("confirmed", readCreatedAt(event.orderId), timestamp, event.orderId)
        recordLag(timestamp, "OrderCompletedEvent")
    }

    @EventHandler
    @Transactional("axonSpringTransactionManager")
    fun on(event: OrderFailedEvent, @Timestamp timestamp: Instant) {
        jdbcTemplate.update(
            "UPDATE orders SET status = 'REJECTED', failure_reason = :reason WHERE order_id = :orderId",
            mapOf("orderId" to event.orderId, "reason" to event.reason)
        )
        recordE2e("rejected", readCreatedAt(event.orderId), timestamp, event.orderId)
        recordLag(timestamp, "OrderFailedEvent")
    }

    private fun readCreatedAt(orderId: String): Instant? =
        jdbcTemplate.queryForList(
            "SELECT created_at FROM orders WHERE order_id = :orderId",
            mapOf("orderId" to orderId),
            SqlTimestamp::class.java
        ).firstOrNull()?.toInstant()

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
        log.info("[PROJECTION] table=orders type={} lag={}ms", eventType, lag.toMillis())
        meterRegistry.counter("es.events.processed", "eventType", eventType).increment()
    }
}
