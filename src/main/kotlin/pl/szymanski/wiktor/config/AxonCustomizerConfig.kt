package pl.szymanski.wiktor.config

import org.axonframework.common.caching.NoCache
import org.axonframework.common.caching.WeakReferenceCache
import org.axonframework.config.AggregateConfigurer
import org.axonframework.config.Configurer
import org.axonframework.config.EventProcessingConfigurer
import org.axonframework.eventhandling.TrackingEventProcessorConfiguration
import org.springframework.beans.factory.annotation.Autowired
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
        configurer.registerTrackingEventProcessorConfiguration("order-saga") { _ ->
            TrackingEventProcessorConfiguration.forParallelProcessing(sagaProps.segments)
                .andInitialTrackingToken { source -> source.createTailToken() }
        }
    }

    @Autowired
    fun configureInventoryItemCache(configurer: Configurer, cacheProperties: CacheProperties) {
        val cache: org.axonframework.common.caching.Cache =
            if (cacheProperties.enabled) WeakReferenceCache() else NoCache.INSTANCE
        configurer.configureAggregate(
            AggregateConfigurer.defaultConfiguration(InventoryItem::class.java)
                .configureCache { cache }
        )
    }
}
