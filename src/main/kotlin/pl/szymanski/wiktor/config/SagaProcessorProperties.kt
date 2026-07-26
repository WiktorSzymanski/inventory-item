package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("axon.saga")
data class SagaProcessorProperties(
    // Fixed total number of order-saga segments, set once at token init. Constant across
    // replica counts — pick a value divisible by the replica counts you benchmark (60 splits
    // evenly for 2/3/4/5/6). Changing it requires resetting the order-saga tokens.
    val totalSegments: Int = 60,
    // How many replicas are running. Per-node claim count is derived as ceil(totalSegments/replicas)
    // so segments spread evenly across replicas. MUST match `docker compose --scale api-es=N`
    // (wired from the API_REPLICAS env); if it's too low, some segments stay unclaimed/unprocessed.
    val replicas: Int = 2,
)
