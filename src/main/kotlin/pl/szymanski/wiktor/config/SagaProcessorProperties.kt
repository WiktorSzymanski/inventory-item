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
    // ES-4-bounded: how many not-yet-started orders may sit in the intake queue at once. When it
    // is full an order-saga segment thread BLOCKS in @StartSaga, so the processor stops reading
    // OrderCreatedEvents and the backlog waits in the durable event store rather than on the heap.
    //
    // 112 is a derivation, not a taste: it equals CommandGatewayConfig.COMMAND_POOL_SIZE, so the
    // extra wait an in-flight saga pays per step is at most ONE full command-pool turn of incoming
    // work. It is the mildest bound that still closes the loop — ES-4 has none at all — and
    // sweeping it down (32, 8) is the experiment this branch exists for. Overridable per run via
    // AXON_SAGA_INTAKE_CAPACITY; the resolved value is logged as part of the [POOLS] line and
    // published as saga_intake_capacity.
    val intakeCapacity: Int = 112,
)
