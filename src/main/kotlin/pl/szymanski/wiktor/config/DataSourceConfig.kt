package pl.szymanski.wiktor.config

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import pl.szymanski.wiktor.db.LaneRoutingDataSource
import javax.sql.DataSource

/**
 * Pool sizes, defaulted to mirror the ES family's split at a 400-connection envelope.
 *
 * ES runs `spring.datasource.hikari.maximum-pool-size` for HTTP reads plus a separate
 * `axon.jdbc.pool.size` for everything Axon touches. The defaults here are that shape: a small
 * read pool and a large write pool summing to 400.
 *
 * **The sizes are an envelope, not a budget to spend.** TO holds ONE connection per busy thread —
 * every handler on this branch is `Propagation.REQUIRED`, so there is no second, non-transactional
 * connection like the one ES's `DataSourceConnectionProvider` takes. The same 400 therefore covers
 * roughly twice the concurrent threads it covers on ES, and the interesting number is not the
 * ceiling but `hikaricp_connections_active{pool="write-pool"}` under load: how much of it each
 * family actually needs for the same offered concurrency is a RESULT, not a setting.
 *
 * Note Hikari's `minimumIdle` defaults to `maximumPoolSize` and is deliberately not overridden
 * here — matching `AxonConfig.axonDataSource`, which does not override it either. Both families
 * therefore open their full pool at startup and present Postgres with the same backend-memory
 * footprint, which is a better baseline than TO's previous 50 against ES's 300.
 */
@ConfigurationProperties("app.db")
data class DbPoolProperties(
    val appPoolSize: Int = 40,
    val writePoolSize: Int = 360,
)

/**
 * Replaces Boot's auto-configured single `DataSource` with two Hikari pools behind a
 * [LaneRoutingDataSource]. Declaring a `DataSource` bean backs off `DataSourceAutoConfiguration`;
 * `spring.datasource.hikari.*` is consequently NOT read any more, and `app.db.*` takes its place.
 *
 * The routing bean is `@Primary` so `@ConditionalOnSingleCandidate(DataSource.class)` still
 * resolves — that is what keeps Boot's `JdbcTemplate` and `DataSourceTransactionManager`
 * auto-configuration, and therefore Spring Data JDBC, working unchanged on top of the split.
 *
 * Flyway is untouched: `spring.flyway.url` is set in application.yaml, so Boot builds Flyway its
 * own short-lived DataSource rather than borrowing this one.
 */
@Configuration
@EnableConfigurationProperties(DbPoolProperties::class)
class DataSourceConfig {

    private val log = LoggerFactory.getLogger(DataSourceConfig::class.java)

    @Bean(name = ["appDataSource"], destroyMethod = "close")
    fun appDataSource(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
        properties: DbPoolProperties,
    ): HikariDataSource = pool(url, username, password, properties.appPoolSize, "app-pool")

    @Bean(name = ["writeDataSource"], destroyMethod = "close")
    fun writeDataSource(
        @Value("\${spring.datasource.url}") url: String,
        @Value("\${spring.datasource.username}") username: String,
        @Value("\${spring.datasource.password}") password: String,
        properties: DbPoolProperties,
    ): HikariDataSource = pool(url, username, password, properties.writePoolSize, "write-pool")

    @Bean
    @Primary
    fun dataSource(
        @Qualifier("appDataSource") appDataSource: DataSource,
        @Qualifier("writeDataSource") writeDataSource: DataSource,
        properties: DbPoolProperties,
    ): DataSource {
        log.info(
            "[POOLS] lane-routed datasource: app-pool={} (HTTP reads) write-pool={} (accept tx, " +
                "order workers, retry pool, republisher) -> {} connections per node. TO holds ONE " +
                "connection per busy thread, so this covers ~{} concurrent threads.",
            properties.appPoolSize, properties.writePoolSize,
            properties.appPoolSize + properties.writePoolSize,
            properties.appPoolSize + properties.writePoolSize,
        )
        return LaneRoutingDataSource(appDataSource, writeDataSource)
    }

    /**
     * `connectionTimeout` is left at Hikari's 30s default rather than copied from
     * `AxonConfig.axonDataSource`'s 5s. On ES a starved command fails terminally after 5s because
     * `ConcurrencyRetryScheduler` will not retry a `SQLTransientConnectionException`; on TO a
     * borrow that waits is just latency, and shortening it would convert pool pressure into
     * rejected orders — a different experiment from the one this branch runs.
     */
    private fun pool(
        url: String,
        username: String,
        password: String,
        size: Int,
        name: String,
    ): HikariDataSource = HikariDataSource().apply {
        jdbcUrl = url
        this.username = username
        this.password = password
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = size
        poolName = name
    }
}
