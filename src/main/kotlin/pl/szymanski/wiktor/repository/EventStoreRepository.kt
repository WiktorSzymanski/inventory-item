package pl.szymanski.wiktor.repository

import com.fasterxml.jackson.databind.ObjectMapper
import io.kurrent.dbclient.AppendToStreamOptions
import io.kurrent.dbclient.EventData
import io.kurrent.dbclient.KurrentDBClient
import io.kurrent.dbclient.ReadStreamOptions
import io.kurrent.dbclient.StreamNotFoundException
import io.kurrent.dbclient.StreamState
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Repository
import pl.szymanski.wiktor.domain.InventoryCreatedEvent
import pl.szymanski.wiktor.domain.InventoryEvent
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.exception.NotFoundException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@Repository
class EventStoreRepository(
    private val client: KurrentDBClient,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val dbFetchTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .register(meterRegistry)

    private val aggregateRecreationTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "aggregate_recreation")
        .register(meterRegistry)

    private val totalLoadTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "total")
        .register(meterRegistry)

    private val eventsAppliedSummary: DistributionSummary = DistributionSummary.builder("events_applied_count")
        .tag("variant", "es")
        .tag("method", "rebuild")
        .serviceLevelObjectives(1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0)
        .register(meterRegistry)

    private val appendSuccessCounter = meterRegistry.counter("inventory.append.success")

    suspend fun loadAggregate(itemId: String): InventoryItem = withContext(Dispatchers.IO) {
        val options = ReadStreamOptions.get().forwards().fromStart()
        val totalStartNs = System.nanoTime()
        try {
            val dbStartNs = System.nanoTime()
            val result = client.readStream(streamName(itemId), options).get()
            dbFetchTimer.record(System.nanoTime() - dbStartNs, TimeUnit.NANOSECONDS)

            val reconstructStartNs = System.nanoTime()
            val events = result.events.mapNotNull { resolvedEvent ->
                val recorded = resolvedEvent.originalEvent
                when (recorded.eventType) {
                    "InventoryCreatedEvent" -> objectMapper.readValue(recorded.eventData, InventoryCreatedEvent::class.java)
                        .copy(revision = recorded.revision)
                    "InventoryReservedEvent" -> objectMapper.readValue(recorded.eventData, InventoryReservedEvent::class.java)
                        .copy(revision = recorded.revision)
                    else -> null
                }
            }
            val item = InventoryItem.reconstruct(events)
            aggregateRecreationTimer.record(System.nanoTime() - reconstructStartNs, TimeUnit.NANOSECONDS)

            totalLoadTimer.record(System.nanoTime() - totalStartNs, TimeUnit.NANOSECONDS)
            eventsAppliedSummary.record(events.size.toDouble())
            item
        } catch (e: ExecutionException) {
            when (e.cause) {
                is StreamNotFoundException -> {
                    totalLoadTimer.record(System.nanoTime() - totalStartNs, TimeUnit.NANOSECONDS)
                    eventsAppliedSummary.record(0.0)
                    throw NotFoundException("Item $itemId not found")
                }
                else -> throw e.cause ?: e
            }
        }
    }

    suspend fun appendEvent(
        event: InventoryEvent,
    ): Unit = withContext(Dispatchers.IO) {
        val eventData = EventData
            .builderAsJson(UUID.randomUUID(), event.javaClass.simpleName, objectMapper.writeValueAsBytes(event))
            .build()

        val options = if (event.revision == InventoryItem.NO_STREAM) {
            AppendToStreamOptions.get().streamState(StreamState.noStream())
        } else {
            AppendToStreamOptions.get().streamRevision(event.revision)
        }

        try {
            client.appendToStream(streamName(event.id), options, eventData).get()
            appendSuccessCounter.increment()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun streamName(itemId: String) = "inventory-$itemId"
}
