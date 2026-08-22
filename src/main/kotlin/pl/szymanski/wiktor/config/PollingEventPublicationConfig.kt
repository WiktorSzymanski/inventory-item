package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
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
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    /**
     * The one pool that executes deliveries, shared by BOTH delivery processes.
     *
     * It used to be private to [PostgresNotificationListener]. It is a bean now because
     * [IncompleteEventRepublisher] has to submit to it as well: sharing it is what keeps
     * "deliveries in flight never exceed the pool width" true of the branch rather than merely of
     * the NOTIFY path. Two pools would mean the sweep could double the concurrent load on the order
     * workers at exactly the moment the drain is already busy.
     *
     * `destroyMethod` is named rather than inferred. Spring's inference prefers `close()`, which on
     * Java 21 ExecutorService blocks until every task finishes — a shutdown that hangs behind an
     * in-flight delivery.
     */
    @Bean(name = ["eventDeliveryExecutor"], destroyMethod = "shutdown")
    fun eventDeliveryExecutor(
        @Value("\${app.event-delivery.threads:20}") threads: Int,
    ): ExecutorService =
        Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "event-delivery").apply { isDaemon = true }
        }
}

/**
 * The second delivery process: everything the cursor drain moved past without delivering.
 *
 * With `app.outbox-cursor.enabled=true` this is no longer the rare-leftover backstop it was
 * written as. [EventDrainLoop] advances its cursor to the highest seq it has SEEN, and a
 * publication's seq is assigned at INSERT — statement 1 of `OrderWriteCommandHandler.write`, while
 * the `inventory_state` row locks are statement 4. Under contention a transaction therefore holds a
 * low seq and commits well after higher ones have been drained, so rows are left behind the cursor
 * as a matter of course. Failed deliveries land here too, since the claim rolls back with them.
 *
 * Which makes this a bounded, executor-backed sweep rather than an unbounded serial loop. The old
 * shape delivered one row at a time on Spring's single shared scheduler thread — with strands
 * routine that would block OutboxMetrics for seconds at a time and cap rescue throughput at
 * whatever one thread can do.
 *
 * Interval and min-age are unchanged at PT1M/PT1M: `outbox.sweep.rescued` measures how much work
 * actually falls to this path, and that number is what should decide the cadence. Both are env
 * knobs, so tuning needs no rebuild.
 *
 * With the cursor disabled this reverts exactly to the old unbounded scan, so the A/B compares two
 * whole topologies and not a half-changed one.
 */
@Component
class IncompleteEventRepublisher(
    private val processor: EventPublicationDirectProcessor,
    private val cursorStore: OutboxCursorStore,
    @Qualifier("eventDeliveryExecutor") private val executor: ExecutorService,
    meterRegistry: MeterRegistry,
    @Value("\${spring.modulith.events.republication-min-age:PT1M}")
    private val minAge: Duration,
    @Value("\${app.outbox-cursor.enabled:true}")
    private val cursorEnabled: Boolean,
    @Value("\${app.outbox-sweep.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${app.outbox-sweep.max-batches:10}")
    private val maxBatches: Int,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Rows this path delivered that the drain did not — the strand rate, and the single number that
     * says whether the cursor's eager advance is cheap or expensive on this workload.
     */
    private val rescued: Counter = meterRegistry.counter("outbox.sweep.rescued")

    @Scheduled(fixedDelayString = "\${spring.modulith.events.republication-interval:PT1M}")
    fun republishIncomplete() {
        if (cursorEnabled) sweepBehindCursor() else republishByScan()
    }

    private fun sweepBehindCursor() {
        // Read once. A cursor that moved on during the sweep only means the next sweep covers
        // more; widening the window mid-pass would let one pass run unboundedly.
        val cursor = cursorStore.load()
        var total = 0
        var batches = 0

        while (batches < maxBatches) {
            val ids = processor.findIncompleteUpTo(cursor, minAge, batchSize)
            batches++
            if (ids.isEmpty()) break

            val delivered = ids
                .map { id -> executor.submit<Boolean> { deliver(id) } }
                .map { future -> runCatching { future.get() }.getOrDefault(false) }
                .count { it }

            rescued.increment(delivered.toDouble())
            total += delivered

            // Same no-progress guard as the scan drain, and for the same reason: the sweep's query
            // is not a cursor, so a page that delivers nothing is refetched verbatim.
            if (delivered == 0) {
                log.error("Sweep page of {} publication(s) delivered nothing; ending pass", ids.size)
                break
            }
            if (ids.size < batchSize) break
        }

        if (total == 0) return
        if (batches >= maxBatches) {
            log.info("[OUTBOX] sweep hit its batch bound: {} rescued in {} batches, more remain", total, batches)
        } else {
            log.debug("[OUTBOX] sweep rescued {} publication(s) below cursor {}", total, cursor)
        }
    }

    private fun republishByScan() {
        processor.findIncompleteIds(minAge).forEach { id ->
            if (deliver(id)) rescued.increment()
        }
    }

    private fun deliver(id: UUID): Boolean =
        runCatching { processor.process(id) }
            .onFailure { e -> log.error("Failed to redeliver publication {}", id, e) }
            .isSuccess
}
