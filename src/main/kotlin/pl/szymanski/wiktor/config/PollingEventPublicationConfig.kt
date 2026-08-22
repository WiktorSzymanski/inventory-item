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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

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

                // registerSingleton adds the instance to manualSingletonNames so type-based
                // injection still resolves to this bean.
                beanFactory.registerSingleton("applicationEventMulticaster", multicaster)
            }
        }
}

/**
 * The SECOND delivery process: everything the cursor drain moved past without delivering.
 *
 * It exists only in cursor mode, and it is not the rare-leftover backstop that name suggests.
 * [pl.szymanski.wiktor.publisher.OutboxPollingPublisher] advances its cursor to the highest seq it
 * has SEEN, and a publication's seq is assigned at INSERT — statement 1 of
 * `OrderWriteCommandHandler.write`, while the `inventory_state` row locks are statement 4. Under
 * contention a transaction therefore holds a low seq and commits well after higher ones have been
 * drained, so rows are left behind the cursor as a matter of course. Failed deliveries land here
 * too, since the claim rolls back with them.
 *
 * Which makes this a bounded, executor-backed sweep rather than an unbounded serial loop: one pass
 * is capped at [batchSize] x [maxBatches] and every page goes through the SAME pool the drain uses,
 * so "deliveries in flight never exceed the pool width" stays true of the branch and not merely of
 * the fast path.
 *
 * **With the cursor disabled this does nothing at all**, which is where the branch deliberately
 * differs from TO-2. TO-2 falls back to an unbounded scan here; TO-1's drain in scan mode already
 * selects every incomplete row every 0.1 s, so a second scan could only find rows the tick just
 * failed on — which the next tick retries anyway. `app.outbox-cursor.enabled=false` therefore
 * reproduces the single-process TO-1 that every earlier run measured, exactly.
 *
 * Interval and min-age are PT1M/PT1M, matching TO-2 so the two branches differ only in the wake-up.
 * `outbox.sweep.rescued` measures how much work actually falls to this path, and THAT number is
 * what should decide the cadence. Both are env knobs, so tuning needs no rebuild.
 */
@Component
class IncompleteEventRepublisher(
    private val processor: EventPublicationDirectProcessor,
    private val cursorStore: OutboxCursorStore,
    // NOT a pool of its own. Sharing the drain's executor is what bounds the branch's total
    // delivery concurrency; two pools would let the sweep double the concurrent load on the order
    // workers at exactly the moment the drain is already busy.
    //
    // Kept as a ThreadPoolTaskExecutor rather than swapped for TO-2's plain ExecutorService so the
    // TaskExecutor set this branch presents to Spring is unchanged. That is a smaller claim than
    // the one OrderWorkerConfig's KDoc and variants.env make: they say this bean is WHY @Async
    // falls back to SimpleAsyncTaskExecutor here and not on TO-2/TO-3/TO-4, and it is not — Boot's
    // taskScheduler is a ThreadPoolTaskScheduler, hence already a second TaskExecutor, on every TO
    // branch. OrderWorkerPoolAutoConfigurationTest asserts both halves.
    @Qualifier("outboxPollerExecutor") private val executor: ThreadPoolTaskExecutor,
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
        if (!cursorEnabled) return
        sweepBehindCursor()
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

            // The sweep's query is not a cursor, so a page that delivers nothing is refetched
            // verbatim on the next iteration. Bail rather than spin on it.
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

    private fun deliver(id: UUID): Boolean =
        runCatching { processor.process(id) }
            .onFailure { e -> log.error("Failed to redeliver publication {}", id, e) }
            .isSuccess
}
