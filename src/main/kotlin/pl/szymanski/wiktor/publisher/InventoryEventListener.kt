package pl.szymanski.wiktor.publisher

import tools.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryReservationFailedEvent
import pl.szymanski.wiktor.domain.InventoryReservationReleasedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderFailedEvent
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class InventoryEventListener(
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val lagTimers = ConcurrentHashMap<String, Timer>()

    @ApplicationModuleListener
    fun on(event: InventoryCreatedEvent) = publish("inventory-events", event.id, event, event.createdAt)

    @ApplicationModuleListener
    fun on(event: InventoryReservedEvent) = publish("inventory-events", event.id, event, event.createdAt)

    // The two saga-only events. Published for the same reason the others are — a downstream
    // consumer sees the whole stock story, not just the half that succeeded — and each one adds a
    // SECOND event_publication row per occurrence, because the saga itself listens to them too.
    // That doubling is a real cost of the pattern on this branch and is deliberately not optimised
    // away by folding the two listeners into one.
    @ApplicationModuleListener
    fun on(event: InventoryReservationFailedEvent) = publish("inventory-events", event.id, event, event.createdAt)

    @ApplicationModuleListener
    fun on(event: InventoryReservationReleasedEvent) = publish("inventory-events", event.id, event, event.createdAt)

    @ApplicationModuleListener
    fun on(event: OrderCreatedEvent) = publish("order-events", event.orderId, event, event.createdAt)

    @ApplicationModuleListener
    fun on(event: OrderCompletedEvent) = publish("order-events", event.orderId, event, event.createdAt)

    @ApplicationModuleListener
    fun on(event: OrderFailedEvent) = publish("order-events", event.orderId, event, event.createdAt)

    private fun publish(topic: String, key: String, event: Any, createdAt: Instant) {
        val payload = objectMapper.writeValueAsString(event)
        log.info(
            "[MOCK-KAFKA] topic={} key={} type={} payload={}",
            topic, key, event.javaClass.simpleName, payload,
        )
        recordLag(event.javaClass.simpleName, createdAt)
    }

    private fun recordLag(eventType: String, createdAt: Instant) {
        lagTimers.computeIfAbsent(eventType) {
            Timer.builder("publish.lag")
                .tag("eventType", it)
                .publishPercentileHistogram(true)
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(meterRegistry)
        }.record(Duration.between(createdAt, Instant.now()))
    }
}
