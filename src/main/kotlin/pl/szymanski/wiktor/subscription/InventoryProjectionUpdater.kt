package pl.szymanski.wiktor.subscription

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.axonframework.eventhandling.EventHandler
import org.axonframework.eventhandling.SequenceNumber
import org.axonframework.eventhandling.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.axonframework.config.ProcessingGroup
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import java.time.Duration
import java.time.Instant

@Component
@ProcessingGroup("inventory-projection")
class InventoryProjectionUpdater(
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator,
    private val meterRegistry: MeterRegistry,
) {
    companion object {
        private val log = LoggerFactory.getLogger(InventoryProjectionUpdater::class.java)
    }

    private val projectionLagTimer: Timer = Timer.builder("projection.lag")
        .publishPercentileHistogram(true)
        .maximumExpectedValue(Duration.ofMinutes(10))
        .register(meterRegistry)

    @EventHandler
    fun on(event: InventoryCreatedEvent, @SequenceNumber seqNo: Long, @Timestamp timestamp: Instant) {
        runBlocking {
            transactionalOperator.executeAndAwait {
                databaseClient.sql(
                    """
                    INSERT INTO inventory_state (item_id, available_qty, reservations, last_event_revision)
                    VALUES (:itemId, :qty, '{}', :revision)
                    ON CONFLICT (item_id) DO UPDATE
                        SET available_qty       = EXCLUDED.available_qty,
                            last_event_revision = EXCLUDED.last_event_revision
                    WHERE inventory_state.last_event_revision < EXCLUDED.last_event_revision
                    """.trimIndent()
                )
                    .bind("itemId", event.id)
                    .bind("qty", event.quantity)
                    .bind("revision", seqNo)
                    .fetch().rowsUpdated().awaitSingle()
            }
        }
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info(
            "[PROJECTION] table=inventory_state key={} type=InventoryCreatedEvent lag={}ms",
            event.id, lag.toMillis(),
        )
        meterRegistry.counter("es.events.processed", "eventType", "InventoryCreatedEvent").increment()
    }

    @EventHandler
    fun on(event: InventoryReservedEvent, @SequenceNumber seqNo: Long, @Timestamp timestamp: Instant) {
        runBlocking {
            transactionalOperator.executeAndAwait {
                val reservationJson = """{"${event.reservationId}":${event.quantity}}"""
                databaseClient.sql(
                    """
                    UPDATE inventory_state
                    SET available_qty       = available_qty - :qty,
                        reservations        = reservations || :reservationJson::jsonb,
                        last_event_revision = :revision
                    WHERE item_id = :itemId AND last_event_revision < :revision
                    """.trimIndent()
                )
                    .bind("qty", event.quantity)
                    .bind("reservationJson", reservationJson)
                    .bind("revision", seqNo)
                    .bind("itemId", event.id)
                    .fetch().rowsUpdated().awaitSingle()
            }
        }
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info(
            "[PROJECTION] table=inventory_state key={} type=InventoryReservedEvent lag={}ms",
            event.id, lag.toMillis(),
        )
        meterRegistry.counter("es.events.processed", "eventType", "InventoryReservedEvent").increment()
    }
}
