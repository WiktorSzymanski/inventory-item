package pl.szymanski.wiktor.config

import org.axonframework.config.EventProcessingConfigurer
import org.axonframework.eventhandling.TrackingEventProcessorConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

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
