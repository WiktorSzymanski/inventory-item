package pl.szymanski.wiktor.integration

import org.assertj.core.api.Assertions.assertThat
import org.axonframework.eventsourcing.CachingEventSourcingRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.szymanski.wiktor.domain.InventoryItem
import org.axonframework.config.Configuration as AxonConfiguration

/**
 * ES-3 is the *cached* optimistic variant — the cache is the whole point of the branch, and it is
 * wired by configuration rather than by anything a functional test would notice. If it silently
 * stopped being applied, every test would still pass and ES-3 would quietly become an ES-2 clone
 * with `cache.enabled` as a dead knob, invalidating the comparison the thesis draws from it.
 *
 * This pins the wiring itself.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "axon.saga.total-segments=1",
        "axon.saga.replicas=1",
        "axon.jdbc.pool.size=10",
        "spring.datasource.hikari.maximum-pool-size=10",
        "cache.enabled=true",
    ],
)
class InventoryItemCacheWiringIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("inventory")
            .withUsername("inventory")
            .withPassword("inventory")

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
        }
    }

    @Autowired private lateinit var axonConfiguration: AxonConfiguration

    @Test
    fun `InventoryItem is served by a caching repository`() {
        val repository = axonConfiguration.repository(InventoryItem::class.java)
        assertThat(repository)
            .`as`(
                "ES-3's InventoryItem repository must be caching — got %s. If this is a plain " +
                    "EventSourcingRepository the cache configuration is not being applied and the " +
                    "branch is no longer the cached variant.",
                repository.javaClass.name,
            )
            .isInstanceOf(CachingEventSourcingRepository::class.java)
    }
}
