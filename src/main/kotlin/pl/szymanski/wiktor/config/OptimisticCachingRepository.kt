package pl.szymanski.wiktor.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.eventsourcing.EventSourcedAggregate
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.SnapshotTriggerDefinition
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * ES-3-optimistic copy-on-write repository.
 *
 * Holds only CONFIRMED (persisted) aggregate state at a known sequence number in a strong-reference,
 * never-evicted cache. Each command works on its OWN deep copy of that state, so there is no shared
 * mutable aggregate instance and hence no need for a lock — this repository is built with
 * [org.axonframework.common.lock.NullLockFactory]. Correctness rests on the event store's
 * `UNIQUE (aggregate_identifier, sequence_number)` constraint: two commands that both start from
 * sequence N try to append N+1, exactly one wins, the loser gets a `ConcurrencyException` (the JDBC
 * engine's `SQLStateResolver` translates the Postgres 23xxx violation) and is retried by the gateway.
 *
 * Cache lifecycle:
 *  - load (hit)  -> deep-copy the confirmed root, reconstruct the aggregate at seq N (NO replay).
 *  - load (miss) -> cold replay via `super` (snapshot + tail), then seed the cache.
 *  - afterCommit -> monotonically advance the cache to the just-persisted state (confirmed only).
 *  - onRollback  -> incremental catch-up: read just the missing delta (`readEvents(id, N+1)`,
 *                   usually 1 event) and advance the cache, so the gateway retry re-runs on
 *                   `cached + delta` instead of `snapshot + full-tail` replay. On any failure the
 *                   entry is evicted so the retry cold-loads (always correct).
 *
 * Set `cache.enabled=false` to bypass the cache entirely (every load cold-replays) while keeping the
 * lock-free behaviour — useful for A/B measurement against the cached path.
 */
class OptimisticCachingRepository<T : Any>(
    builder: EventSourcingRepository.Builder<T>,
    private val eventStore: EventStore,
    private val aggregateType: Class<T>,
    private val snapshotTriggerDefinition: SnapshotTriggerDefinition,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
    private val cacheEnabled: Boolean,
) : EventSourcingRepository<T>(builder) {

    private data class Confirmed<T>(val root: T, val sequence: Long, val deleted: Boolean)

    /** Repository-level strong-reference cache of confirmed state. Never evicted (bounded aggregate set). */
    private val confirmed = ConcurrentHashMap<String, Confirmed<T>>()

    private val hitCounter = meterRegistry.counter("inventory.opt.cache.hit")
    private val missCounter = meterRegistry.counter("inventory.opt.cache.miss")
    private val catchupCounter = meterRegistry.counter("inventory.opt.catchup")
    private val catchupEvents = DistributionSummary.builder("inventory.opt.catchup.events").register(meterRegistry)

    override fun doLoadWithLock(aggregateIdentifier: String, expectedVersion: Long?): EventSourcedAggregate<T> {
        val cached = if (cacheEnabled) confirmed[aggregateIdentifier] else null
        if (cached == null) {
            missCounter.increment()
            val aggregate = super.doLoadWithLock(aggregateIdentifier, expectedVersion)
            if (cacheEnabled) {
                advance(aggregateIdentifier, aggregate) // seed confirmed state at loaded sequence
                registerCacheHooks(aggregate.version() ?: -1L, aggregate)
            }
            return aggregate
        }
        hitCounter.increment()
        val trigger = snapshotTriggerDefinition.prepareTrigger(aggregateType)
        val aggregate = EventSourcedAggregate.reconstruct(
            deepCopy(cached.root), aggregateModel(), cached.sequence, cached.deleted, eventStore, trigger,
        )
        validateOnLoad(aggregate, expectedVersion)
        registerCacheHooks(cached.sequence, aggregate)
        return aggregate
    }

    override fun doCreateNewForLock(factoryMethod: Callable<T>): EventSourcedAggregate<T> {
        val aggregate = super.doCreateNewForLock(factoryMethod)
        if (cacheEnabled) registerCacheHooks(-1L, aggregate)
        return aggregate
    }

    private fun registerCacheHooks(baseSequence: Long, aggregate: EventSourcedAggregate<T>) {
        if (!CurrentUnitOfWork.isStarted()) return
        val uow = CurrentUnitOfWork.get()
        // afterCommit fires only on a successful commit => cache holds persisted state exclusively.
        uow.afterCommit { advance(aggregate.identifierAsString(), aggregate) }
        // onRollback fires on ConcurrencyException (append lost) => bring the cache up to the store head.
        uow.onRollback { catchUp(aggregate.identifierAsString(), baseSequence) }
    }

    /** Monotonically advance the confirmed cache to the aggregate's just-persisted state. */
    private fun advance(id: String, aggregate: EventSourcedAggregate<T>) {
        val newSequence = aggregate.version() ?: return
        val entry = Confirmed(deepCopy(aggregate.aggregateRoot), newSequence, aggregate.isDeleted)
        confirmed.merge(id, entry) { old, candidate -> if (candidate.sequence > old.sequence) candidate else old }
    }

    /**
     * Incremental catch-up after a lost append: read only the delta events the cached state is
     * missing and apply them, so the next (retried) load serves fresh state without a snapshot replay.
     *
     * On a single node the winning command's [advance] (afterCommit) has usually already moved the
     * cache to the store head by retry time; this delta read is the same-effect realisation of that
     * for the case where no local commit updated the cache (e.g. a different node won). It is
     * strictly best-effort and NON-destructive: on any failure the cache is left untouched (never
     * evicted) so we never fall back into the snapshot+tail replay path this cache exists to avoid.
     */
    private fun catchUp(id: String, baseSequence: Long) {
        try {
            val current = confirmed[id] ?: return
            val delta = eventStore.readEvents(id, current.sequence + 1)
            if (!delta.hasNext()) return
            val trigger = snapshotTriggerDefinition.prepareTrigger(aggregateType)
            val aggregate = EventSourcedAggregate.reconstruct(
                deepCopy(current.root), aggregateModel(), current.sequence, current.deleted, eventStore, trigger,
            )
            aggregate.initializeState(delta) // replays the delta onto the pre-seeded root (no re-publish)
            val newSequence = aggregate.version() ?: return
            if (newSequence > current.sequence) {
                val entry = Confirmed(deepCopy(aggregate.aggregateRoot), newSequence, aggregate.isDeleted)
                confirmed.merge(id, entry) { old, candidate -> if (candidate.sequence > old.sequence) candidate else old }
                catchupCounter.increment()
                catchupEvents.record((newSequence - current.sequence).toDouble())
            }
        } catch (e: Exception) {
            // Non-destructive: leave the cache as-is; the winning command's afterCommit keeps it fresh.
            log.debug("[OPT] delta catch-up skipped for {} (base={}): {}", id, baseSequence, e.message)
        }
    }

    private fun deepCopy(root: T): T = objectMapper.convertValue(root, aggregateType)

    /** Testing/observability: current confirmed sequence held for an aggregate, or null if uncached. */
    fun cachedSequence(id: String): Long? = confirmed[id]?.sequence

    companion object {
        private val log = LoggerFactory.getLogger(OptimisticCachingRepository::class.java)
    }
}
