package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("snapshot")
data class SnapshotProperties(
    val enabled: Boolean = true,
    val eventCount: Int = 50,
)
