package pl.szymanski.wiktor.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.eventsourcing.EventSourcedAggregate
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.NoSnapshotTriggerDefinition
import org.axonframework.eventsourcing.SnapshotTrigger
import org.axonframework.eventsourcing.SnapshotTriggerDefinition
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable

/**
 * ES-4 copy-on-write repository.
 *
 * Identical caching machinery to the ES-3-optimistic variant — the ONLY difference is the lock: this
 * repository is built with Axon's default [org.axonframework.common.lock.PessimisticLockFactory]
 * instead of `NullLockFactory`, so commands targeting the same aggregate are serialised inside the JVM
 * and never race for the same sequence number. The two branches are therefore a clean A/B of the
 * concurrency strategy alone.
 *
 * Holds only CONFIRMED (persisted) aggregate state at a known sequence number in a Caffeine cache
 * bounded by an idle TTL and a maximum size ([CacheProperties]). Each command still works on its OWN
 * deep copy of that state: with the lock in place this is no longer required for correctness, but it
 * keeps the cache immune to a rolled-back command's partial mutations, so a rollback needs no cache
 * invalidation (unlike Axon's stock `CachingEventSourcingRepository`, which shares one instance and
 * must evict it on failure).
 *
 * **Eviction is a performance event, never a correctness one.** The cache is a pure accelerator over
 * the event store: an evicted entry is simply a miss, and the miss path is `super.doLoadWithLock`,
 * which reads the authoritative store under the same lock. The cached [SnapshotTrigger] is lost with
 * the entry, but that too is benign — `EventSourcedAggregate.initializeState` replays through
 * `publish`, which calls `SnapshotTrigger.eventHandled` for every replayed event, so the event
 * counter is rebuilt from the tail rather than restarting from zero.
 *
 * The TTL is `expireAfterAccess`, so an aggregate under continuous load is never evicted. On a
 * benchmark hot set of a handful of items nothing ever goes idle and the cache behaves exactly as
 * the previous never-evicted `ConcurrentHashMap` did; the bounds exist to stop aggregates that go
 * cold from pinning heap forever.
 *
 * Lock/cache interplay: [org.axonframework.modelling.command.LockingRepository] obtains the lock
 * before [doLoadWithLock] and releases it in the UnitOfWork CLEANUP phase — i.e. after AFTER_COMMIT.
 * [advance] therefore always runs while the lock is still held, so the next command for the same
 * aggregate is guaranteed to observe the cache at the store head: a hit is never stale.
 *
 * Cache lifecycle:
 *  - load (hit)  -> deep-copy the confirmed root, reconstruct the aggregate at seq N (NO replay),
 *                   re-attaching the cached [org.axonframework.eventsourcing.SnapshotTrigger] so the
 *                   snapshot event counter survives (see [Confirmed]).
 *  - load (miss) -> cold replay via `super` (snapshot + tail), then seed the cache.
 *  - afterCommit -> monotonically advance the cache to the just-persisted state (confirmed only).
 *  - onRollback  -> incremental catch-up: read just the missing delta (`readEvents(id, N+1)`) and
 *                   advance the cache. With the pessimistic lock this finds nothing on a single node;
 *                   it stays for the multi-node case (the lock is JVM-local, not distributed).
 *
 * Set `cache.enabled=false` to bypass the cache entirely (every load cold-replays) while keeping the
 * pessimistic locking — useful for A/B measurement against the cached path.
 */
