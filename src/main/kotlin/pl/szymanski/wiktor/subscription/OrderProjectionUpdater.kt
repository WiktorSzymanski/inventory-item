package pl.szymanski.wiktor.subscription

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.axonframework.eventhandling.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderFailedEvent
import java.time.Duration
import java.time.Instant

@Component
@ProcessingGroup("order-projection")
class OrderProjectionUpdater(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private val log = LoggerFactory.getLogger(OrderProjectionUpdater::class.java)
    }

    private val projectionLagTimer: Timer = Timer.builder("order.projection.lag")
        .publishPercentileHistogram(true)
        .maximumExpectedValue(Duration.ofMinutes(10))
        .register(meterRegistry)

    @EventHandler
    @Transactional
    fun on(event: OrderCreatedEvent, @Timestamp timestamp: Instant) {
        jdbcTemplate.update(
            "INSERT INTO orders (order_id, user_id, status) VALUES (:orderId, :userId, 'PENDING') ON CONFLICT DO NOTHING",
            mapOf("orderId" to event.orderId, "userId" to event.userId)
        )
        recordLag(timestamp, "OrderCreatedEvent")
    }

    @EventHandler
    @Transactional
    fun on(event: OrderCompletedEvent, @Timestamp timestamp: Instant) {
        jdbcTemplate.update(
            "UPDATE orders SET status = 'COMPLETED' WHERE order_id = :orderId",
            mapOf("orderId" to event.orderId)
        )
        recordLag(timestamp, "OrderCompletedEvent")
    }

    @EventHandler
    @Transactional
    fun on(event: OrderFailedEvent, @Timestamp timestamp: Instant) {
        jdbcTemplate.update(
            "UPDATE orders SET status = 'FAILED' WHERE order_id = :orderId",
            mapOf("orderId" to event.orderId)
        )
        recordLag(timestamp, "OrderFailedEvent")
    }

    private fun recordLag(timestamp: Instant, eventType: String) {
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info("[PROJECTION] table=orders type={} lag={}ms", eventType, lag.toMillis())
        meterRegistry.counter("es.events.processed", "eventType", eventType).increment()
    }
}
