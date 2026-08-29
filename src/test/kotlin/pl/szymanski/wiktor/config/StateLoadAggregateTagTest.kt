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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.TimeUnit
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

    private fun count(phase: String, aggregate: String, path: String = AggregateLoadPath.COMMAND): Long =
        registry.find("state_load_time")
            .tag("phase", phase).tag("aggregate", aggregate).tag("path", path)
            .timer()?.count() ?: 0L

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
     * The snapshot phase must still record — an empty lookup is a full JDBC round trip and is a real
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

    /**
     * The write path is the default, and it is the only path that gets a `load` phase: an aggregate
     * loaded straight from the store has no repository envelope, so the round trip IS the load.
     */
    @Test
    fun `a plain load is tagged path=command and carries a load phase equal to total`() {
        val engine = TimedEventStorageEngine(
            StubEngine(snapshot = null, events = listOf(event("OrderAggregate", 0))),
            registry,
        )

        load(engine, readSnapshotFirst = true)

        assertEquals(1L, count("load", "OrderAggregate"))
        val load = registry.find("state_load_time").tag("phase", "load").timer()!!
        val total = registry.find("state_load_time").tag("phase", "total").timer()!!
        assertEquals(
            total.totalTime(TimeUnit.NANOSECONDS), load.totalTime(TimeUnit.NANOSECONDS),
            "load must be the same measurement as total, not a second reading of the clock",
        )
    }

    /**
     * The cache repair. It runs on the losing command's thread but only after its append failed, so
     * it is the cost of the conflict and must not appear anywhere in the write path — including as a
     * `load` phase, which would count the repair as if state had been loaded to execute a command.
     */
    @Test
    fun `a repair-path read is tagged path=repair and emits no load phase`() {
        val engine = TimedEventStorageEngine(
            StubEngine(snapshot = null, events = listOf(event("InventoryItem", 31))),
            registry,
        )

        AggregateLoadPath.on(AggregateLoadPath.REPAIR) {
            load(engine, readSnapshotFirst = false, firstSequenceNumber = 31L)
        }

        listOf("events", "replay", "total").forEach {
            assertEquals(1L, count(it, "InventoryItem", AggregateLoadPath.REPAIR), "phase=$it should be path=repair")
            assertEquals(0L, count(it, "InventoryItem"), "phase=$it must not land on the command path")
        }
        assertEquals(0L, count("load", "InventoryItem", AggregateLoadPath.REPAIR))
        assertEquals(0L, count("load", "InventoryItem"))
    }

    /**
     * The snapshotter's fallback replay. It IS inline on the command thread before the insert, but it
     * is not the load that command performed, and at a 30-event threshold pooling it would add a full
     * replay to the write path on every 30th command.
     */
    @Test
    fun `a snapshot-path replay is tagged path=snapshot and emits no load phase`() {
        val engine = TimedEventStorageEngine(
            StubEngine(snapshot = null, events = listOf(event("OrderAggregate", 0))),
            registry,
        )

        AggregateLoadPath.on(AggregateLoadPath.SNAPSHOT) { load(engine, readSnapshotFirst = true) }

        assertEquals(1L, count("total", "OrderAggregate", AggregateLoadPath.SNAPSHOT))
        assertEquals(0L, count("total", "OrderAggregate"))
        assertEquals(0L, count("load", "OrderAggregate", AggregateLoadPath.SNAPSHOT))
    }

    /**
     * A caching repository times the whole load itself, over both arms. The store read is then only
     * part of it, so the engine must stay quiet about `load` or the phase would hold two different
     * measurements of the same population.
     */
    @Test
    fun `an enveloped load leaves the load phase to the repository`() {
        val engine = TimedEventStorageEngine(
            StubEngine(snapshot = null, events = listOf(event("InventoryItem", 0))),
            registry,
        )

        AggregateLoadPath.withEnvelope { load(engine, readSnapshotFirst = true) }

        assertEquals(1L, count("total", "InventoryItem"), "the phases are still recorded")
        assertEquals(0L, count("load", "InventoryItem"), "but the load phase belongs to the envelope")
    }

    /** A pooled thread must not carry a path or an envelope into its next task. */
    @Test
    fun `the path and envelope are restored after use`() {
        assertEquals(AggregateLoadPath.COMMAND, AggregateLoadPath.current)
        AggregateLoadPath.on(AggregateLoadPath.REPAIR) {
            assertEquals(AggregateLoadPath.REPAIR, AggregateLoadPath.current)
            AggregateLoadPath.on(AggregateLoadPath.SNAPSHOT) {
                assertEquals(AggregateLoadPath.SNAPSHOT, AggregateLoadPath.current)
            }
            assertEquals(AggregateLoadPath.REPAIR, AggregateLoadPath.current)
        }
        assertEquals(AggregateLoadPath.COMMAND, AggregateLoadPath.current)

        assertFalse(AggregateLoadPath.isEnveloped)
        AggregateLoadPath.withEnvelope {
            assertTrue(AggregateLoadPath.isEnveloped)
            AggregateLoadPath.withEnvelope { assertTrue(AggregateLoadPath.isEnveloped) }
            assertTrue(AggregateLoadPath.isEnveloped)
        }
        assertFalse(AggregateLoadPath.isEnveloped)
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
