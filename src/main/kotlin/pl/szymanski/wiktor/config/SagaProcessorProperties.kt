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
    // Width of the `saga-command` pool, which on this branch is the ONLY execution lane: first
    // attempts, retries and the saga's terminal dispositions all run there (see
    // CommandGatewayConfig.RETRY_POOL_SIZE for why the retry lane executes nothing).
    //
    // 112 is a derivation, not a taste — it is what makes peak connection demand
    // 2 x (112 command + 60 saga + 3 projections) = 350, exactly the AXON_JDBC_POOL_SIZE
    // docker-compose passes. RAISING IT WITHOUT RAISING THAT POOL STARVES COMMANDS, and starvation
    // does not fail cleanly: a SQLTransientConnectionException is not a ConcurrencyException, so
    // ConcurrencyRetryScheduler declines to retry and the command fails terminally into the saga's
    // abandon() path after a 5s connectionTimeout — latency and rejections, not errors.
    //
    // Overridable per run via COMMAND_POOL; the resolved value is logged as part of the [POOLS]
    // line, which WARNS when the resulting demand exceeds the pool, and published as
    // executor_pool_core_threads{name="saga-command"} / command_pool in meta.json.
    val commandPoolSize: Int = CommandGatewayConfig.DEFAULT_COMMAND_POOL_SIZE,
)
