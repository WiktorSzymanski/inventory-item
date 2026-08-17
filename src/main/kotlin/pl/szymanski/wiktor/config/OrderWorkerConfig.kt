package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.szymanski.wiktor.service.OrderRetryScheduler
import pl.szymanski.wiktor.service.OrderWorkerPool

@ConfigurationProperties("app.order-worker")
data class OrderWorkerProperties(
    val threads: Int = 8,
    // INERT now. The merged pool is a ScheduledThreadPoolExecutor, whose DelayedWorkQueue is
    // unbounded by construction — which is what the shipped Int.MAX_VALUE default asked for anyway,
    // so no run that used the default changes behaviour. Kept as a property, and warned about
    // below, because docker-compose still exports ORDER_WORKER_QUEUE_CAPACITY to every TO branch
    // and a knob that silently does nothing is worse than one that says so.
    val queueCapacity: Int = Int.MAX_VALUE,
)

/**
 * ONE pool, where this branch used to have two.
 *
 * The previous wiring declared `orderWorkerExecutor` (150 threads, first attempts) and a separate
 * `order-retry-*` pool (50 threads) that served the backoff and executed the retried attempt.
 * Here [OrderWorkerPool] does both at `150 + 50 = 200` threads, so total executing width and the
 * connection demand are unchanged and only retry TOPOLOGY moves. `app.order-retry` is gone;
 * `ORDER_RETRY_THREADS` and `ORDER_RETRY_EXECUTE_ON_RETRY_POOL` are still exported by
 * docker-compose to every TO branch and are inert here — logged at startup so a run cannot mistake
 * them for effective. TO-3 keeps the two-pool topology, which is what makes it the comparison.
 *
 * **The bean TYPES here are load-bearing, and getting one wrong fails silently rather than loudly.**
 * Two Boot conditionals key off them:
 *
 *  - `applicationTaskExecutor` is `@ConditionalOnMissingBean(Executor.class)`. [OrderWorkerPool] is
 *    a `TaskExecutor`, hence an `Executor`, so Boot still backs off and `@Async` resolution is
 *    exactly what it was before the merge — which on THIS branch means the SimpleAsyncTaskExecutor
 *    fallback described on the bean below, not this pool. Declaring the pool as something that is
 *    not an `Executor` would let Boot create `applicationTaskExecutor` and quietly move `@Async`
 *    work onto it.
 *  - `TaskSchedulingAutoConfiguration` is
 *    `@ConditionalOnMissingBean({SchedulingConfigurer, TaskScheduler, ScheduledExecutorService})`.
 *    [OrderWorkerPool] is deliberately NONE of those, even though it wraps a
 *    `ScheduledThreadPoolExecutor`. Declaring it as a `ThreadPoolTaskScheduler` or exposing the raw
 *    executor would back off Boot's `taskScheduler` — and with it `spring.task.scheduling.pool.size`
 *    — so `@Scheduled` work (`OutboxPollingPublisher`, `OutboxMetrics`) would migrate onto this
 *    200-thread pool. That is the "a retry wait parked on the outbox scheduler" failure the retry
 *    pool was split out to avoid, arriving from the opposite direction.
 *
 * `orderRetryScheduler` stays a bean of the custom `OrderRetryScheduler` type for the same reason it
 * was one before: it trips no conditional. It must NOT be [OrderWorkerPool] itself — if the pool
 * implemented `OrderRetryScheduler`, `InventoryService` would have two candidates to inject.
 */
@Configuration
@EnableConfigurationProperties(OrderWorkerProperties::class)
class OrderWorkerConfig {

    private val log = LoggerFactory.getLogger(OrderWorkerConfig::class.java)

