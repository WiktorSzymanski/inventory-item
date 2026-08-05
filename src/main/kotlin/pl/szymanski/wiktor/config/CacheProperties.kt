package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Tuning for [PessimisticCachingRepository]'s confirmed-state cache.
 *
 * [ttl] is an *idle* timeout (`expireAfterAccess`), not a write timeout: an aggregate that keeps
 * receiving commands is never evicted, so on the benchmark's small hot set the TTL never fires and
 * measured behaviour is unchanged. Its job is to bound memory for aggregates that go cold, which
 * the previous never-evicted `ConcurrentHashMap` could not do.
 *
 * Eviction is a performance event, never a correctness one — see [PessimisticCachingRepository].
 */
@ConfigurationProperties("cache")
data class CacheProperties(
    val enabled: Boolean = true,
    val ttl: Duration = Duration.ofMinutes(10),
    val maximumSize: Long = 10_000,
)
