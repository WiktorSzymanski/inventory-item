package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import pl.szymanski.wiktor.service.DelayedOrderRetryScheduler
import pl.szymanski.wiktor.service.OrderRetryScheduler
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

@ConfigurationProperties("app.order-worker")
data class OrderWorkerProperties(
    val threads: Int = 8,
    val queueCapacity: Int = Int.MAX_VALUE,
)

/**
 * The retry pool: a lane of its own for attempts that have already conflicted.
 *
 * With [executeOnRetryPool] (the default) this pool RUNS the retried attempt — the aggregate reads,
 * the reserveDelayMs sleep, the UPDATEs, the commit — exactly as the ES branches do, where Axon's
 * `RetryingCallback` re-dispatches inline onto its own retry pool. That parity is the point: TO and
 * ES then differ in their persistence model rather than in their retry topology.
 *
 * [threads] defaults to 50 against 150 workers — a third of the first-attempt width, where
 * ES-4-NullLock-A runs 23 of 91 (25.3%). Set it to the worker count for "separate lane, equally
 * wide" (isolation without narrowing), or lower to reproduce a narrow waist. It is the knob this
 * branch is read on, so watch `order_retry_pool_active` before attributing anything to it —
 * `_queued` also counts attempts merely still serving out their backoff.
 *
 * Set [executeOnRetryPool] false for the earlier behaviour, where this pool only counted
 * time and handed the attempt back to `orderWorkerExecutor`. That configuration is the one that
 * differs from the old blocking path in a single dimension (where the waiting happens), so it is
 * the honest setting
 * for a pure blocking-vs-non-blocking A/B.
 */
@ConfigurationProperties("app.order-retry")
data class OrderRetryProperties(
    // 33% of the order-worker width (50 of 150) — kept in step with application.yaml, which is
    // where the reasoning lives. WriteLaneCoverageTest asserts the ratio against THIS value.
    val threads: Int = 50,
    val executeOnRetryPool: Boolean = true,
)

@Configuration
@EnableConfigurationProperties(OrderWorkerProperties::class, OrderRetryProperties::class)
class OrderWorkerConfig {

    /**
     * Unbounded queue (Int.MAX_VALUE -> unbounded LinkedBlockingQueue): the worker pool never
     * rejects, mirroring the ES branches' unbounded async executors. Excess load is absorbed in
     * memory (degrade) rather than shed with HTTP 503, so admission is bounded only by the Tomcat
     * HTTP thread pool that accepts orders.
     */
    @Bean
    fun orderWorkerExecutor(properties: OrderWorkerProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.threads
            maxPoolSize = properties.threads
            queueCapacity = properties.queueCapacity
            setThreadNamePrefix("order-worker-")
            // NOTE this pool runs more than the order tasks. Because an Executor bean exists in
            // this context, Boot's `applicationTaskExecutor` is never created (its
            // `TaskExecutorConfigurations.OnExecutorCondition` is `@ConditionalOnMissingBean(
            // Executor.class)`), and Spring's @Async resolution then finds exactly one TaskExecutor
            // bean — this one. So `InventoryService.onOrderCreated`, the @ApplicationModuleListener
            // that marks the publication complete in its own REQUIRES_NEW transaction, ALSO runs
            // here: event DELIVERY competes with reservation EXECUTION for these threads and their
            // queue. Long-standing behaviour on every TO branch, called out because it is invisible.
        }

    /**
     * Deliberately NOT `spring.task.scheduling` (pool size 2): that scheduler runs the outbox
     * drain, and application.yaml already warns that a long tick there starves the
     * outbox.backlog gauge. Retry waits would be exactly such a tick.
     *
     * Daemon threads so a stuck retry can never hold the JVM open at shutdown; the returned
     * scheduler is AutoCloseable, which Spring calls on context close for a graceful stop.
     */
    @Bean
    fun orderRetryScheduler(properties: OrderRetryProperties, meterRegistry: MeterRegistry): OrderRetryScheduler {
        val threadNumber = AtomicInteger(1)
        val executor = ScheduledThreadPoolExecutor(properties.threads) { runnable ->
            Thread(runnable, "order-retry-${threadNumber.getAndIncrement()}").apply { isDaemon = true }
        }
        // Once this pool executes retries rather than merely timing them, its width is a throughput
        // limit and has to be observable. A raw ScheduledThreadPoolExecutor publishes nothing on its
        // own — unlike orderWorkerExecutor, which Spring Boot auto-binds as executor_* because it is
        // a ThreadPoolTaskExecutor. `order_retry_pool_queued` sitting at 0 while throughput is flat
        // means this pool was never the constraint.
        Gauge.builder("order.retry.pool.active", executor) { it.activeCount.toDouble() }
            .description("Retry threads currently executing an attempt")
            .register(meterRegistry)
        Gauge.builder("order.retry.pool.queued", executor) { it.queue.size.toDouble() }
            .description("Retries waiting for a retry thread (includes those still serving out their backoff)")
            .register(meterRegistry)
        return DelayedOrderRetryScheduler(executor)
    }
}