class PessimisticCachingRepository<T : Any>(
    builder: EventSourcingRepository.Builder<T>,
    private val eventStore: EventStore,
    private val aggregateType: Class<T>,
    private val snapshotTriggerDefinition: SnapshotTriggerDefinition,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
    cacheProperties: CacheProperties,
) : EventSourcingRepository<T>(builder), ConfirmedStateSource {

    private val cacheEnabled = cacheProperties.enabled

    /**
     * [trigger] is cached alongside the state for the same reason Axon's [AggregateCacheEntry] keeps
     * one: [SnapshotTriggerDefinition.prepareTrigger] hands out a trigger with a ZEROED event counter,
     * so preparing a fresh one per cache hit would stop `EventCountSnapshotTriggerDefinition` from ever
     * reaching its threshold and silently disable snapshotting. The live trigger is carried forward and
     * re-attached via [SnapshotTriggerDefinition.reconfigure] instead.
     */
    private data class Confirmed<T>(
        val root: T,
        val sequence: Long,
        val deleted: Boolean,
        val trigger: SnapshotTrigger,
    )

    // Metric names are deliberately identical to the ES-3-optimistic branch so the same dashboard and
    // report queries compare both variants without renaming anything.
    private val hitCounter = meterRegistry.counter("inventory.opt.cache.hit")
    private val missCounter = meterRegistry.counter("inventory.opt.cache.miss")
    private val catchupCounter = meterRegistry.counter("inventory.opt.catchup")
    // catchUp repairs a cache entry that a foreign node moved past. It is no longer the ONLY repair
    // path — an idle entry now expires and the next load cold-replays from the store — but it is the
    // only one that fires while an aggregate is hot, which is exactly when it matters. A failure is
    // therefore not cosmetic: every subsequent command on that aggregate targets an already-taken
    // sequence number and exhausts its retries into a REJECT, until either a later rollback repairs
    // the entry or it goes idle long enough to expire. Those rejections land in
    // saga_completed{outcome="command_failed"} and read as contention, which is exactly the number
    // the multi-replica story rests on. Counted so the two are separable.
    private val catchupFailed = meterRegistry.counter("inventory.opt.catchup.failed")
    private val catchupEvents = DistributionSummary.builder("inventory.opt.catchup.events").register(meterRegistry)
    // Deliberately NOT CaffeineCacheMetrics: its `cache.*` meter names have collided with the
    // hand-rolled hit/miss counters above in the past, and queries.promql is byte-shared with ES-2.
    private val evictedCounter = meterRegistry.counter("inventory.opt.cache.evicted")

    /**
     * Repository-level cache of confirmed state, bounded by an idle TTL and a maximum size.
     *
     * `expireAfterAccess` (not `expireAfterWrite`): a hot aggregate is never evicted, so the bounds
     * only reclaim aggregates that have gone cold. Eviction costs one cold replay — see the class
     * docs for why it can never cost correctness.
     */
    private val confirmed: Cache<String, Confirmed<T>> = Caffeine.newBuilder()
        .expireAfterAccess(cacheProperties.ttl)
        .maximumSize(cacheProperties.maximumSize)
        .evictionListener<String, Confirmed<T>> { _, _, _ -> evictedCounter.increment() }
        .build()

    init {
        Gauge.builder("inventory.opt.cache.size") { confirmed.estimatedSize().toDouble() }
            .register(meterRegistry)
    }

    override fun doLoadWithLock(aggregateIdentifier: String, expectedVersion: Long?): EventSourcedAggregate<T> {
        val cached = if (cacheEnabled) confirmed.getIfPresent(aggregateIdentifier) else null
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
        // reconfigure (NOT prepareTrigger): keeps the snapshot event counter running across commands.
        val trigger = snapshotTriggerDefinition.reconfigure(aggregateType, cached.trigger)
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
        // It runs before the lock is released (CLEANUP), so the next command sees the advanced state.
        uow.afterCommit { advance(aggregate.identifierAsString(), aggregate) }
        // onRollback fires on a failed command; thanks to the deep copy there is nothing to undo, so
        // this only pulls in events another node may have appended.
        uow.onRollback { catchUp(aggregate.identifierAsString(), baseSequence) }
    }

    /** Monotonically advance the confirmed cache to the aggregate's just-persisted state. */
    private fun advance(id: String, aggregate: EventSourcedAggregate<T>) {
        val newSequence = aggregate.version() ?: return
        // Carry the live trigger (already counting this command's events) into the new entry.
        val entry = Confirmed(deepCopy(aggregate.aggregateRoot), newSequence, aggregate.isDeleted, aggregate.snapshotTrigger)
        // asMap() gives ConcurrentMap semantics over the Caffeine cache, so the monotonic guard is
        // as atomic here as it was on the ConcurrentHashMap this replaced.
        confirmed.asMap().merge(id, entry) { old, candidate -> if (candidate.sequence > old.sequence) candidate else old }
    }

    /**
     * Incremental catch-up after a rolled-back command: read only the delta events the cached state is
     * missing and apply them, so the next load serves fresh state without a snapshot replay.
     *
     * On a single node with the pessimistic lock this finds nothing — no other command could have
     * appended while this one held the lock. It matters only when a second node writes to the same
     * aggregate. Strictly best-effort and NON-destructive: on any failure the cache is left untouched
     * (never invalidated) so we never fall back into the snapshot+tail replay path this cache exists
     * to avoid. An absent entry — expired, or never loaded — needs no repair: the next load misses and
     * cold-replays from the store, which is authoritative.
     */
    private fun catchUp(id: String, baseSequence: Long) {
        try {
            val current = confirmed.getIfPresent(id) ?: return
            val delta = eventStore.readEvents(id, current.sequence + 1)
            if (!delta.hasNext()) return
            // Throwaway trigger: this replay is a cache repair, not command execution — it must not
            // schedule a snapshot from inside a rollback, nor advance the live counter.
            val aggregate = EventSourcedAggregate.reconstruct(
                deepCopy(current.root), aggregateModel(), current.sequence, current.deleted, eventStore,
                NoSnapshotTriggerDefinition.TRIGGER,
            )
            aggregate.initializeState(delta) // replays the delta onto the pre-seeded root (no re-publish)
            val newSequence = aggregate.version() ?: return
            if (newSequence > current.sequence) {
                // Keep the live trigger; only the state moved forward.
                val entry = Confirmed(deepCopy(aggregate.aggregateRoot), newSequence, aggregate.isDeleted, current.trigger)
                confirmed.asMap().merge(id, entry) { old, candidate -> if (candidate.sequence > old.sequence) candidate else old }
                catchupCounter.increment()
                catchupEvents.record((newSequence - current.sequence).toDouble())
            }
        } catch (e: Exception) {
            // Non-destructive: leave the cache as-is; the committing command's afterCommit keeps it fresh.
            // WARN, not DEBUG: the root logger is at INFO, so a DEBUG line here was never emitted and
            // this failure was completely silent. See the counter's declaration for why it matters.
            catchupFailed.increment()
            log.warn("[PES] delta catch-up FAILED for {} (base={}) — cache may now be stale, " +
                "commands on this aggregate will conflict until a later rollback repairs it", id, baseSequence, e)
        }
    }

    private fun deepCopy(root: T): T = objectMapper.convertValue(root, aggregateType)

    /** Testing/observability: current confirmed sequence held for an aggregate, or null if uncached. */
    fun cachedSequence(id: String): Long? = confirmed.getIfPresent(id)?.sequence

    /** Testing: drop an entry so the next load takes the cold-replay path. */
    fun evict(id: String) = confirmed.invalidate(id)

    // --- ConfirmedStateSource: read side used by CacheFedSnapshotter ------------------------------

    override val cachedAggregateType: Class<*> get() = aggregateType

    override val aggregateTypeName: String get() = aggregateModel().type()

    /**
     * The cached root is returned by reference, deliberately un-copied. Deep-copying it here would
     * reintroduce exactly the fat Jackson round trip the cache-fed snapshot path exists to remove,
     * and it is unnecessary: the cache never hands this instance to a command (every hit copies it
     * first, and [advance] stores a fresh copy), so it is immutable in practice.
     */
    override fun confirmedState(id: String): ConfirmedState? =
        confirmed.getIfPresent(id)?.let { ConfirmedState(it.root, it.sequence, it.deleted) }

    companion object {
        private val log = LoggerFactory.getLogger(PessimisticCachingRepository::class.java)
    }
}
