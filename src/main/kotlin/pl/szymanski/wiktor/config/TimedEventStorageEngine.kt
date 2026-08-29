package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.eventhandling.DomainEventMessage
import org.axonframework.eventhandling.EventMessage
import org.axonframework.eventhandling.TrackingToken
import org.axonframework.eventsourcing.eventstore.DomainEventStream
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ES-2: records state_load_time{phase} for aggregate load breakdown:
//   snapshot — JDBC read of the snapshot row
//   events   — JDBC fetch of all event rows (eager, before replay starts)
//   replay   — in-memory @EventSourcingHandler application of buffered events
//   total    — end-to-end from first I/O call to aggregate fully replayed
//   load     — the whole write-path load for an aggregate with no repository envelope; see below
//
// Every sample also carries {aggregate}, the Axon aggregate type. Without it the four phases sum
// two unrelated populations into one histogram: this application loads OrderAggregate from the
// store on every order AND InventoryItem on every reserve, so a branch that caches InventoryItem
// silently changes what the panel's p50 describes rather than changing its value. A third value,
// "unknown", is emitted where nothing in the call identifies an aggregate — see [readEvents].
//
// And a {path}, which says whether the load is the write path at all: this engine sees every store
// round trip, and on ES-4 the cache-repair and snapshot-replay paths reach it too. See
// [AggregateLoadPath]. Read the write path as path="command"; note that an absent label satisfies
// path!="repair", so a query written that way also resolves against branches and archived runs
// that predate the tag.
class TimedEventStorageEngine(
    private val delegate: EventStorageEngine,
    private val meterRegistry: MeterRegistry,
) : EventStorageEngine by delegate {

    // Registered on demand rather than up front: the aggregate types are not known at construction,
    // and Micrometer would dedupe by meter id anyway. The map is here to keep the load path off the
    // registry's own lookup, which builds a Tags list per call.
    private val timers = ConcurrentHashMap<String, Timer>()

    private fun timer(phase: String, aggregate: String, path: String): Timer =
        timers.computeIfAbsent("$phase|$aggregate|$path") {
            Timer.builder("state_load_time")
                .tag("phase", phase).tag("aggregate", aggregate).tag("path", path)
                .register(meterRegistry)
        }

    // ES write path: times the synchronous JDBC INSERT of appended event rows.
    // Mirrors the TO branch's state_persist_time{source=db_write} for TO-vs-ES write-cost comparison.
    private val appendTimer = Timer.builder("state_persist_time").tag("source", "db_write").register(meterRegistry)

    /**
     * One aggregate load, spanning readSnapshot → readEvents → stream exhaustion. JDBC aggregate
     * loading is synchronous on a single IO thread, so a ThreadLocal is safe here.
     *
     * [snapshotNanos] is a raw elapsed time rather than a [Timer.Sample] because the snapshot phase
     * cannot be RECORDED at its own call site: the aggregate type is unknown when `readSnapshot`
     * starts, and for an aggregate that has never been snapshotted it is still unknown when that
     * call returns — `readSnapshot` hands back an empty Optional carrying no type. The measurement
     * is taken there and the sample is recorded in [readEvents], once the events reveal the type.
     */
    private class Session(val total: Timer.Sample) {
        var snapshotNanos: Long = -1
        var snapshotType: String? = null
    }

    /**
     * The `load` phase answers one question — what did materialising this aggregate cost the command
     * before it appended — with ONE population, so that it can be read without first knowing whether
     * the branch caches, whether this load hit, or how the phases decompose.
     *
     * Where a caching repository wraps the load it records that phase itself, over its whole
     * `doLoadWithLock`: the cache-hit arm never reaches this class, and the miss arm costs more than
     * the store round trip alone (validation, seeding the cache with a copy of what was read). For an
     * aggregate loaded straight from the store — OrderAggregate here — there is no such envelope and
     * no such extra, so the round trip measured below IS the load, and it is emitted from here.
     * [AggregateLoadPath.isEnveloped] is what keeps exactly one of the two recording per load.
     */
    private fun shouldEmitLoadPhase(path: String) = path == AggregateLoadPath.COMMAND && !AggregateLoadPath.isEnveloped

    private val loadSession = ThreadLocal<Session>()

    // Kotlin `by` delegation does not forward Java `default` interface methods — it inherits
    // the default body instead of calling delegate.method(). These three methods are `default`
    // in EventStorageEngine (all throw UnsupportedOperationException); AbstractEventStorageEngine
    // overrides them with real implementations that JdbcEventStorageEngine inherits. Without
    // these explicit overrides the TrackingEventProcessors fail to initialise their segments.
    override fun createHeadToken(): TrackingToken? = delegate.createHeadToken()
    override fun createTailToken(): TrackingToken? = delegate.createTailToken()
    override fun createTokenAt(dateTime: Instant): TrackingToken? = delegate.createTokenAt(dateTime)

    override fun appendEvents(events: MutableList<out EventMessage<*>>) {
        appendTimer.recordCallable { delegate.appendEvents(events) }
    }

    override fun readSnapshot(aggregateIdentifier: String): Optional<DomainEventMessage<*>> {
        val session = loadSession.get() ?: Session(Timer.start(meterRegistry)).also { loadSession.set(it) }
        val start = System.nanoTime()
        val snapshot = delegate.readSnapshot(aggregateIdentifier)
        // Recorded in readEvents, not here: an empty result carries no aggregate type. An empty
        // lookup is still a full JDBC round trip and belongs in the phase.
        session.snapshotNanos = System.nanoTime() - start
        session.snapshotType = snapshot.orElse(null)?.type
        return snapshot
    }

    override fun readEvents(aggregateIdentifier: String, firstSequenceNumber: Long): DomainEventStream {
        val session = loadSession.get() ?: Session(Timer.start(meterRegistry))
        loadSession.remove()

        // Read once, at the start: the path cannot change under a load, and the stream below is
        // exhausted by the caller, potentially after the mutator that set it has already restored it.
        val path = AggregateLoadPath.current
        val emitLoad = shouldEmitLoadPhase(path)

        // Eagerly buffer all event rows so "events" captures only JDBC fetch time,
        // while "replay" captures only the subsequent @EventSourcingHandler applications.
        val eventsSample = Timer.start(meterRegistry)
        val inner = delegate.readEvents(aggregateIdentifier, firstSequenceNumber)
        val buffer = mutableListOf<DomainEventMessage<*>>()
        var lastSeq: Long? = null
        while (inner.hasNext()) {
            val e = inner.next()
            buffer.add(e)
            lastSeq = e.sequenceNumber
        }

        // The snapshot names the type when there is one; otherwise the first event does. Neither
        // exists for a delta read that came back empty. On ES-4 that is the cache-repair probe
        // PessimisticCachingRepository.catchUp fires on every rollback, which finds nothing far more
        // often than it finds events; a branch with no confirmed-state cache never produces one.
        // Those are not aggregate loads and must not be attributed to one, so they land under
        // "unknown" where they can be read, or excluded, on their own. A repair that DOES find events
        // identifies itself perfectly well and is tagged with the aggregate; path="repair" is what
        // separates it from a cold miss.
        val aggregate = session.snapshotType ?: buffer.firstOrNull()?.type ?: UNKNOWN_AGGREGATE
        eventsSample.stop(timer("events", aggregate, path))
        if (session.snapshotNanos >= 0) {
            timer("snapshot", aggregate, path).record(session.snapshotNanos, TimeUnit.NANOSECONDS)
        }

        var idx = 0
        val replaySample = Timer.start(meterRegistry)
        var done = false

        return object : DomainEventStream {
            override fun hasNext(): Boolean {
                val has = idx < buffer.size
                if (!has && !done) {
                    done = true
                    replaySample.stop(timer("replay", aggregate, path))
                    // stop() returns the sampled duration, so `load` is the very same measurement as
                    // `total` rather than a second clock reading of it.
                    val totalNanos = session.total.stop(timer("total", aggregate, path))
                    if (emitLoad) timer("load", aggregate, path).record(totalNanos, TimeUnit.NANOSECONDS)
                }
                return has
            }
            override fun next(): DomainEventMessage<*> = buffer[idx++]
            override fun peek(): DomainEventMessage<*>? = if (idx < buffer.size) buffer[idx] else null
            override fun getLastSequenceNumber(): Long? = lastSeq
        }
    }

    private companion object {
        const val UNKNOWN_AGGREGATE = "unknown"
    }
}
