package pl.szymanski.wiktor.config

import org.axonframework.common.Registration
import org.axonframework.common.caching.Cache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Axon Cache backed by a ConcurrentHashMap with strong references.
 * Unlike WeakReferenceCache, entries are never evicted by GC, which prevents
 * the EventCountSnapshotTriggerDefinition counter from resetting and ensures
 * hot aggregates are never replayed from scratch during sustained load.
 * Only used for InventoryItem (5 items), so unbounded size is acceptable.
 */
class StrongCache : Cache {

    private val map = ConcurrentHashMap<Any, Any>()
    private val listeners = CopyOnWriteArrayList<Cache.EntryListener>()

    @Suppress("UNCHECKED_CAST")
    override fun <K : Any, V : Any> get(key: K): V? = map[key] as V?

    override fun put(key: Any, value: Any) {
        val prev = map.put(key, value)
        if (prev != null) {
            listeners.forEach { it.onEntryUpdated(key, value) }
        } else {
            listeners.forEach { it.onEntryCreated(key, value) }
        }
    }

    override fun putIfAbsent(key: Any, value: Any): Boolean {
        val added = map.putIfAbsent(key, value) == null
        if (added) listeners.forEach { it.onEntryCreated(key, value) }
        return added
    }

    override fun containsKey(key: Any): Boolean = map.containsKey(key)

    override fun remove(key: Any): Boolean {
        val removed = map.remove(key) != null
        if (removed) listeners.forEach { it.onEntryRemoved(key) }
        return removed
    }

    override fun registerCacheEntryListener(cacheEntryListener: Cache.EntryListener): Registration {
        listeners.add(cacheEntryListener)
        return Registration { listeners.remove(cacheEntryListener); true }
    }
}
