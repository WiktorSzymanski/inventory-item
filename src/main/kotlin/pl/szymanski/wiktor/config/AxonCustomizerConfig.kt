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
        @Value("\${axon.mongo.pool.size:100}") mongoPoolSize: Int,
    ) {
        configurer
            .registerTrackingEventProcessor("inventory-projection")
            .registerTrackingEventProcessor("mock-kafka-publisher")
            .registerTrackingEventProcessor("order-projection")
            .registerTrackingEventProcessor("order-saga")
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
        logPoolBudget(sagaProps, sagaThreadsPerNode, mongoPoolSize)
    }

    /**
     * The connection budget for the configuration that is ACTUALLY running.
     *
     * [CommandGatewayConfig.SAGA_SEGMENT_THREADS] hardcodes the default 60 so
     * RetryDispatchTargetTest can assert `1 x (112 + 60 + 3) = 175` without a Spring
     * context. Once `AXON_SAGA_TOTAL_SEGMENTS` can move the real count, that assertion
     * no longer describes every run, and the failure mode it guards against is silent:
     * CommandGatewayConfig documents that a starved command blocks on the driver's
     * waitQueueTimeoutMS and then fails TERMINALLY into the saga's abandon() path,
     * because a MongoTimeoutException is not a ConcurrencyException and is therefore not
     * retried. It surfaces as latency and a rejection rate, not an error.
     *
     * Only one direction is dangerous. FEWER segments shrink demand -- the pool is merely
     * oversized. MORE than the default push demand up, so that case warns. The headroom
     * here is much larger than on ES-2 (175 against 400 rather than 350 against 350),
     * because the Mongo driver holds one connection per busy thread instead of two.
     */
    private fun logPoolBudget(
        sagaProps: SagaProcessorProperties,
        sagaThreadsPerNode: Int,
        mongoPoolSize: Int,
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
            "| mongo pool = $mongoPoolSize"
        if (peakDemand > mongoPoolSize) {
            log.warn(
                "[POOLS] {} -- OVERSUBSCRIBED. Commands will block on the driver's wait queue and " +
                "fail terminally into the saga's abandon() path, showing up as latency and " +
                "rejections rather than errors. Lower AXON_SAGA_TOTAL_SEGMENTS or raise " +
                "AXON_MONGO_POOL_SIZE.",
                budget,
            )
        } else {
            log.info("[POOLS] {}", budget)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AxonCustomizerConfig::class.java)
    }

    // ES-2's configureInventoryItem() is REMOVED here, and it had to be.
    //
    // It registered a SECOND configuration for InventoryItem —
    // `AggregateConfigurer.defaultConfiguration(...)`, i.e. a stock EventSourcingRepository on
    // LockingRepository's PESSIMISTIC default — alongside the one `@Aggregate` creates. Both
    // subscribe an AggregateAnnotationCommandHandler for the same command types, and on the command
    // bus the second subscription replaces the first, so commands were still being handled through
    // the pessimistic repository while `Configuration.repository(InventoryItem::class)` returned the
    // NullLockFactory bean. Measured, not theorised: with this method present the 100-way contention
    // test recorded ZERO optimistic retries; without it, retries in the hundreds. The lock removal
    // was a no-op and every signal short of the contention count said otherwise.
    //
    // Nothing is lost by dropping it: the snapshot trigger it configured now rides
    // `inventoryItemRepository` in AxonConfig, which is where it has to be anyway — Axon ignores
    // @Aggregate's `snapshotTriggerDefinition` attribute once `repository` is set.
}
