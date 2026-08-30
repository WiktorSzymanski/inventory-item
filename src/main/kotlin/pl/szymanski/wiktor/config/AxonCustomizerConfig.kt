package pl.szymanski.wiktor.config

import org.axonframework.config.EventProcessingConfigurer
import org.axonframework.eventhandling.TrackingEventProcessorConfiguration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(SagaProcessorProperties::class)
class AxonCustomizerConfig {

    @Autowired
    fun configureProcessors(
        configurer: EventProcessingConfigurer,
        sagaProps: SagaProcessorProperties,
        @Value("\${axon.jdbc.pool.size:50}") axonPoolSize: Int,
    ) {
        configurer
            .registerTrackingEventProcessor("inventory-projection")
            .registerTrackingEventProcessor("mock-kafka-publisher")
            .registerTrackingEventProcessor("order-projection")
            .registerTrackingEventProcessor("order-saga")
            // Subscribing (not tracking): runs on the replica that published the event, so the
            // reserve-applied counter reflects true per-replica append distribution.
            .registerSubscribingEventProcessor("reserve-metrics")
        configurer.registerTrackingEventProcessorConfiguration("inventory-projection") { _ ->
            TrackingEventProcessorConfiguration.forSingleThreadedProcessing()
                .andBatchSize(100)
        }
        configurer.registerTrackingEventProcessorConfiguration("mock-kafka-publisher") { _ ->
            TrackingEventProcessorConfiguration.forSingleThreadedProcessing()
                .andBatchSize(100)
        }
        configurer.registerTrackingEventProcessorConfiguration("order-projection") { _ ->
            TrackingEventProcessorConfiguration.forSingleThreadedProcessing()
                .andBatchSize(100)
        }
        // Per-node claim count = ceil(totalSegments / replicas), so each replica claims a fair,
        // even share of the fixed segment pool. Only the claim count changes with replica count;
        // totalSegments stays constant (no token reset needed when scaling up/down).
        val sagaThreadsPerNode = (sagaProps.totalSegments + sagaProps.replicas - 1) / sagaProps.replicas
        configurer.registerTrackingEventProcessorConfiguration("order-saga") { _ ->
            TrackingEventProcessorConfiguration.forParallelProcessing(sagaThreadsPerNode)
                .andInitialSegmentsCount(sagaProps.totalSegments)
                // Head, not tail: on a (re)init the saga starts from the END of the stream and only
                // processes NEW orders. A token reset (e.g. changing total-segments) therefore does
                // NOT replay the whole order history. createTailToken() would replay from the start.
                .andInitialTrackingToken { source -> source.createHeadToken() }
                .andBatchSize(100)
        }
        logPoolBudget(sagaProps, sagaThreadsPerNode, axonPoolSize)
    }

    /**
     * The connection budget for the configuration that is ACTUALLY running.
     *
     * [CommandGatewayConfig.SAGA_SEGMENT_THREADS] hardcodes the default 60 so
     * RetryDispatchTargetTest can assert `2 x (112 + 60 + 3) = 350` without a Spring
     * context. Once `AXON_SAGA_TOTAL_SEGMENTS` can move the real count, that assertion
     * no longer describes every run, and the failure mode it guards against is silent:
     * CommandGatewayConfig documents that a starved command stalls on the 5s
     * connectionTimeout and then fails TERMINALLY into the saga's abandon() path,
     * because a SQLTransientConnectionException is not a ConcurrencyException and is
     * therefore not retried. It surfaces as latency and a rejection rate, not an error.
     *
     * Only one direction is dangerous. FEWER segments shrink demand -- the pool is merely
     * oversized. MORE than the default push demand past the 350 that docker-compose
     * passes, so that case warns.
     */
    private fun logPoolBudget(
        sagaProps: SagaProcessorProperties,
        sagaThreadsPerNode: Int,
        axonPoolSize: Int,
    ) {
        val busyThreads = CommandGatewayConfig.COMMAND_POOL_SIZE +
            sagaThreadsPerNode +
            CommandGatewayConfig.SINGLE_THREADED_PROJECTIONS
        val peakDemand = CommandGatewayConfig.CONNECTIONS_PER_BUSY_THREAD * busyThreads
        val budget = "segments=${sagaProps.totalSegments} replicas=${sagaProps.replicas} " +
            "threads=$sagaThreadsPerNode | peak demand = " +
            "${CommandGatewayConfig.CONNECTIONS_PER_BUSY_THREAD} x " +
            "(${CommandGatewayConfig.COMMAND_POOL_SIZE} command + $sagaThreadsPerNode saga + " +
            "${CommandGatewayConfig.SINGLE_THREADED_PROJECTIONS} projections) = $peakDemand " +
            "| axon pool = $axonPoolSize"
        if (peakDemand > axonPoolSize) {
            log.warn(
                "[POOLS] {} -- OVERSUBSCRIBED. Commands will stall 5s on connectionTimeout and " +
                "fail terminally into the saga's abandon() path, showing up as latency and " +
                "rejections rather than errors. Lower AXON_SAGA_TOTAL_SEGMENTS or raise " +
                "AXON_JDBC_POOL_SIZE (and PG max_connections with it).",
                budget,
            )
        } else {
            log.info("[POOLS] {}", budget)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AxonCustomizerConfig::class.java)
    }

    // ES-4: the former manual configureAggregate(InventoryItem) + StrongCache/NoCache wiring
    // was removed. InventoryItem is now registered solely via @Aggregate(repository = "inventoryItemRepository"),
    // which resolves the previous @Aggregate + manual-config dual registration (in which the manual cache
    // configuration was silently ignored). See AxonConfig.inventoryItemRepository.
}
