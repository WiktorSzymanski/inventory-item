package pl.szymanski.wiktor.config

import org.axonframework.common.caching.NoCache
import org.axonframework.common.caching.WeakReferenceCache
import org.axonframework.config.AggregateConfigurer
import org.axonframework.config.Configurer
import org.axonframework.config.EventProcessingConfigurer
import org.axonframework.eventhandling.TrackingEventProcessorConfiguration
import org.axonframework.eventsourcing.SnapshotTriggerDefinition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import pl.szymanski.wiktor.domain.InventoryItem

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
                .andInitialTrackingToken { source -> source.createHeadToken() }
            TrackingEventProcessorConfiguration.forParallelProcessing(sagaThreadsPerNode)
                .andInitialSegmentsCount(sagaProps.totalSegments)
                .andInitialTrackingToken { source -> source.createTailToken() }
                .andBatchSize(100)
        }
    }

    @Autowired
    fun configureInventoryItemCache(
        configurer: Configurer,
        cacheProperties: CacheProperties,
        @Qualifier("inventorySnapshotTrigger") snapshotTrigger: SnapshotTriggerDefinition,
    ) {
        val cache: org.axonframework.common.caching.Cache =
            if (cacheProperties.enabled) WeakReferenceCache() else NoCache.INSTANCE
        configurer.configureAggregate(
            AggregateConfigurer.defaultConfiguration(InventoryItem::class.java)
                .configureCache { cache }
                .configureSnapshotTrigger { snapshotTrigger }
        )
    }
}
