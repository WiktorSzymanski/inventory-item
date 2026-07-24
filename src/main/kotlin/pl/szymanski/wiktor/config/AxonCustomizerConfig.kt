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
        configurer.registerTrackingEventProcessorConfiguration("order-saga") { _ ->
            TrackingEventProcessorConfiguration.forParallelProcessing(sagaProps.segments)
                .andInitialTrackingToken { source -> source.createTailToken() }
                .andBatchSize(100)
        }
    }

    // ES-3-optimistic: the former manual configureAggregate(InventoryItem) + StrongCache/NoCache wiring
    // was removed. InventoryItem is now registered solely via @Aggregate(repository = "inventoryItemRepository"),
    // which resolves the previous @Aggregate + manual-config dual registration. See AxonConfig.inventoryItemRepository.
}
