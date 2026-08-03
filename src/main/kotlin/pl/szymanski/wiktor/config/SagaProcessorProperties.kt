package pl.szymanski.wiktor.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("axon.saga")
data class SagaProcessorProperties(
    // Fixed total number of order-saga segments, set once at token init. Constant across
    // replica counts — 60 splits evenly for 2/3/4/5/6 replicas. Identical on every ES
    // branch so the variants differ only in their persistence/concurrency strategy.
    // Changing it requires resetting the order-saga tokens.
    val totalSegments: Int = 60,
    // How many replicas are running. Per-node claim count is derived as
    // ceil(totalSegments / replicas) so segments spread evenly across replicas. MUST equal
    // REPLICAS in .env (wired through the API_REPLICAS env var); if it is too low, some
    // segments stay unclaimed and the orders routed to them are never processed.
    val replicas: Int = 1,
)
