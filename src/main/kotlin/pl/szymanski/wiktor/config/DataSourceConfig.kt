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
    // Derived from the write lane's thread count, not rounded — see application.yaml and
    // [DataSourceConfig.writeLaneDemand]. Kept in step with the yaml default because
    // WriteLaneCoverageTest asserts the invariant against THIS value.
    val writePoolSize: Int = 363,
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

    companion object {
        /** Boot's task scheduler, which runs IncompleteEventRepublisher. One thread by default. */
        const val REPUBLISHER_THREADS = 1

        /**
         * Threads that can hold a write-pool connection at the same moment. One per thread, not
         * two: every handler on this branch is `Propagation.REQUIRED`, so unlike ES there is no
         * second, non-transactional connection taken alongside the transactional one.
         *
         * A pure function so the invariant is testable without standing up a context — the sum is
         * the only thing standing between the configuration and a silent starvation window.
         */
        fun writeLaneDemand(tomcatThreads: Int, workerThreads: Int, retryThreads: Int): Int =
            tomcatThreads + workerThreads + retryThreads + REPUBLISHER_THREADS
    }

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
        workerProperties: OrderWorkerProperties,
        retryProperties: OrderRetryProperties,
        @Value("\${server.tomcat.threads.max:150}") tomcatThreads: Int,
    ): DataSource {
        val demand = writeLaneDemand(tomcatThreads, workerProperties.threads, retryProperties.threads)
        log.info(
            "[POOLS] lane-routed datasource: app-pool={} (HTTP reads) write-pool={} (accept tx, " +
                "order workers, retry pool, republisher) -> {} connections per node. TO holds ONE " +
                "connection per busy thread — write-lane demand is tomcat {} + worker {} + retry {} " +
                "+ republisher {} = {} threads.",
            properties.appPoolSize, properties.writePoolSize,
            properties.appPoolSize + properties.writePoolSize,
            tomcatThreads, workerProperties.threads, retryProperties.threads, REPUBLISHER_THREADS,
            demand,
        )
        if (demand > properties.writePoolSize) {
            log.warn(
                "[POOLS] write-pool={} cannot cover the {} threads that can demand it. Lanes now " +
                    "compete, and HikariCP's borrow() favours continuously-active threads — it " +
                    "checks a thread-local list first and scans the shared list before parking on " +
                    "the handoff queue — so the intermittently-scheduled RETRY lane starves first, " +
                    "delaying orders the system has already partly paid for. Either raise " +
                    "DB_WRITE_POOL_SIZE to {} or lower HTTP_THREADS / ORDER_WORKER_THREADS.",
                properties.writePoolSize, demand, demand,
            )
        }
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
        // Carries the lane into Postgres itself: pg_stat_activity.application_name then attributes
        // every backend, lock wait and long-running statement to the pool that opened it, which is
        // the DB-side half of the split and is visible to postgres_exporter without any app metric.
        addDataSourceProperty("ApplicationName", "inventory-$name")
    }
}
