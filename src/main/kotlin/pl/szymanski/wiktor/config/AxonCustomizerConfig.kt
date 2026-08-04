package pl.szymanski.wiktor.config

import org.axonframework.common.caching.Cache
import org.axonframework.common.caching.NoCache
import org.axonframework.common.caching.WeakReferenceCache
import org.axonframework.config.EventProcessingConfigurer
import org.axonframework.eventhandling.TrackingEventProcessorConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(CacheProperties::class, SagaProcessorProperties::class)
class AxonCustomizerConfig {

    @Autowired
    fun configureProcessors(configurer: EventProcessingConfigurer, sagaProps: SagaProcessorProperties) {
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
    }

    /**
     * Referenced by name from [pl.szymanski.wiktor.domain.InventoryItem]'s `@Aggregate(cache = ...)`.
     * WeakReferenceCache means entries can be collected at any time, so ES-3's hit rate is a
     * function of GC pressure as well as workload — measure it before drawing cache conclusions.
     */
    @Bean("inventoryItemCache")
    fun inventoryItemCache(cacheProperties: CacheProperties): Cache =
        if (cacheProperties.enabled) WeakReferenceCache() else NoCache.INSTANCE
}
