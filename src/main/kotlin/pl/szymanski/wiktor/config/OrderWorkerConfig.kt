package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@ConfigurationProperties("app.order-worker")
data class OrderWorkerProperties(
    val threads: Int = 8,
    val queueCapacity: Int = Int.MAX_VALUE,
)

@Configuration
@EnableConfigurationProperties(OrderWorkerProperties::class)
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
}
