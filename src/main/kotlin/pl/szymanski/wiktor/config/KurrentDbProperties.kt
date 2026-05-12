package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("kurrentdb")
data class KurrentDbProperties(
    val connectionString: String = "kurrentdb://localhost:2113?tls=false",
)
