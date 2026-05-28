package pl.szymanski.wiktor.subscription

import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.axonframework.eventhandling.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderFailedEvent
import java.time.Duration
import java.time.Instant

@Component
@ProcessingGroup("mock-kafka-publisher")
class MockKafkaPublisher(
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private val log = LoggerFactory.getLogger(MockKafkaPublisher::class.java)
    }

    @EventHandler
    fun on(event: InventoryCreatedEvent, @Timestamp timestamp: Instant) {
        val lag = Duration.between(timestamp, Instant.now())
        log.info(
            "[MOCK-KAFKA] topic=inventory-events key={} type=InventoryCreatedEvent lag={}ms",
            event.id, lag.toMillis(),
        )
        meterRegistry.timer("publish.lag", "eventType", "InventoryCreatedEvent").record(lag)
    }

    @EventHandler
    fun on(event: InventoryReservedEvent, @Timestamp timestamp: Instant) {
        val lag = Duration.between(timestamp, Instant.now())
        log.info(
            "[MOCK-KAFKA] topic=inventory-events key={} type=InventoryReservedEvent lag={}ms",
            event.id, lag.toMillis(),
        )
        meterRegistry.timer("publish.lag", "eventType", "InventoryReservedEvent").record(lag)
    }

    @EventHandler
    fun on(event: OrderCreatedEvent, @Timestamp timestamp: Instant) {
        val lag = Duration.between(timestamp, Instant.now())
        log.info("[MOCK-KAFKA] topic=order-events key={} type=OrderCreatedEvent lag={}ms", event.orderId, lag.toMillis())
        meterRegistry.timer("publish.lag", "eventType", "OrderCreatedEvent").record(lag)
    }

    @EventHandler
    fun on(event: OrderCompletedEvent, @Timestamp timestamp: Instant) {
        val lag = Duration.between(timestamp, Instant.now())
        log.info("[MOCK-KAFKA] topic=order-events key={} type=OrderCompletedEvent lag={}ms", event.orderId, lag.toMillis())
        meterRegistry.timer("publish.lag", "eventType", "OrderCompletedEvent").record(lag)
    }

    @EventHandler
    fun on(event: OrderFailedEvent, @Timestamp timestamp: Instant) {
        val lag = Duration.between(timestamp, Instant.now())
        log.info("[MOCK-KAFKA] topic=order-events key={} type=OrderFailedEvent lag={}ms", event.orderId, lag.toMillis())
        meterRegistry.timer("publish.lag", "eventType", "OrderFailedEvent").record(lag)
    }
}
