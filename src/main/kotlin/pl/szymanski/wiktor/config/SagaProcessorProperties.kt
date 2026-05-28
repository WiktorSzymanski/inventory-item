package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("axon.saga")
data class SagaProcessorProperties(val segments: Int = 32)
