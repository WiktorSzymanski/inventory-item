package pl.szymanski.wiktor.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
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
 * ES-4 copy-on-write repository. **Lock-free**: [AxonConfig] builds it with
 * [org.axonframework.common.lock.NullLockFactory], not the [org.axonframework.common.lock.PessimisticLockFactory]
 * that [org.axonframework.modelling.command.LockingRepository] defaults to. Nothing serialises
 * commands targeting the same aggregate, so correctness rests on the event store's
 * `UNIQUE (aggregate_identifier, sequence_number)` constraint: two commands that both start from
 * sequence N try to append N+1, exactly one wins, and the loser's `23505` becomes a
 * `ConcurrencyException` (via the storage engine's `SQLStateResolver`) that
 * [ConcurrencyRetryScheduler] retries.
 *
 * The class NAME predates that switch and is kept deliberately: `k6/lib/config.js` and
 * `k6/bench/reset.sh` refer to it by name, and everything under `k6/` must stay byte-identical
 * across all eight variant branches.
 *
 * Holds only CONFIRMED (persisted) aggregate state at a known sequence number in a Caffeine cache
 * bounded by an idle TTL and a maximum size ([CacheProperties]). Each command works on its OWN deep
 * copy of that state, which without a lock is REQUIRED for correctness — concurrent commands would
 * otherwise mutate one shared root. It also keeps the cache immune to a rolled-back command's partial
 * mutations, so a rollback needs no cache invalidation (unlike Axon's stock
 * `CachingEventSourcingRepository`, which shares one instance and must evict it on failure).
 *
 * **Eviction is a performance event, never a correctness one.** The cache is a pure accelerator over
 * the event store: an evicted entry is simply a miss, and the miss path is `super.doLoadWithLock`,
 * which reads the authoritative store. The cached [SnapshotTrigger] is lost with
 * the entry, but that too is benign — `EventSourcedAggregate.initializeState` replays through
 * `publish`, which calls `SnapshotTrigger.eventHandled` for every replayed event, so the event
 * counter is rebuilt from the tail rather than restarting from zero.
 *
 * The TTL is `expireAfterAccess`, so an aggregate under continuous load is never evicted. On a
 * benchmark hot set of a handful of items nothing ever goes idle and the cache behaves exactly as
 * the previous never-evicted `ConcurrentHashMap` did; the bounds exist to stop aggregates that go
 * cold from pinning heap forever.
 *
 * **A cache hit CAN be stale, and that is by design.** [advance] runs at AFTER_COMMIT with no lock
 * held, so between a hit at sequence N and this command's append another command may have committed
 * N+1. The stale read is not a correctness hole: it is caught at append time by the unique
 * constraint, the UnitOfWork rolls back, [catchUp] pulls in the delta, and the retry runs against
 * fresh state. The cache itself never goes backwards — every write goes through a monotonic `merge`
 * — and never holds uncommitted state.
 *
 * Metrics: `state_load_time{phase=load}` is the write path's whole state-load cost per command
 * attempt, hits and misses pooled; `{phase=copy}` is what the hit arm pays in place of a store round
 * trip, and the `{phase=snapshot|events|replay|total, path=command}` phases decompose the miss arm.
 * The repair that follows a lost race is `inventory.opt.catchup.duration` and `path=repair`; it is
 * not part of any of them.
 *
 * Cache lifecycle:
 *  - load (hit)  -> deep-copy the confirmed root, reconstruct the aggregate at seq N (NO replay),
 *                   re-attaching the cached [org.axonframework.eventsourcing.SnapshotTrigger] so the
 *                   snapshot event counter survives (see [Confirmed]).
 *  - load (miss) -> cold replay via `super` (snapshot + tail), then seed the cache.
 *  - afterCommit -> monotonically advance the cache to the just-persisted state (confirmed only).
 *  - onRollback  -> incremental catch-up: read just the missing delta (`readEvents(id, N+1)`) and
 *                   advance the cache, so the gateway retry re-runs on `cached + delta` rather than
 *                   on a snapshot + full-tail replay. Lock-free, this is the single-node repair path,
 *                   not a multi-node-only one.
 *
 * Set `cache.enabled=false` to bypass the cache entirely (every load cold-replays) while keeping the
 * lock-free behaviour — useful for A/B measurement against the cached path.
 */
class PessimisticCachingRepository<T : Any>(
    builder: EventSourcingRepository.Builder<T>,
    private val eventStore: EventStore,
    private val aggregateType: Class<T>,
    private val snapshotTriggerDefinition: SnapshotTriggerDefinition,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
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

    /**
     * How long the cache repair takes — the work sitting between the conflict and the retry, timed as
     * one operation: the delta read, the deep copy, the replay onto it, and the merge back.
     *
     * Tagged by outcome because [catchUp] fires on EVERY rollback and usually finds nothing: the
     * empty probe and a real replay are the same call site and differ by orders of magnitude, so a
     * pooled histogram would report the probe as its median and hide the replay entirely.
     *
     * This does not duplicate the `state_load_time{phase=events|replay,path=repair}` samples the
     * storage-engine wrapper already emits for the delta read. Those are the store round trip; this
     * is the whole repair, and the gap between them is what the repair costs beyond reading. They
     * carry `path=repair` precisely so they stay out of the write-path load — see
     * [AggregateLoadPath].
     */
    private fun catchupTimer(outcome: String) =
        Timer.builder("inventory.opt.catchup.duration").tag("outcome", outcome).register(meterRegistry)

    private val catchupApplied = catchupTimer("applied")
    private val catchupNoop = catchupTimer("noop")
    private val catchupFailedTimer = catchupTimer("failed")

    /**
     * What a cache hit pays in place of a store round trip: the Jackson deep copy plus the
     * reconstruct, which together replace snapshot-read + tail-fetch + replay.
     *
     * Deliberately a PHASE of `state_load_time` rather than a metric of its own. The question it
     * exists to answer is how the copy compares to the replay it removed, and as a phase it lands on
     * the same panel, the same axis and the same 1us histogram floor as `replay` and `total`. On a
     * branch with no confirmed-state cache the series simply never appears.
     *
     * It is the hit ARM of the load, not the load: [loadTimer] is what the write path actually pays.
     *
     * Lazy because the tag comes from `aggregateModel()`, which is not resolvable while the
     * repository is still under construction.
     */
    private val copyTimer: Timer by lazy {
        Timer.builder("state_load_time").tag("phase", "copy").tag("aggregate", aggregateModel().type())
            // Hard-coded rather than read from [AggregateLoadPath]: a cache hit only ever happens
            // while executing a command. catchUp's deep copy is repair, and it is timed by
            // inventory.opt.catchup.duration, not here.
            .tag("path", AggregateLoadPath.COMMAND)
            .register(meterRegistry)
    }

    /**
     * What the write path pays to materialise this aggregate, end to end, before the command runs and
     * the append happens — the whole of [doLoadWithLock], both arms.
     *
     * This is the number that the phase breakdown could not give. `copy` describes the hit arm and
     * `total` the miss arm; they are disjoint populations on disjoint sets of commands, so no
     * percentile over either one is the cost of loading state on this branch, and the ratio between
     * them shifts with the hit rate. Pooling both here gives one series whose p95 is a real answer,
     * with the two phases left to explain it.
     *
     * It also covers what neither phase does: [validateOnLoad], the hook registration, and — on a
     * miss — [advance] deep-copying the just-loaded root to seed the cache, which is write-path work
     * the store round trip does not include.
     *
     * Recorded in a `finally`, so a load that throws is counted rather than silently dropped. That
     * pools a failed load with successful ones, which is the lesser error: loads fail here only when
     * the store itself is unreachable (conflicts surface at append, not at load), whereas
     * success-only sampling would quietly hide exactly the pathological case.
     */
    private val loadTimer: Timer by lazy {
        Timer.builder("state_load_time").tag("phase", "load").tag("aggregate", aggregateModel().type())
            .tag("path", AggregateLoadPath.COMMAND)
            .register(meterRegistry)
    }
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

    /**
     * Times the whole load as [loadTimer] and declares the envelope, so [TimedEventStorageEngine]
     * knows not to emit its own `load` phase for the store read on the miss arm.
     */
    override fun doLoadWithLock(aggregateIdentifier: String, expectedVersion: Long?): EventSourcedAggregate<T> {
        val sample = Timer.start(meterRegistry)
        try {
            return AggregateLoadPath.withEnvelope { loadFromCacheOrStore(aggregateIdentifier, expectedVersion) }
        } finally {
            sample.stop(loadTimer)
        }
    }

    // Not named `load`: AbstractRepository already has one, and hiding it silently would be worse
    // than the compile error it actually causes.
    private fun loadFromCacheOrStore(aggregateIdentifier: String, expectedVersion: Long?): EventSourcedAggregate<T> {
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
        // Without a lock, concurrent commands on one aggregate share this trigger instance and race on
        // its counter. That race is benign — it can only make the snapshot cadence slightly irregular,
        // never produce a wrong snapshot — whereas prepareTrigger would hand out a ZEROED counter per
        // command and stop the threshold from ever being reached, silently disabling snapshotting.
        val trigger = snapshotTriggerDefinition.reconfigure(aggregateType, cached.trigger)
        // Everything the hit path does instead of touching the store — see [copyTimer]. The
        // reconfigure above is excluded deliberately: it is bookkeeping on the snapshot counter, not
        // part of materialising state, and folding it in would flatter the comparison.
        val copySample = Timer.start(meterRegistry)
        val aggregate = EventSourcedAggregate.reconstruct(
            deepCopy(cached.root), aggregateModel(), cached.sequence, cached.deleted, eventStore, trigger,
        )
        copySample.stop(copyTimer)
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
        // onRollback fires on a failed command — lock-free, typically the lost race for this sequence.
        // Thanks to the deep copy there is nothing to undo, so this only pulls in the events the
        // winner (local or on another node) appended.
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
     * missing and apply them, so the retry serves fresh state without a snapshot replay.
     *
     * Lock-free, this is the ordinary single-node path: the command that lost the race for sequence
     * N+1 lands here, and by retry time the winner's [advance] (afterCommit) has usually already moved
     * the cache to the store head — this delta read is the same-effect realisation of that for the
     * case where no local commit did (e.g. a different node won). Strictly best-effort and
     * NON-destructive: on any failure the cache is left untouched
     * (never invalidated) so we never fall back into the snapshot+tail replay path this cache exists
     * to avoid. An absent entry — expired, or never loaded — needs no repair: the next load misses and
     * cold-replays from the store, which is authoritative.
     */
    private fun catchUp(id: String, baseSequence: Long) =
        // Everything below reads the store, so it must not be tagged as a write-path load: it runs
        // after this command's append already failed. See [AggregateLoadPath].
        AggregateLoadPath.on(AggregateLoadPath.REPAIR) { repair(id, baseSequence) }

    private fun repair(id: String, baseSequence: Long) {
        val sample = Timer.start(meterRegistry)
        // Starts as the common case and is promoted only where the work actually happened, so the
        // early returns below (no entry, empty delta, no version) fall through the finally as "noop"
        // without each needing to say so.
        var outcome = catchupNoop
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
                outcome = catchupApplied
            }
        } catch (e: Exception) {
            outcome = catchupFailedTimer
            // Non-destructive: leave the cache as-is; the committing command's afterCommit keeps it fresh.
            // WARN, not DEBUG: the root logger is at INFO, so a DEBUG line here was never emitted and
            // this failure was completely silent. See the counter's declaration for why it matters.
            catchupFailed.increment()
            log.warn("[PES] delta catch-up FAILED for {} (base={}) — cache may now be stale, " +
                "commands on this aggregate will conflict until a later rollback repairs it", id, baseSequence, e)
        } finally {
            sample.stop(outcome)
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
