package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.axonframework.eventhandling.DomainEventMessage
import org.axonframework.eventhandling.EventMessage
import org.axonframework.eventhandling.GenericDomainEventMessage
import org.axonframework.eventhandling.TrackedEventMessage
import org.axonframework.eventhandling.TrackingToken
import org.axonframework.eventsourcing.eventstore.DomainEventStream
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.stream.Stream

/**
 * `state_load_time` carries no aggregate label until this test's contract holds, and without one the
 * panel sums two unrelated populations into the same histogram buckets: on a branch with the
 * copy-on-write cache the InventoryItem loads disappear (they never reach the store) while the
 * uncached OrderAggregate loads remain, so the same series means a different thing per branch and
 * the p50s are not comparable.
 *
 * The tag is resolved from [DomainEventMessage.getType], which is the Axon aggregate type and is
 * carried by both the snapshot message and every event message. It cannot be known when
 * `readSnapshot` STARTS, and for an aggregate that has never been snapshotted it is not known when
 * that call RETURNS either — hence the deferred recording these tests pin down.
 */
class StateLoadAggregateTagTest {

    private val registry = SimpleMeterRegistry()

    /** Minimal engine: everything the wrapper delegates that these tests do not exercise is inert. */
    private class StubEngine(
        private val snapshot: DomainEventMessage<*>? = null,
        private val events: List<DomainEventMessage<*>> = emptyList(),
    ) : EventStorageEngine {
        override fun appendEvents(events: MutableList<out EventMessage<*>>) = Unit
        override fun storeSnapshot(snapshot: DomainEventMessage<*>) = Unit
        override fun readEvents(trackingToken: TrackingToken?, mayBlock: Boolean): Stream<out TrackedEventMessage<*>> =
            Stream.empty()

        override fun readEvents(aggregateIdentifier: String, firstSequenceNumber: Long): DomainEventStream =
            DomainEventStream.of(events)

        override fun readSnapshot(aggregateIdentifier: String): Optional<DomainEventMessage<*>> =
            Optional.ofNullable(snapshot)
    }

    private fun event(type: String, seq: Long) =
        GenericDomainEventMessage(type, "agg-1", seq, "payload-$seq")

    private fun count(phase: String, aggregate: String): Long =
        registry.find("state_load_time").tag("phase", phase).tag("aggregate", aggregate).timer()?.count() ?: 0L

    /** Drives one aggregate load the way `AbstractEventStore` does: snapshot first, then the tail. */
    private fun load(engine: TimedEventStorageEngine, readSnapshotFirst: Boolean, firstSequenceNumber: Long = 0L) {
        if (readSnapshotFirst) engine.readSnapshot("agg-1")
        val stream = engine.readEvents("agg-1", firstSequenceNumber)
        while (stream.hasNext()) stream.next()
    }

    @Test
    fun `a snapshotted load is tagged with the aggregate type carried by the snapshot`() {
        val engine = TimedEventStorageEngine(
            StubEngine(snapshot = event("InventoryItem", 29), events = listOf(event("InventoryItem", 30))),
            registry,
        )

        load(engine, readSnapshotFirst = true)

        listOf("snapshot", "events", "replay", "total").forEach {
            assertEquals(1L, count(it, "InventoryItem"), "phase=$it should be tagged InventoryItem")
        }
    }

    /**
     * The OrderAggregate case, and the reason the snapshot phase cannot record at its own call site:
     * `readSnapshot` returns empty here, so the type only becomes known once the events arrive.
     * The snapshot phase must still record — an empty lookup is a full round trip to the server and is a real
     * part of the load's cost.
     */
    @Test
    fun `a load with no snapshot row is tagged from the first event, snapshot phase included`() {
        val engine = TimedEventStorageEngine(
            StubEngine(snapshot = null, events = listOf(event("OrderAggregate", 0))),
            registry,
        )

        load(engine, readSnapshotFirst = true)

        listOf("snapshot", "events", "replay", "total").forEach {
            assertEquals(1L, count(it, "OrderAggregate"), "phase=$it should be tagged OrderAggregate")
        }
        assertEquals(0L, count("snapshot", "InventoryItem"))
    }

    /**
     * A delta read with no preceding snapshot lookup, coming back empty: nothing in the call
     * identifies the aggregate. On ES-4 this is the probe `PessimisticCachingRepository.catchUp`
     * fires on every rollback, which is empty far more often than not. These samples are not
     * aggregate loads at all and must not be folded into either aggregate's numbers.
     */
    @Test
    fun `an empty delta read with no snapshot lands under unknown, not under an aggregate`() {
        val engine = TimedEventStorageEngine(StubEngine(), registry)

        load(engine, readSnapshotFirst = false, firstSequenceNumber = 31L)

        listOf("events", "replay", "total").forEach {
            assertEquals(1L, count(it, "unknown"), "phase=$it should be tagged unknown")
        }
        assertEquals(0L, count("snapshot", "unknown"), "no snapshot was read, so no snapshot sample")
        assertEquals(0L, count("total", "InventoryItem"))
        assertEquals(0L, count("total", "OrderAggregate"))
    }

    /**
     * A non-empty delta DOES identify its aggregate, so it is tagged rather than left unknown. It is
     * a store read on that aggregate's stream, which is what the phase measures.
     */
    @Test
    fun `a non-empty delta read is tagged from its events`() {
        val engine = TimedEventStorageEngine(
            StubEngine(snapshot = null, events = listOf(event("InventoryItem", 31))),
            registry,
        )

        load(engine, readSnapshotFirst = false, firstSequenceNumber = 31L)

        assertEquals(1L, count("total", "InventoryItem"))
        assertEquals(0L, count("total", "unknown"))
    }

    /** The deferred snapshot sample must not leak into the next load on the same thread. */
    @Test
    fun `consecutive loads on one thread do not carry state across`() {
        val order = TimedEventStorageEngine(
            StubEngine(snapshot = null, events = listOf(event("OrderAggregate", 0))),
            registry,
        )
        val inventory = TimedEventStorageEngine(
            StubEngine(snapshot = event("InventoryItem", 29), events = emptyList()),
            registry,
        )

        load(order, readSnapshotFirst = true)
        load(inventory, readSnapshotFirst = true)
        load(order, readSnapshotFirst = true)

        assertEquals(2L, count("total", "OrderAggregate"))
        assertEquals(1L, count("total", "InventoryItem"))
        assertEquals(0L, count("total", "unknown"))
    }
}
