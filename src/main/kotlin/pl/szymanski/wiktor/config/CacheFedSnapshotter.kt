package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.axonframework.eventhandling.GenericDomainEventMessage
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.spring.eventsourcing.SpringAggregateSnapshotter
import org.springframework.beans.factory.ObjectProvider

/**
 * ES-4: builds the snapshot from cached confirmed state instead of replaying the event store.
 *
 * **The problem this removes.** Snapshotting is a separate path from command execution and it
 * ignored [PessimisticCachingRepository] entirely. `EventCountSnapshotTriggerDefinition` counts
 * every applied event on the cached trigger; every 30th it fires, and
 * `AbstractSnapshotTrigger.prepareSnapshotScheduling` registers the work on **`onPrepareCommit`**.
 * Axon's auto-configured `SpringAggregateSnapshotter` sets no executor, so it inherits
 * `DirectExecutor.INSTANCE`. The stock task then does `eventStore.readEvents(id)` — the (fat)
 * snapshot row plus the whole tail — deserializes it, replays it through the
 * `@EventSourcingHandler`s, serializes the result and stores it. All of that ran **synchronously on
 * the command thread, before commit, while the pessimistic aggregate lock was still held**, so
 * every 30th command on a hot item blocked every other command on that item.
 *
 * The cache already holds the materialized root at a known sequence, so the replay is redundant:
 * this snapshotter serializes what it has. Eliminated per snapshot: one fat snapshot-row read, the
 * tail reads, the deserialize and the replay. What remains is the serialize and one INSERT.
 *
 * **This is a property of the copy-on-write cache, not a generic Axon tuning fix**, which is why it
 * lands on ES-4 alone: the other ES branches have no confirmed-state cache to read from.
 *
 * **Why hook [createSnapshotterTask] rather than write a `Snapshotter`.** It is the one `protected`
 * seam inside `AbstractSnapshotter`'s machinery, so overriding it keeps the `snapshotsInProgress` /
 * `SCHEDULED_SNAPSHOT_SET` de-duplication, the tracing spans, the transaction wrapper and the
 * silent-failure handling, and replaces only the part that reads the store.
 *
 * **Two deliberate differences from the stock snapshotter:**
 *  - The snapshot lands at sequence **N-1**, not N. The trigger fires at `onPrepareCommit` whereas
 *    [PessimisticCachingRepository.advance] runs at `afterCommit`, so the cache still holds the
 *    previous sequence. That state is *committed by construction* — which makes this safer than
 *    making the stock replay asynchronous would have been, since an async `readEvents` would race
 *    the in-flight commit for the same result with no such guarantee. A snapshot one event behind
 *    costs one extra event on the next cold replay and nothing else.
 *  - `AggregateSnapshotter`'s "only store if the snapshot replaces more than one event" guard is
 *    dropped, because evaluating it needs the very read being eliminated. Harmless at a threshold
 *    of 30: the sequence is always ~29 events past the previous snapshot.
 */
class CacheFedSnapshotter(
    builder: SpringAggregateSnapshotter.Builder,
    private val eventStore: EventStore,
    private val stateSource: ObjectProvider<ConfirmedStateSource>,
    meterRegistry: MeterRegistry,
) : SpringAggregateSnapshotter(builder) {

    // `_count` on these timers is the per-source snapshot count; no separate counter is registered.
    private val fromCache = Timer.builder("inventory.opt.snapshot.duration").tag("source", "cache").register(meterRegistry)
    private val fromReplay = Timer.builder("inventory.opt.snapshot.duration").tag("source", "replay").register(meterRegistry)

    override fun createSnapshotterTask(aggregateType: Class<*>, aggregateIdentifier: String): Runnable {
        // Built eagerly and cheaply (it only constructs the task object, no I/O) so the cache path
        // can fall back to it without a `super` call from inside a lambda.
        val replayTask = super.createSnapshotterTask(aggregateType, aggregateIdentifier)

        return Runnable {
            // Resolved per task, not at construction: this is what breaks the bean cycle
            // snapshotter -> inventoryItemRepository -> inventorySnapshotTrigger -> snapshotter.
            val source = stateSource.ifAvailable

            // The type guard is REQUIRED, not defensive. `inventorySnapshotTrigger` is the only
            // SnapshotTriggerDefinition bean, so Axon applies it to OrderAggregate too and those
            // snapshots arrive here — they must never be served from the InventoryItem cache.
            if (source == null || source.cachedAggregateType != aggregateType) {
                fromReplay.recordCallable(replayTask::run)
                return@Runnable
            }

            val state = source.confirmedState(aggregateIdentifier)
            if (state == null) {
                // Cold aggregate, evicted entry, or cache.enabled=false: fall back to the store.
                fromReplay.recordCallable(replayTask::run)
                return@Runnable
            }
            if (state.deleted) return@Runnable // matches AggregateSnapshotter returning null

            fromCache.recordCallable {
                eventStore.storeSnapshot(
                    GenericDomainEventMessage(
                        source.aggregateTypeName, aggregateIdentifier, state.sequence, state.root,
                    )
                )
            }
        }
    }
}
