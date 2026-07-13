package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@ConfigurationProperties("app.outbox-poller")
data class OutboxPollerProperties(
    val threads: Int = 1,
)

@Configuration
@EnableConfigurationProperties(OutboxPollerProperties::class)
class OutboxPollerConfig {

    /**
     * Delivery workers for the outbox poller. Publications are claim-guarded in
     * [EventPublicationDirectProcessor], so parallel delivery of distinct ids is safe; global
     * publication order is only preserved with a single thread.
     */
    @Bean
    fun outboxPollerExecutor(properties: OutboxPollerProperties): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = properties.threads
            maxPoolSize = properties.threads
            setThreadNamePrefix("outbox-poller-")
        }
}
