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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
     *
     * **The queue is BOUNDED on this branch, and that is the whole safety argument for pushing the
     * payload.** `Executors.newFixedThreadPool` hands out an unbounded LinkedBlockingQueue, which
     * was survivable while a NOTIFY was a wake-up: the drain coalesced any burst into one pass and
     * submitted at most one bounded page at a time. A payload-carrying NOTIFY cannot coalesce —
     * every notification carries different bytes — so the listener submits per row, and with an
     * unbounded queue that is exactly the open loop commit 2185068 removed: a commit burst becomes
     * a delivery burst that drives the order-worker pool into row contention (measured then at
     * db_write p95 404 ms and conflict_ratio 4.64, against TO-1's 0.96 ms / 0.37).
     *
     * [BlockingSubmitPolicy] turns a full queue into backpressure instead of a rejection, so the
     * bound propagates: the listener thread stops draining PostgreSQL's async-notify queue, that
     * queue fills, and committing writers slow down at pg_notify. The limit is enforced against the
     * producers rather than absorbed in RAM.
     */
    @Bean(name = ["eventDeliveryExecutor"], destroyMethod = "shutdown")
    fun eventDeliveryExecutor(
        @Value("\${app.event-delivery.threads:20}") threads: Int,
        @Value("\${app.event-delivery.queue-capacity:1000}") queueCapacity: Int,
    ): ExecutorService =
        ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            { r -> Thread(r, "event-delivery").apply { isDaemon = true } },
            BlockingSubmitPolicy(),
        )
}

/**
 * Rejection policy that does not reject: a submit onto a full queue BLOCKS the submitting thread
 * until a slot frees.
 *
 * `CallerRunsPolicy` is the usual answer and is wrong here. The caller is the single
 * `pg-notify-listener` thread; having it run a delivery inline would stop it reading notifications
 * for the duration, and a delivery can take as long as an order-worker submit plus a commit. Worse,
 * it would silently exceed the pool's width — the thing the bound exists to guarantee.
 *
 * Blocking on `queue.put` keeps the width exact and pushes the wait onto the one thread whose
 * stalling IS the backpressure signal: while it waits, PostgreSQL's async-notify queue grows and
 * `pg_notify` at commit gets slower, which is the bound reaching the writers.
 *
 * The shutdown check is not optional. `queue.put` on a pool that will never run another task blocks
 * forever, so a submit racing @PreDestroy would hang shutdown; after `shutdown()` the honest answer
 * is a rejection.
 */
class BlockingSubmitPolicy : RejectedExecutionHandler {
    override fun rejectedExecution(r: Runnable, executor: ThreadPoolExecutor) {
        if (executor.isShutdown) {
            throw RejectedExecutionException("event delivery pool is shut down")
        }
        try {
            executor.queue.put(r)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RejectedExecutionException("interrupted while waiting for delivery queue space", e)
        }
    }
}

/**
 * The second delivery process: everything the NOTIFY path did not deliver.
 *
 * On TO-2 this sweeps up what the cursor drain advanced past. Here there is no advancing cursor to
 * be behind — the drain runs only on (re)connect — so this is a plain bounded scan of whatever is
 * still incomplete, and it owns three distinct populations:
 *
 * - **Events too large for a NOTIFY payload.** V10's trigger sends nothing above ~8 kB, so for
 *   those rows this pass is not a backstop, it is the delivery path. `PAYLOAD_BYTES` at the
 *   campaign's C10/C11 cells puts every seeded `InventoryCreatedEvent` here.
 * - **Notifications sent while no LISTEN was active.** NOTIFY is fire-and-forget; the reconnect
 *   drain covers most of this, this covers the rest.
 * - **Failed deliveries**, whose claim rolled back with them.
 *
 * Bounded and executor-backed rather than an unbounded serial loop: the old shape delivered one row
 * at a time on Spring's single shared scheduler thread, which would block OutboxMetrics for seconds
 * and cap rescue throughput at whatever one thread can do.
 *
 * Interval and min-age are unchanged at PT1M/PT1M: `outbox.sweep.rescued` measures how much work
 * actually falls to this path, and that number is what should decide the cadence. Both are env
 * knobs, so tuning needs no rebuild.
 */
@Component
class IncompleteEventRepublisher(
    private val processor: EventPublicationDirectProcessor,
    @Qualifier("eventDeliveryExecutor") private val executor: ExecutorService,
    meterRegistry: MeterRegistry,
    @Value("\${spring.modulith.events.republication-min-age:PT1M}")
    private val minAge: Duration,
    @Value("\${app.outbox-sweep.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${app.outbox-sweep.max-batches:10}")
    private val maxBatches: Int,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Rows this path delivered that the push path did not.
     *
     * On this branch that is mostly the oversize population, so it doubles as the count of events
     * that did not fit in a NOTIFY. A non-zero value at a workload with no padding means
     * notifications are being LOST, which is a different and much more interesting fault.
     */
    private val rescued: Counter = meterRegistry.counter("outbox.sweep.rescued")

    @Scheduled(fixedDelayString = "\${spring.modulith.events.republication-interval:PT1M}")
    fun republishIncomplete() = sweepByScan()

    /**
     * Deliver everything still incomplete, in bounded pages, oldest first.
     *
     * **Position-free, unlike TO-2's, and it has to be.** TO-2 sweeps `seq <= cursor` because its
     * drain runs continuously and leaves strands *behind* an advancing cursor. Here the drain runs
     * only on (re)connect, so the cursor is frozen at wherever startup left it — usually 0, on an
     * empty table — and `seq <= 0` matches nothing. A cursor-bounded sweep on this branch is not
     * merely narrow, it is empty: an event that misses its NOTIFY would never be delivered at all.
     * Verified the hard way, with a 20 kB event stranded at seq 200 under a cursor of 0.
     *
     * That matters more here than it would on TO-2. This branch's fallback is not only a crash
     * backstop: an event too large for a NOTIFY payload gets no notification by design (V10), so
     * this pass is its ONLY delivery path.
     *
     * Bounded and executor-backed for the reasons the cursor sweep was: one pass must not run
     * unboundedly long, and it must not deliver one row at a time on Spring's shared scheduler
     * thread. The page is unordered, like the drain's — see [EventPublicationDirectProcessor
     * .findIncompleteIds] — so the planner can go straight at the partial index instead of walking
     * a growing prefix of delivered rows.
     */
    private fun sweepByScan() {
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

            // The query is not a cursor, so a page that delivers nothing is refetched verbatim.
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
            log.debug("[OUTBOX] sweep rescued {} publication(s) the push path did not deliver", total)
        }
    }

    private fun deliver(id: UUID): Boolean =
        runCatching { processor.process(id) }
            .onFailure { e -> log.error("Failed to redeliver publication {}", id, e) }
            .isSuccess
}
