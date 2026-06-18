package pl.szymanski.wiktor.config

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.szymanski.wiktor.domain.InventoryItem
import java.util.concurrent.TimeUnit

@Configuration
class InventoryStateCacheConfig {

    /**
     * Caffeine cache for the reservation write-path state load.
     *
     * 5-minute idle TTL (expire-after-access): the per-entry timer resets on every read or
     * write, so any item not touched within the window is evicted to reclaim space. Live
     * entries stay consistent through the write path's version-guarded post-commit merge (and
     * eviction on optimistic conflict); an evicted key simply falls back to a DB fetch on next
     * use. recordStats() feeds Prometheus so the (expected marginal) TO benefit can be measured
     * against the no-cache baseline.
     */
    @Bean
    fun inventoryStateCache(meterRegistry: MeterRegistry): Cache<String, InventoryItem> {
        val cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .recordStats()
            .build<String, InventoryItem>()
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "inventory_state_cache")
        return cache
    }
}
