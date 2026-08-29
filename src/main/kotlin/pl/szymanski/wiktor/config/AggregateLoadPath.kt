package pl.szymanski.wiktor.config

/**
 * Which call path an aggregate load belongs to, carried on the loading thread so
 * [TimedEventStorageEngine] can tag `state_load_time` with it.
 *
 * The wrapper sits on the storage engine and therefore sees every store round trip, with nothing in
 * the call itself saying who asked. On ES-4 three unrelated callers reach it and `{aggregate}` does
 * not separate them:
 *
 *  - **[COMMAND]** — [PessimisticCachingRepository.doLoadWithLock] missing the cache and falling
 *    through to `super`. This, and only this, is the write path: state being materialised for a
 *    command that is about to append.
 *  - **[REPAIR]** — [PessimisticCachingRepository.catchUp] reading the delta after a rollback. It
 *    runs on the losing command's thread but AFTER its append failed, so it is the cost of the
 *    conflict, not the cost of the write. An empty probe used to be the only one distinguishable
 *    (it identifies no aggregate and lands under `unknown`); a repair that actually finds events was
 *    tagged `InventoryItem` and was indistinguishable from a cold miss.
 *  - **[SNAPSHOT]** — [CacheFedSnapshotter]'s fallback to the stock replay task, which reads the
 *    snapshot row plus the whole tail. It runs inline on the command thread at `onPrepareCommit`, so
 *    it IS before the insert, but it is not this command's state load and pooling it with one
 *    inflates the write path by a full replay every `snapshot.event-count` commands.
 *
 * The default is [COMMAND] rather than "unknown": the command path is the one that does not get to
 * announce itself (it is the plain `load -> execute -> append` sequence, with no seam to wrap), so
 * the two side paths mark themselves and everything else is the write path by construction.
 *
 * A `ThreadLocal` is the right carrier because aggregate loading is synchronous on one thread — the
 * same assumption [TimedEventStorageEngine]'s load session already rests on. Every mutator restores
 * the previous value in a `finally`, so a pooled command thread never leaks a path into its next
 * task.
 */
object AggregateLoadPath {

    const val COMMAND = "command"
    const val REPAIR = "repair"
    const val SNAPSHOT = "snapshot"

    private val path: ThreadLocal<String> = ThreadLocal.withInitial { COMMAND }

    /**
     * Whether an outer envelope is already timing this load as a whole.
     *
     * [PessimisticCachingRepository] records `state_load_time{phase=load}` around its entire
     * `doLoadWithLock`, covering both the cache-hit arm (which never touches the store) and the miss
     * arm (which does). An uncached aggregate has no such envelope, and for it the store round trip
     * IS the whole load — so the storage engine emits the `load` phase itself in exactly that case.
     * This flag is what tells the two apart, and keeps the miss arm from being counted twice under
     * one phase with two different durations.
     */
    private val enveloped: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    val current: String get() = path.get()

    val isEnveloped: Boolean get() = enveloped.get()

    /** Runs [block] with the load path set to [newPath], restoring the previous value afterwards. */
    fun <T> on(newPath: String, block: () -> T): T {
        val previous = path.get()
        path.set(newPath)
        return try {
            block()
        } finally {
            if (previous == COMMAND) path.remove() else path.set(previous)
        }
    }

    /** Runs [block] with an outer load envelope declared active — see [enveloped]. */
    fun <T> withEnvelope(block: () -> T): T {
        val previous = enveloped.get()
        enveloped.set(true)
        return try {
            block()
        } finally {
            if (previous) enveloped.set(true) else enveloped.remove()
        }
    }
}
