package pl.szymanski.wiktor.subscription

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.axonframework.eventhandling.SequenceNumber
import org.axonframework.eventhandling.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryReservationFailedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import java.time.Duration
import java.time.Instant

@Component
@ProcessingGroup("inventory-projection")
class InventoryProjectionUpdater(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private val log = LoggerFactory.getLogger(InventoryProjectionUpdater::class.java)
    }

    private val projectionLagTimer: Timer = Timer.builder("projection.lag")
        .publishPercentileHistogram(true)
        .maximumExpectedValue(Duration.ofMinutes(10))
        .register(meterRegistry)

    // Counts every successful reservation regardless of call path (direct reserve or saga).
    private val appendSuccessCounter: Counter =
        Counter.builder("inventory.append.success").register(meterRegistry)

    // Counts saga-path insufficient-stock failures as business exceptions, consistent with
    // the GlobalExceptionHandler's inventory_exception_total{type} metric.
    private val insufficientStockCounter: Counter =
        Counter.builder("inventory.exception").tag("type", "InsufficientStockException").register(meterRegistry)

    @EventHandler
    @Transactional
    fun on(event: InventoryCreatedEvent, @SequenceNumber seqNo: Long, @Timestamp timestamp: Instant) {
        jdbcTemplate.update(
            """
            INSERT INTO inventory_state (item_id, available_qty, reservations, last_event_revision)
            VALUES (:itemId, :qty, '{}', :revision)
            ON CONFLICT (item_id) DO UPDATE
                SET available_qty       = EXCLUDED.available_qty,
                    last_event_revision = EXCLUDED.last_event_revision
            WHERE inventory_state.last_event_revision < EXCLUDED.last_event_revision
            """.trimIndent(),
            mapOf("itemId" to event.id, "qty" to event.quantity, "revision" to seqNo)
        )
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info(
            "[PROJECTION] table=inventory_state key={} type=InventoryCreatedEvent lag={}ms",
            event.id, lag.toMillis(),
        )
        meterRegistry.counter("es.events.processed", "eventType", "InventoryCreatedEvent").increment()
    }

    @EventHandler
    @Transactional
    fun on(event: InventoryReservedEvent, @SequenceNumber seqNo: Long, @Timestamp timestamp: Instant) {
        val reservationJson = """{"${event.reservationId}":${event.quantity}}"""
        jdbcTemplate.update(
            """
            UPDATE inventory_state
            SET available_qty       = available_qty - :qty,
                reservations        = reservations || :reservationJson::jsonb,
                last_event_revision = :revision
            WHERE item_id = :itemId AND last_event_revision < :revision
            """.trimIndent(),
            mapOf(
                "qty" to event.quantity,
                "reservationJson" to reservationJson,
                "revision" to seqNo,
                "itemId" to event.id,
            )
        )
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info(
            "[PROJECTION] table=inventory_state key={} type=InventoryReservedEvent lag={}ms",
            event.id, lag.toMillis(),
        )
        meterRegistry.counter("es.events.processed", "eventType", "InventoryReservedEvent").increment()
        appendSuccessCounter.increment()
    }

    @EventHandler
    fun on(event: InventoryReservationFailedEvent, @Timestamp timestamp: Instant) {
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info(
            "[PROJECTION] reservation failed itemId={} reservationId={} reason={} lag={}ms",
            event.id, event.reservationId, event.reason, lag.toMillis(),
        )
        meterRegistry.counter("es.events.processed", "eventType", "InventoryReservationFailedEvent").increment()
        insufficientStockCounter.increment()
    }
}
