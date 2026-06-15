package pl.szymanski.wiktor.config

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.szymanski.wiktor.domain.InventoryItem

@Configuration
class InventoryStateCacheConfig {

    /**
     * Caffeine cache for the reservation write-path state load.
     *
     * No TTL: entries only change through the write path, which keeps them consistent via a
     * version-guarded post-commit merge (and evicts on optimistic conflict). Read-only items
     * never go stale. recordStats() feeds Prometheus so the (expected marginal) TO benefit can
     * be measured against the no-cache baseline.
     */
    @Bean
    fun inventoryStateCache(meterRegistry: MeterRegistry): Cache<String, InventoryItem> {
        val cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .recordStats()
            .build<String, InventoryItem>()
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "inventory_state_cache")
        return cache
    }
}
