package pl.szymanski.wiktor.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
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

/**
 * Backup delivery for publications whose NOTIFY was lost — a crash between the outbox insert
 * committing and delivery completing, or a listener failure that rolled the claim back.
 * NOTIFY/LISTEN is the primary path, so this poller only sweeps leftovers and its interval does
 * not affect normal publish latency. Routing through the same claim-guarded
 * [EventPublicationDirectProcessor] makes races with the NOTIFY path harmless.
 *
 * republication-min-age keeps the sweep away from publications whose delivery may still be in
 * flight; with sub-second NOTIFY delivery, PT1M is very conservative.
 */
@Component
class IncompleteEventRepublisher(
    private val processor: EventPublicationDirectProcessor,
    @Value("\${spring.modulith.events.republication-min-age:PT1M}")
    private val minAge: Duration,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(fixedDelayString = "\${spring.modulith.events.republication-interval:PT1M}")
    fun republishIncomplete() {
        processor.findIncompleteIds(minAge).forEach { id ->
            runCatching { processor.process(id) }
                .onFailure { e -> log.error("Failed to redeliver publication {}", id, e) }
        }
    }
}
