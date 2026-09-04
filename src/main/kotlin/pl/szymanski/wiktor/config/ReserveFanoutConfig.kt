package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.szymanski.wiktor.service.ReserveFanoutPool

/**
 * Both values are placeholders for a context that binds no yaml — a slice test, essentially.
 * `application.yaml` carries the real ones (`${RESERVE_FANOUT_THREADS:150}` /
 * `${RESERVE_FANOUT_QUEUE_CAPACITY:1000}`), and under the harness docker-compose overrides those in
 * turn. Deliberately SMALL rather than a copy of the shipped 150, following
 * [OrderWorkerProperties], whose 8 plays the same role against a shipped 50: a test context that
 * forgets to set a width should not stand up a production-sized pool.
 */
@ConfigurationProperties("app.reserve-fanout")
data class ReserveFanoutProperties(
    val threads: Int = 4,
    val queueCapacity: Int = 64,
)

/**
 * The second pool on this branch, and the only thing that separates it from TO-3.
 *
 * Read [ReserveFanoutPool] for why it exists and — more importantly — why its bean type implements
 * no executor interface. The short version: [pl.szymanski.wiktor.service.OrderWorkerPool] must stay
 * the context's ONLY `TaskExecutor` or `@Async` silently relocates to a `SimpleAsyncTaskExecutor`,
 * and nothing about a boot failure would tell you it happened.
 *
 * Kept out of [OrderWorkerConfig] on purpose. That class documents the single merged order pool and
 * the Boot conditionals it must not trip; this pool answers to neither concern — it runs no order
 * task, serves no retry, and takes no database connection.
 */
@Configuration
@EnableConfigurationProperties(ReserveFanoutProperties::class)
class ReserveFanoutConfig {

    private val log = LoggerFactory.getLogger(ReserveFanoutConfig::class.java)

    @Bean(destroyMethod = "close")
    fun reserveFanoutExecutor(
        properties: ReserveFanoutProperties,
        meterRegistry: MeterRegistry,
    ): ReserveFanoutPool {
        log.info(
            "[POOLS] reserve-fanout={} threads, queue={} (bounded, CallerRunsPolicy) — the reserve " +
                "MODIFY phase only. These threads hold NO database connection and NO row lock, so " +
                "this width does not draw on DB_MAX_CONNECTIONS the way order-worker and Tomcat do. " +
                "On saturation a line group runs on the submitting order-worker thread instead, " +
                "which is TO-3's sequential behaviour.",
            properties.threads,
            properties.queueCapacity,
        )

        val pool = ReserveFanoutPool(properties.threads, properties.queueCapacity)

        // Same hand-rolled bind, same meter names and the same reason as OrderWorkerConfig's: the
        // dashboards' "Busy threads by pool" panel queries executor_active_threads /
        // executor_pool_size_threads / executor_queued_tasks by {{name}}, and Boot instruments only
        // ThreadPoolTaskExecutor beans. Gauges only — no per-task timing on a hot path.
        //
        // Unlike orderWorkerExecutor this is a plain ThreadPoolExecutor, so executor_pool_max_threads
        // reads the configured width here rather than Integer.MAX_VALUE. Both series are meaningful
        // for this pool; only pool.core is for the other one.
        ExecutorServiceMetrics(pool.executor, "reserveFanoutExecutor", emptyList()).bindTo(meterRegistry)

        return pool
    }
}
