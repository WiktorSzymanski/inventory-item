package pl.szymanski.wiktor.subscription

import com.fasterxml.jackson.databind.ObjectMapper
import io.kurrent.dbclient.KurrentDBClient
import io.kurrent.dbclient.Position
import io.kurrent.dbclient.RecordedEvent
import io.kurrent.dbclient.ResolvedEvent
import io.kurrent.dbclient.SubscribeToAllOptions
import io.kurrent.dbclient.Subscription
import io.kurrent.dbclient.SubscriptionListener
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent

@Component
class InventoryProjectionSubscriber(
    private val client: KurrentDBClient,
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : ApplicationRunner {

    companion object {
        private const val READ_MODEL_NAME = "inventory-projection"
        private val log = LoggerFactory.getLogger(InventoryProjectionSubscriber::class.java)
    }

    private val projectionLagTimer: Timer = Timer.builder("projection.lag")
        .publishPercentileHistogram(true)
        .maximumExpectedValue(Duration.ofMinutes(10))
        .register(meterRegistry)

    override fun run(args: ApplicationArguments) {
        val options = runBlocking { buildSubscribeOptions() }
        client.subscribeToAll(
            object : SubscriptionListener() {
                override fun onEvent(subscription: Subscription, event: ResolvedEvent) {
                    val recorded = event.originalEvent
                    val isDomainEvent = !recorded.eventType.startsWith("$")
                    runBlocking {
                        transactionalOperator.executeAndAwait {
                            if (isDomainEvent) {
                                projectEvent(recorded)
                            }
                            saveCheckpoint(recorded.position)
                        }
                    }
                    if (isDomainEvent) {
                        val lag = Duration.between(recorded.created, Instant.now())
                        projectionLagTimer.record(lag)
                        log.info(
                            "[PROJECTION] table=inventory_state key={} type={} lag={}ms",
                            recorded.streamId, recorded.eventType, lag.toMillis(),
                        )
                        meterRegistry.counter("es.events.processed", "eventType", recorded.eventType).increment()
                    }
                }

                override fun onCancelled(subscription: Subscription, exception: Throwable?) {
                    if (exception != null) {
                        log.error("Subscription dropped", exception)
                    }
                }
            },
            options,
        ).get()
        log.info("Subscribed to KurrentDB \$all")
    }

    private suspend fun buildSubscribeOptions(): SubscribeToAllOptions {
        val row = databaseClient.sql(
            "SELECT commit_position, prepare_position FROM checkpoints WHERE read_model_name = :name"
        )
            .bind("name", READ_MODEL_NAME)
            .fetch()
            .first()
            .awaitSingleOrNull()

        return if (row != null) {
            val commitPosition = row["commit_position"] as Long
            val preparePosition = row["prepare_position"] as Long
            log.info("Resuming projection from checkpoint commitPosition={}", commitPosition)
            SubscribeToAllOptions.get().fromPosition(Position(commitPosition, preparePosition))
        } else {
            log.info("No checkpoint found, subscribing from start")
            SubscribeToAllOptions.get().fromStart()
        }
    }

    private suspend fun projectEvent(recorded: RecordedEvent) {
        val revision = recorded.revision
        when (recorded.eventType) {
            "InventoryCreatedEvent" -> {
                val e = objectMapper.readValue(recorded.eventData, InventoryCreatedEvent::class.java)
                databaseClient.sql(
                    """
                    INSERT INTO inventory_state (item_id, available_qty, reservations, last_event_revision)
                    VALUES (:itemId, :qty, '{}', :revision)
                    ON CONFLICT (item_id) DO UPDATE
                        SET available_qty        = EXCLUDED.available_qty,
                            last_event_revision  = EXCLUDED.last_event_revision
                    WHERE inventory_state.last_event_revision < EXCLUDED.last_event_revision
                    """.trimIndent()
                )
                    .bind("itemId", e.id)
                    .bind("qty", e.quantity)
                    .bind("revision", revision)
                    .fetch().rowsUpdated().awaitSingle()
            }

            "InventoryReservedEvent" -> {
                val e = objectMapper.readValue(recorded.eventData, InventoryReservedEvent::class.java)
                val reservationJson = objectMapper.writeValueAsString(mapOf(e.reservationId to e.quantity))
                databaseClient.sql(
                    """
                    UPDATE inventory_state
                    SET available_qty       = available_qty - :qty,
                        reservations        = reservations || :reservationJson::jsonb,
                        last_event_revision = :revision
                    WHERE item_id = :itemId AND last_event_revision < :revision
                    """.trimIndent()
                )
                    .bind("qty", e.quantity)
                    .bind("reservationJson", reservationJson)
                    .bind("revision", revision)
                    .bind("itemId", e.id)
                    .fetch().rowsUpdated().awaitSingle()
            }
        }
    }

    private suspend fun saveCheckpoint(position: Position) {
        databaseClient.sql(
            """
            INSERT INTO checkpoints (read_model_name, commit_position, prepare_position)
            VALUES (:name, :commitPosition, :preparePosition)
            ON CONFLICT (read_model_name) DO UPDATE
                SET commit_position  = EXCLUDED.commit_position,
                    prepare_position = EXCLUDED.prepare_position
            """.trimIndent()
        )
            .bind("name", READ_MODEL_NAME)
            .bind("commitPosition", position.commitUnsigned)
            .bind("preparePosition", position.prepareUnsigned)
            .fetch().rowsUpdated().awaitSingle()
    }
}