    /**
     * The whole retry topology. First attempts arrive through `execute()`, retries through
     * `schedule()`, both on `order-worker-*` threads; the backoff itself is served in the executor's
     * DelayedWorkQueue and holds no thread and no connection.
     *
     * Unbounded queue, so the pool never rejects and excess load is absorbed in memory (degrade)
     * rather than shed with HTTP 503 — same contract as the Int.MAX_VALUE worker queue it replaces,
     * and as the ES branches' unbounded async executors. Admission stays bounded by the Tomcat pool.
     */
    @Bean(destroyMethod = "close")
    fun orderWorkerExecutor(properties: OrderWorkerProperties, meterRegistry: MeterRegistry): OrderWorkerPool {
        // NOTE @Async work does NOT land on this pool on this branch, unlike TO-2/TO-3/TO-4.
        //
        // Because an Executor bean exists in this context, Boot's `applicationTaskExecutor` is
        // never created (its `TaskExecutorConfigurations.OnExecutorCondition` is
        // `@ConditionalOnMissingBean(Executor.class)`). On the branches with a single TaskExecutor
        // bean, Spring's @Async resolution then finds this pool and
        // `InventoryService.onOrderCreated` — the @ApplicationModuleListener that marks the
        // publication complete — runs on it. TO-1 has TWO such beans, this one and
        // `outboxPollerExecutor`, so `AsyncExecutionAspectSupport.getDefaultExecutor` gets a
        // NoUniqueBeanDefinition, finds no bean named `taskExecutor` either, and falls back to
        // SimpleAsyncTaskExecutor — a NEW THREAD PER INVOCATION, unbounded.
        //
        // Pre-existing, not introduced by the pool merge, and left alone deliberately: changing it
        // would change what TO-1 measures. The reservation itself still runs on this bounded pool,
        // so the exposure is one short-lived thread per accepted order.
        if (properties.queueCapacity != Int.MAX_VALUE) {
            log.warn(
                "[POOLS] ORDER_WORKER_QUEUE_CAPACITY={} is INERT: the merged pool is a " +
                    "ScheduledThreadPoolExecutor and its DelayedWorkQueue is unbounded. A run that set it " +
                    "expecting queue-full shedding did not get any.",
                properties.queueCapacity,
            )
        }
        log.info(
            "[POOLS] order-worker={} threads, serving first attempts AND retries (this branch used to " +
                "split the same work across 150 worker + 50 retry, as TO-3 still does). " +
                "ORDER_RETRY_THREADS and ORDER_RETRY_EXECUTE_ON_RETRY_POOL are INERT — there is no " +
                "second pool to size or to choose. A run left at docker-compose's " +
                "ORDER_WORKER_THREADS=150 default is 50 execution threads NARROWER than the two-pool " +
                "topology it is being compared with; export ORDER_WORKER_THREADS=200.",
            properties.threads,
        )

        val pool = OrderWorkerPool(properties.threads)

        // Micrometer's standard executor metrics, which is what puts this pool in the dashboards'
        // "Busy threads by pool" panel: it queries executor_active_threads /
        // executor_pool_size_threads / executor_queued_tasks by {{name}}. Those names come from
        // Boot's auto-instrumentation of ThreadPoolTaskExecutor beans, which this is not — so bind
        // them by hand, with the same name Boot used (the bean name), so no dashboard changes.
        //
        // The MeterBinder form is deliberate over ExecutorServiceMetrics.monitor(), which WRAPS the
        // executor to time every submission. Gauges only: no per-task overhead on a hot path.
        // Same call the ES branches' CommandGatewayConfig.monitorPool makes.
        //
        // TWO OF THOSE SERIES NOW READ DIFFERENTLY, and both are properties of
        // ScheduledThreadPoolExecutor rather than of this code:
        //   executor_pool_max_threads = 2147483647, NOT the width. An STPE grows only to its core
        //     size and leaves maximumPoolSize at Integer.MAX_VALUE, where a ThreadPoolTaskExecutor
        //     reports its configured width. Read executor_pool_core_threads for the width — a panel
        //     plotting max against active would draw a ceiling nine orders of magnitude too high
        //     and make a saturated pool look idle.
        //   executor_queued_tasks includes retries still serving out their backoff, since the
        //     DelayedWorkQueue holds both. Subtract order_retry_pool_queued for ready backlog.
        ExecutorServiceMetrics(pool.executor, "orderWorkerExecutor", emptyList()).bindTo(meterRegistry)

        // Kept under the SAME NAME so the existing panel target resolves, but it means something
        // narrower now: it used to be the retry pool's queue.size, which is retries in backoff PLUS
        // retries waiting for a retry thread. There is no second queue any more — waiting for a
        // thread is `executor_queued_tasks` — so this is strictly "still serving out the backoff",
        // and the ready backlog is executor_queued_tasks minus this.
        //
        // order.retry.pool.active is NOT published: there is no separate pool to be active on, and
        // a constant 0 would read as "the retry lane is idle" rather than "there is no retry lane".
        // executor_active_threads covers both roles at once here, by construction.
        Gauge.builder("order.retry.pool.queued", pool) { it.backoffInFlight().toDouble() }
            .description("Retries scheduled but not yet started (still serving out their backoff)")
            .register(meterRegistry)

        return pool
    }

    /**
     * The seam `InventoryService` retries through, and the reason the retried attempt lands back on
     * the worker pool: this hands `schedule()` to the very pool that ran the first attempt.
     *
     * A lambda rather than the pool itself, so exactly one bean satisfies `OrderRetryScheduler` —
     * see the class doc.
     */
    @Bean
    fun orderRetryScheduler(orderWorkerExecutor: OrderWorkerPool): OrderRetryScheduler =
        OrderRetryScheduler { delayMs, task -> orderWorkerExecutor.schedule(delayMs, task) }
}
