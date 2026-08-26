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
    @Value("\${app.outbox-cursor.watermark:false}")
    private val watermarkEnabled: Boolean,
    @Value("\${app.outbox-sweep.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${app.outbox-sweep.max-batches:10}")
    private val maxBatches: Int,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Rows this path delivered that the drain did not — the strand rate, and the single number that
     * says whether the cursor's eager advance is cheap or expensive on this workload.
     *
     * The companion question — how far the cursor over-RAN, which is what a safety margin would
     * have to cover — is NOT measurable from here, and an earlier version of this class was wrong
     * to try. `cursor - seq` sampled at sweep time reads the distance a row sits below the cursor
     * when the sweep reaches it, and the sweep only looks at rows older than
     * `republication-min-age` (PT1M). At ~80 orders/s that floor alone is tens of thousands of
     * seq, so the measurement is dominated by the min-age wait rather than by the over-run:
     * TO-2-fix-B_capacity_W-fast_20260826T121411Z read p99 = 112,640 against a true over-run two
     * orders of magnitude smaller.
     *
     * The over-run is already exported, by [pl.szymanski.wiktor.publisher.OutboxMetrics], as
     *
     *     outbox_cursor_position - outbox_oldest_incomplete_seq
     *
     * sampled every 5 s and free of that floor: `oldest.incomplete.seq` is `min(seq)` on the
     * partial index, one descent, and it counts a row from the moment it is visible rather than
     * from the moment it is a minute old. Positive means rows are stranded below the cursor and
     * the value is the worst outstanding over-run; at or below zero means nothing is stranded.
     */
    private val rescued: Counter = meterRegistry.counter("outbox.sweep.rescued")

    /**
     * Which sweep the drain arm needs. Three arms, same shape as [EventDrainLoop.drainAll].
     *
     * The seq cursor strands rows BELOW itself by design, so [sweepBehindCursor] looks exactly
     * there and nowhere else. The watermark cursor strands nothing below itself — everything under
     * `pg_snapshot_xmin` was seen — but it can leave rows stranded ABOVE it, because one
     * long-running transaction pins `xmin` and no below-cursor query can reach past it. So that arm
     * takes a position-free sweep, which finds anything older than the min-age wherever it sits.
     *
     * That position-free sweep is [boundedScanSweep] and NOT [republishByScan], which keeps its
     * unbounded shape for `cursorEnabled=false` alone — the pre-V8 SCAN arm, whose whole value is
     * being exactly what earlier TO-2 runs measured.
     */
    @Scheduled(fixedDelayString = "\${spring.modulith.events.republication-interval:PT1M}")
    fun republishIncomplete() {
        when {
            watermarkEnabled -> boundedScanSweep()
            cursorEnabled -> sweepBehindCursor()
            else -> republishByScan()
        }
    }

    /**
     * The watermark arm's sweep: position-free, and bounded where [republishByScan] is not.
     *
     * Unbounded, this walks the whole `completion_date IS NULL` region. Measured on
     * TO-2-fix-A_capacity_W-base_20260825T235228Z: 42,352 tuples per pass, ~28 passes a minute,
     * `outbox.sweep.rescued` 0 for the entire run. Worse than the wasted work, the query holds a
     * snapshot for its whole duration, and that snapshot pins the very `xmin` this arm's drain is
     * blocked on — the sweep stalls the drain it exists to back up. The LIMIT is what makes the
     * snapshot short, so the bound is a correctness fix for the drain and not merely tidiness here.
     */
    private fun boundedScanSweep() {
        var total = 0
        var batches = 0

        while (batches < maxBatches) {
            val ids = processor.findIncompleteIds(minAge, batchSize)
            batches++
            if (ids.isEmpty()) break

            val delivered = ids
                .map { id -> executor.submit<Boolean> { deliver(id) } }
                .map { future -> runCatching { future.get() }.getOrDefault(false) }
                .count { it }

            rescued.increment(delivered.toDouble())
            total += delivered

            // Same no-progress guard as sweepBehindCursor, same reason: this query carries no
            // position either, so a page that delivered nothing is refetched verbatim.
            if (delivered == 0) {
                log.error("Scan sweep page of {} publication(s) delivered nothing; ending pass", ids.size)
                break
            }
            if (ids.size < batchSize) break
        }

        if (total == 0) return
        if (batches >= maxBatches) {
            log.info("[OUTBOX] scan sweep hit its batch bound: {} rescued in {} batches, more remain", total, batches)
        } else {
            log.debug("[OUTBOX] scan sweep rescued {} publication(s)", total)
        }
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
