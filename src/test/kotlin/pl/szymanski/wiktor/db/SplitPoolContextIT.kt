package pl.szymanski.wiktor.db

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource

/**
 * Boots the real application context against a real Postgres.
 *
 * The unit tests prove the routing CONTRACT; this proves the routing survives contact with Spring
 * Boot. Declaring DataSource beans backs off `DataSourceAutoConfiguration`, and everything
 * downstream — `JdbcTemplate`, `DataSourceTransactionManager`, Spring Data JDBC through
 * `AbstractJdbcConfiguration` — resolves via `@ConditionalOnSingleCandidate(DataSource.class)`,
 * which only works because the routing bean is `@Primary`. Getting that wrong is a startup
 * failure, and the bench harness would report it as a health-check timeout.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        // The production defaults sum to 400, and Hikari's minimumIdle defaults to maximumPoolSize,
        // so the real sizes would open 400 backends against a container capped at 100.
        "app.db.app-pool-size=5",
        "app.db.write-pool-size=10",
        "app.order-worker.threads=2",
        "app.order-retry.threads=2",
    ],
)
class SplitPoolContextIT {

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

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    @Qualifier("appDataSource")
    private lateinit var appDataSource: HikariDataSource

    @Autowired
    @Qualifier("writeDataSource")
    private lateinit var writeDataSource: HikariDataSource

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `the primary DataSource is the router, and Boot wired its JdbcTemplate and tx manager to it`() {
        assertInstanceOf(LaneRoutingDataSource::class.java, dataSource)
        assertNotNull(transactionManager, "no transaction manager — @ConditionalOnSingleCandidate did not resolve")
        assertEquals("app-pool", appDataSource.poolName)
        assertEquals("write-pool", writeDataSource.poolName)
        // Flyway ran against its own DataSource; the migrated schema must be visible through ours.
        assertTrue(
            jdbcTemplate.queryForObject("select count(*) from information_schema.tables where table_name = 'inventory_state'", Int::class.java)!! > 0,
            "inventory_state missing — the app DataSource is not pointing at the migrated database",
        )
    }

    @Test
    fun `a transaction lands on the pool its lane selects`() {
        // Asked of Postgres rather than of Hikari's activeConnections gauge: that gauge is
        // pool-global, so any concurrent write-lane work (the republisher tick, a Modulith
        // listener) makes a count-based assertion flap. application_name is carried by the
        // connection itself, so this identifies the pool that actually served the transaction.
        val template = TransactionTemplate(transactionManager)

        val appName = DbLaneContext.on(DbLane.APP) {
            template.execute { jdbcTemplate.queryForObject("select current_setting('application_name')", String::class.java) }
        }
        assertEquals("inventory-app-pool", appName, "an APP-lane transaction did not borrow from app-pool")

        val writeName = DbLaneContext.on(DbLane.WRITE) {
            template.execute { jdbcTemplate.queryForObject("select current_setting('application_name')", String::class.java) }
        }
        assertEquals("inventory-write-pool", writeName, "a WRITE-lane transaction did not borrow from write-pool")
    }
}
