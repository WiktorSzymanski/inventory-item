package pl.szymanski.wiktor.config

/**
 * Confirmed (persisted) aggregate state at a known sequence number.
 *
 * [root] is the cache's own instance, NOT a copy. It is safe to read concurrently because the
 * cache never hands it to a command: [PessimisticCachingRepository.advance] stores a fresh deep
 * copy, and every cache hit deep-copies again before reconstructing. Treat it as immutable —
 * serialize it, never mutate it.
 */
data class ConfirmedState(val root: Any, val sequence: Long, val deleted: Boolean)

/**
 * Read side of a repository that keeps confirmed aggregate state in memory.
 *
 * Exists so [CacheFedSnapshotter] can build a snapshot from cached state without depending on
 * [PessimisticCachingRepository]'s type parameter, and — because the implementation is injected as
 * an `ObjectProvider` and resolved lazily — without closing the bean cycle
 * `snapshotter -> repository -> snapshotTriggerDefinition -> snapshotter`.
 */
interface ConfirmedStateSource {

    /**
     * The aggregate class this source caches. Callers MUST check it before using [confirmedState].
     *
     * Named `cachedAggregateType` rather than `aggregateType` because Axon's `AbstractRepository`
     * already declares `getAggregateType()`, and an implementation that extends it would otherwise
     * hit an accidental-override JVM signature clash.
     */
    val cachedAggregateType: Class<*>

    /** Axon's aggregate type *name* (`AggregateModel.type()`), as it appears in the event store. */
    val aggregateTypeName: String

    /** Confirmed state for [id], or null if this aggregate is not currently cached. */
    fun confirmedState(id: String): ConfirmedState?
}
