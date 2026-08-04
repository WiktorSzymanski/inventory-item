package pl.szymanski.wiktor.config

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
@EnableConfigurationProperties(SagaProcessorProperties::class)
class AxonCustomizerConfig {

    @Autowired
    fun configureProcessors(configurer: EventProcessingConfigurer, sagaProps: SagaProcessorProperties) {
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
    }

    @Autowired
    fun configureInventoryItem(
        configurer: Configurer,
        @Qualifier("inventorySnapshotTrigger") snapshotTrigger: SnapshotTriggerDefinition,
    ) {
        // No cache configured → Axon uses an uncached EventSourcingRepository for InventoryItem.
        configurer.configureAggregate(
            AggregateConfigurer.defaultConfiguration(InventoryItem::class.java)
                .configureSnapshotTrigger { snapshotTrigger }
        )
    }
}
