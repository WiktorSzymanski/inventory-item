package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("cache")
data class CacheProperties(val enabled: Boolean = true)
