package pl.szymanski.wiktor.subscription

import com.mongodb.DuplicateKeyException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.axonframework.eventhandling.SequenceNumber
import org.axonframework.eventhandling.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException as SpringDuplicateKeyException
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryReservationFailedEvent
import pl.szymanski.wiktor.domain.InventoryReservationReleasedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.repository.InventoryProjection
import java.time.Duration
import java.time.Instant

/**
 * The inventory read model, maintained from the event stream.
 *
 * Two things changed from ES-2 and nothing else did. Every metric name, tag and log line is
 * identical, because the projection is not what this branch is comparing.
 *
 * **1. The statements.** ES-2 writes raw SQL through a `NamedParameterJdbcTemplate` on the Axon
 * pool; here the same three writes are Mongo update operations. The `last_event_revision`
 * monotonic guard survives verbatim as a `lastEventRevision < revision` predicate, so the
 * handlers stay idempotent under at-least-once delivery and safe to replay. Postgres' atomic
 * `available_qty = available_qty - :qty` becomes `$inc`, which is atomic in the same sense --
 * the read and the write happen inside the server, so two concurrent decrements cannot lose one
 * another.
 *
 * **2. `@Transactional` is gone.** On ES-2 each handler ran in a transaction on the Axon
 * `DataSource` so that its single UPDATE could not be seen half-applied. Every handler here
 * writes exactly ONE document, and a single-document write in MongoDB is atomic on its own;
 * wrapping it in a session would add a round trip and a WriteConflict surface for no guarantee
 * that is not already held. This is a real behavioural difference from the parent branch and is
 * written down rather than left to be inferred. The write path is a different matter -- see
 * [pl.szymanski.wiktor.config.AxonConfig], where transactions ARE used, because an event append
 * genuinely spans several documents.
 */
@Component
@ProcessingGroup("inventory-projection")
class InventoryProjectionUpdater(
    private val mongo: MongoOperations,
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
    fun on(event: InventoryCreatedEvent, @SequenceNumber seqNo: Long, @Timestamp timestamp: Instant) {
        // The Mongo spelling of `INSERT ... ON CONFLICT (item_id) DO UPDATE ... WHERE
        // inventory_state.last_event_revision < EXCLUDED.last_event_revision`.
        //
        // The filter carries the revision guard, so on a re-delivered or stale event it matches
        // nothing -- and an upsert that matches nothing INSERTS. The insert then collides with
        // the existing _id and the server raises a duplicate key. That rejection IS the
        // "DO NOTHING" arm of the SQL, so it is swallowed rather than propagated: letting it
        // escape would park the event and stall the whole inventory-projection processor.
        try {
            mongo.upsert(
                Query(where("id").`is`(event.id).and("lastEventRevision").lt(seqNo)),
                Update()
                    .set("availableQty", event.quantity)
                    .set("lastEventRevision", seqNo),
                InventoryProjection::class.java,
            )
        } catch (e: SpringDuplicateKeyException) {
            log.debug("[PROJECTION] stale InventoryCreatedEvent ignored itemId={} revision={}", event.id, seqNo)
        } catch (e: DuplicateKeyException) {
            log.debug("[PROJECTION] stale InventoryCreatedEvent ignored itemId={} revision={}", event.id, seqNo)
        }
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info(
            "[PROJECTION] collection=inventory_state key={} type=InventoryCreatedEvent lag={}ms",
            event.id, lag.toMillis(),
        )
        meterRegistry.counter("es.events.processed", "eventType", "InventoryCreatedEvent").increment()
    }

    @EventHandler
    fun on(event: InventoryReservedEvent, @SequenceNumber seqNo: Long, @Timestamp timestamp: Instant) {
        applyDelta(event.id, -event.quantity, seqNo)
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info(
            "[PROJECTION] collection=inventory_state key={} type=InventoryReservedEvent lag={}ms",
            event.id, lag.toMillis(),
        )
        meterRegistry.counter("es.events.processed", "eventType", "InventoryReservedEvent").increment()
        appendSuccessCounter.increment()
    }

    @EventHandler
    fun on(event: InventoryReservationReleasedEvent, @SequenceNumber seqNo: Long, @Timestamp timestamp: Instant) {
        applyDelta(event.id, event.quantity, seqNo)
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info(
            "[PROJECTION] collection=inventory_state key={} type=InventoryReservationReleasedEvent lag={}ms",
            event.id, lag.toMillis(),
        )
        meterRegistry.counter("es.events.processed", "eventType", "InventoryReservationReleasedEvent").increment()
    }

    @EventHandler
    fun on(event: InventoryReservationFailedEvent, @Timestamp timestamp: Instant) {
        val lag = Duration.between(timestamp, Instant.now())
        projectionLagTimer.record(lag)
        log.info(
            "[PROJECTION] reservation failed itemId={} reason={} lag={}ms",
            event.id, event.reason, lag.toMillis(),
        )
        meterRegistry.counter("es.events.processed", "eventType", "InventoryReservationFailedEvent").increment()
        insufficientStockCounter.increment()
    }

    /**
     * `UPDATE inventory_state SET available_qty = available_qty +/- :qty, last_event_revision =
     * :revision WHERE item_id = :itemId AND last_event_revision < :revision`.
     *
     * No upsert here, matching the SQL: a reserve or release for an item the projection has not
     * created yet is a no-op, and the create event will bring the row into existence with the
     * correct quantity when it arrives.
     */
    private fun applyDelta(itemId: String, delta: Int, revision: Long) {
        mongo.updateFirst(
            Query(where("id").`is`(itemId).and("lastEventRevision").lt(revision)),
            Update()
                .inc("availableQty", delta)
                .set("lastEventRevision", revision),
            InventoryProjection::class.java,
        )
    }
}
