package pl.szymanski.wiktor.publisher

import tools.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.OrderReservationCreatedEvent
import java.time.Duration
import java.time.Instant

@Component
class InventoryEventListener(
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @ApplicationModuleListener
    fun on(event: InventoryCreatedEvent) {
        val payload = objectMapper.writeValueAsString(event)
        log.info(
            "[MOCK-KAFKA] topic=inventory-events key={} type={} payload={}",
            event.id, event.javaClass.simpleName, payload,
        )
        recordLag(event.javaClass.simpleName, event.createdAt)
    }

    @ApplicationModuleListener
    fun on(event: InventoryReservedEvent) {
        val payload = objectMapper.writeValueAsString(event)
        log.info(
            "[MOCK-KAFKA] topic=inventory-events key={} type={} payload={}",
            event.id, event.javaClass.simpleName, payload,
        )
        recordLag(event.javaClass.simpleName, event.createdAt)
    }

    @ApplicationModuleListener
    fun on(event: OrderReservationCreatedEvent) {
        val payload = objectMapper.writeValueAsString(event)
        log.info(
            "[MOCK-KAFKA] topic=order-events key={} type={} payload={}",
            event.orderId, event.javaClass.simpleName, payload,
        )
        recordLag(event.javaClass.simpleName, event.createdAt)
    }

    private fun recordLag(eventType: String, createdAt: Instant) {
        Timer.builder("outbox.publish.lag")
            .tag("eventType", eventType)
            .publishPercentileHistogram(true)
            .maximumExpectedValue(Duration.ofMinutes(10))
            .register(meterRegistry)
            .record(Duration.between(createdAt, Instant.now()))
    }
}
