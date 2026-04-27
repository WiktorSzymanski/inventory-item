package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "outbox")
data class OutboxProperties(
    val pollIntervalMs: Long = 500L,
    val batchSize: Int = 10,
)
