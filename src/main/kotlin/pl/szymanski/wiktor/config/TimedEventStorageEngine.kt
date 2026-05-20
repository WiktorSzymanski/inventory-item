package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.eventhandling.DomainEventMessage
import org.axonframework.eventhandling.TrackingToken
import org.axonframework.eventsourcing.eventstore.DomainEventStream
import org.axonframework.eventsourcing.eventstore.EventStorageEngine
import java.time.Instant

// ES-2: records state_load_time{phase} for aggregate load breakdown:
//   snapshot — JDBC read of the snapshot row
//   events   — JDBC fetch of all event rows (eager, before replay starts)
//   replay   — in-memory @EventSourcingHandler application of buffered events
//   total    — end-to-end from first I/O call to aggregate fully replayed
class TimedEventStorageEngine(
    private val delegate: EventStorageEngine,
    private val meterRegistry: MeterRegistry,
) : EventStorageEngine by delegate {

    private val snapshotTimer = Timer.builder("state_load_time").tag("phase", "snapshot").register(meterRegistry)
    private val eventsTimer   = Timer.builder("state_load_time").tag("phase", "events").register(meterRegistry)
    private val replayTimer   = Timer.builder("state_load_time").tag("phase", "replay").register(meterRegistry)
    private val totalTimer    = Timer.builder("state_load_time").tag("phase", "total").register(meterRegistry)

    // Tracks the start of the full load session across readSnapshot → readEvents → stream exhaustion.
    // JDBC aggregate loading is synchronous on a single IO thread, so ThreadLocal is safe here.
    private val loadSession = ThreadLocal<Timer.Sample>()

    // Kotlin `by` delegation does not forward Java `default` interface methods — it inherits
    // the default body instead of calling delegate.method(). These three methods are `default`
    // in EventStorageEngine (all throw UnsupportedOperationException); AbstractEventStorageEngine
    // overrides them with real implementations that JdbcEventStorageEngine inherits. Without
    // these explicit overrides the TrackingEventProcessors fail to initialise their segments.
    override fun createHeadToken(): TrackingToken? = delegate.createHeadToken()
    override fun createTailToken(): TrackingToken? = delegate.createTailToken()
    override fun createTokenAt(dateTime: Instant): TrackingToken? = delegate.createTokenAt(dateTime)

    override fun readSnapshot(aggregateIdentifier: String) = run {
        if (loadSession.get() == null) loadSession.set(Timer.start(meterRegistry))
        snapshotTimer.recordCallable { delegate.readSnapshot(aggregateIdentifier) }!!
    }

    override fun readEvents(aggregateIdentifier: String, firstSequenceNumber: Long): DomainEventStream {
        if (loadSession.get() == null) loadSession.set(Timer.start(meterRegistry))
        val sessionSample = loadSession.get()
        loadSession.remove()

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
        eventsSample.stop(eventsTimer)

        var idx = 0
        val replaySample = Timer.start(meterRegistry)
        var done = false

        return object : DomainEventStream {
            override fun hasNext(): Boolean {
                val has = idx < buffer.size
                if (!has && !done) {
                    done = true
                    replaySample.stop(replayTimer)
                    sessionSample.stop(totalTimer)
                }
                return has
            }
            override fun next(): DomainEventMessage<*> = buffer[idx++]
            override fun peek(): DomainEventMessage<*>? = if (idx < buffer.size) buffer[idx] else null
            override fun getLastSequenceNumber(): Long? = lastSeq
        }
    }
}
