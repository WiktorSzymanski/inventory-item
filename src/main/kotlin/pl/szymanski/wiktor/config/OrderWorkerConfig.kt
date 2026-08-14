package pl.szymanski.wiktor.config

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
 * TO-3-mod only. Threads that do nothing but wait out a retry backoff and re-submit the attempt to
 * orderWorkerExecutor, so 2 is plenty regardless of load: the work itself never runs here.
 */
@ConfigurationProperties("app.order-retry")
data class OrderRetryProperties(
    val threads: Int = 2,
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
    fun orderRetryScheduler(properties: OrderRetryProperties): OrderRetryScheduler {
        val threadNumber = AtomicInteger(1)
        val executor = ScheduledThreadPoolExecutor(properties.threads) { runnable ->
            Thread(runnable, "order-retry-${threadNumber.getAndIncrement()}").apply { isDaemon = true }
        }
        return DelayedOrderRetryScheduler(executor)
    }
}
