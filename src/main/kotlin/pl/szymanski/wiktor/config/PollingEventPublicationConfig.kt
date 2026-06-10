package pl.szymanski.wiktor.config

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.modulith.events.core.EventPublicationRegistry
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

@Configuration
@EnableScheduling
class PollingEventPublicationConfig {

    /**
     * Replaces Modulith's auto-configured PersistentApplicationEventMulticaster with our custom
     * PollingOnlyEventMulticaster. A BeanDefinitionRegistryPostProcessor is needed because:
     * - Modulith's auto-config does NOT use @ConditionalOnMissingBean on this bean.
     * - Auto-configs are processed AFTER user @Configuration classes, so a normal @Bean would
     *   be overridden by the auto-config.
     * - This BDRPP runs AFTER all @Configuration classes (including auto-configs) are processed,
     *   giving us a guaranteed window to remove and replace the definition.
     */
    @Bean
    fun eventMulticasterRegistrar(): BeanDefinitionRegistryPostProcessor =
        object : BeanDefinitionRegistryPostProcessor {

            override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
                if (registry.containsBeanDefinition("applicationEventMulticaster")) {
                    registry.removeBeanDefinition("applicationEventMulticaster")
                }
            }

            override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
                // All three suppliers are lazy: the beans they wrap are not yet instantiated at
                // this point in the context lifecycle.
                val multicaster = PollingOnlyEventMulticaster(
                    { beanFactory.getBean(EventPublicationRegistry::class.java) },
                    { beanFactory.getBean(EventPublicationRepository::class.java) },
                    { beanFactory.getBean(Environment::class.java) },
                )
                multicaster.setBeanFactory(beanFactory)

                // registerSingleton adds the instance to manualSingletonNames, so type-based
                // injection of IncompleteEventPublications still resolves to this bean.
                beanFactory.registerSingleton("applicationEventMulticaster", multicaster)
            }
        }
}

@Component
class EventPublicationPoller(
    private val incompleteEventPublications: IncompleteEventPublications,
) {
    @Scheduled(fixedDelayString = "\${spring.modulith.events.polling-interval:PT10S}")
    fun pollAndPublish() {
        incompleteEventPublications.resubmitIncompletePublicationsOlderThan(Duration.ZERO)
    }
}
