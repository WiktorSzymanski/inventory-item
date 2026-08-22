package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.task.TaskExecutorMetricsAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import pl.szymanski.wiktor.service.OrderWorkerPool
import java.util.concurrent.ScheduledExecutorService

/**
 * What the merged pool does to Spring Boot's autoconfiguration — the part
 * [OrderRetrySchedulerWiringTest] cannot see, because a minimal context has no autoconfiguration in
 * it to be affected.
 *
 * Every failure here is silent. `@Scheduled` work quietly migrating onto the 200 order threads,
 * `@Async` quietly migrating off them, or `executor_*` registered twice and every dashboard panel
 * that groups by `name` doubling — none of it fails a boot, and a bench run would show it only as
 * an unexplained number.
 *
 * The real autoconfigurations are used rather than mirrored, so a Boot upgrade that changes one of
 * these conditions breaks this test instead of the next campaign.
 */
class OrderWorkerPoolAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                TaskExecutionAutoConfiguration::class.java,
                TaskSchedulingAutoConfiguration::class.java,
                TaskExecutorMetricsAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(OrderWorkerConfig::class.java, TestBeans::class.java)
        .withPropertyValues("app.order-worker.threads=3")

    /**
     * `@EnableScheduling` because Boot's `taskScheduler` is
     * `@ConditionalOnBean(SCHEDULED_ANNOTATION_PROCESSOR)` — without it there would be no scheduler
     * to back off and the assertion below would pass vacuously. The application enables it in
     * `PollingEventPublicationConfig`.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class TestBeans {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Test
    fun `the order pool is not a scheduler, and Boot still has one of its own`() {
        runner.run { context ->
            val pool = context.getBean("orderWorkerExecutor")
            assertInstanceOf(OrderWorkerPool::class.java, pool)
            assertInstanceOf(TaskExecutor::class.java, pool)
            assertFalse(pool is TaskScheduler, "the order pool must not be resolvable as a TaskScheduler")
            assertFalse(pool is ScheduledExecutorService, "the order pool must not be resolvable as a ScheduledExecutorService")

            // TaskSchedulerConfiguration is @ConditionalOnMissingBean({TaskScheduler,
            // ScheduledExecutorService}). Exposing the merged pool as either type would back Boot's
            // scheduler off, and the outbox drain and OutboxMetrics would then run on the 200
            // threads that run reservations — the collision the retry pool was split out to avoid,
            // arriving from the other direction.
            assertTrue(
                context.getBeanNamesForType(ScheduledExecutorService::class.java).isEmpty(),
                "a ScheduledExecutorService bean backs off Boot's taskScheduler; found " +
                    context.getBeanNamesForType(ScheduledExecutorService::class.java).toList(),
            )
            val schedulers = context.getBeanNamesForType(TaskScheduler::class.java)
            assertTrue(schedulers.isNotEmpty(), "Boot's taskScheduler backed off — @Scheduled has nowhere of its own to run")
            schedulers.forEach {
                assertNotSame(pool, context.getBean(it), "@Scheduled work would run on the order pool")
            }

            // applicationTaskExecutor is @ConditionalOnMissingBean(Executor.class). The pool being
            // an Executor is what keeps @Async resolution exactly as it was before the merge.
            assertFalse(
                context.containsBean("applicationTaskExecutor"),
                "an applicationTaskExecutor bean means the order pool stopped being the Executor " +
                    "bean Boot backs off for, which silently moves @Async work somewhere else",
            )
        }
    }

    @Test
    fun `the merged pool publishes the executor_ series the dashboards group by`() {
        runner.run { context ->
            val registry = context.getBean(MeterRegistry::class.java)

            // OrderWorkerConfig binds these BY HAND, because Boot's
            // TaskExecutorMetricsAutoConfiguration walks the TaskExecutor beans and instruments
            // only the ThreadPoolTaskExecutor and ThreadPoolTaskScheduler ones — OrderWorkerPool is
            // neither. Without the hand-rolled bind the series would not exist at all and the
            // dashboards' "Busy threads by pool" panel would be empty for this branch, which reads
            // as a run with no worker pool. Asserted against the real autoconfiguration so a Boot
            // change on either side is caught here.
            //
            // A duplicate registration is NOT what this guards: Micrometer dedupes by meter id, so
            // Boot also binding the same executor under the same name would be a no-op.
            assertNotNull(
                registry.find("executor.pool.size").tag("name", "orderWorkerExecutor").gauge(),
                "executor.pool.size{name=orderWorkerExecutor} missing — ExecutorServiceMetrics not bound",
            )
            assertNotNull(
                registry.find("executor.queued").tag("name", "orderWorkerExecutor").gauge(),
                "executor.queued{name=orderWorkerExecutor} missing",
            )

            // Not pool.size: a ScheduledThreadPoolExecutor starts its threads lazily, so it reads 0
            // until the first task — the same ramp ThreadPoolTaskExecutor has, and the reason
            // executor_pool_size_threads climbs during a run rather than starting flat. pool.core
            // is the configured width. (executor_pool_max_threads is Integer.MAX_VALUE on an STPE
            // and is not the width at all.)
            val core = registry.find("executor.pool.core").tag("name", "orderWorkerExecutor").gauge()
            assertNotNull(core, "executor.pool.core{name=orderWorkerExecutor} missing")
            assertEquals(3.0, core!!.value(), "app.order-worker.threads=3 for this test")
        }
    }

    /**
     * The `@Async` fallback the V8 cursor port must not disturb.
     *
     * `AsyncExecutionAspectSupport.getDefaultExecutor` asks for a UNIQUE `TaskExecutor` bean;
     * failing that, for one literally named `taskExecutor`; failing that, it falls back to
     * `SimpleAsyncTaskExecutor` — a new thread per invocation. So the fallback holds exactly when
     * two conditions do: more than one `TaskExecutor` in the context, and no bean under that name.
     *
     * Note what this test measures rather than what the surrounding comments have long claimed.
     * Boot's `taskScheduler` is a `ThreadPoolTaskScheduler`, which IS a `TaskExecutor`, so the
     * ambiguity exists on every TO branch with or without `outboxPollerExecutor` — this branch's
     * second delivery pool is not what causes it. `app.outbox-poller.threads` is asserted anyway
     * because the OTHER half of the guard is real: `outboxPollerExecutor` must stay a
     * `TaskExecutor` and stay SHARED between the drain and the sweep. Handing the sweep a pool of
     * its own would let the two processes together put twice the pool's width of deliveries in
     * flight, which is invisible in every log and every dashboard.
     */
    @Test
    fun `the @Async fallback conditions hold, and the poller pool stays a shared TaskExecutor`() {
        runner.withUserConfiguration(OutboxPollerConfig::class.java)
            .withPropertyValues("app.outbox-poller.threads=2")
            .run { context ->
                assertInstanceOf(
                    ThreadPoolTaskExecutor::class.java, context.getBean("outboxPollerExecutor"),
                    "the drain and the sweep share this bean; a plain ExecutorService would drop " +
                        "it out of the TaskExecutor set and change what @Async resolution sees",
                )

                val executors = context.getBeanNamesForType(TaskExecutor::class.java).toList()
                assertTrue(
                    executors.containsAll(listOf("orderWorkerExecutor", "outboxPollerExecutor")),
                    "both delivery-side pools must be TaskExecutors; found $executors",
                )
                assertTrue(
                    executors.size > 1,
                    "@Async falls back only while the by-type lookup is ambiguous; found $executors",
                )

                // The two names that end the fallback. `taskExecutor` is the tie-breaker Spring
                // reaches for after the by-type lookup fails; `applicationTaskExecutor` carries it
                // as an alias, so Boot creating one would resolve the ambiguity from either side.
                assertFalse(
                    context.containsBean("taskExecutor"),
                    "a bean named `taskExecutor` resolves the ambiguity and ends the fallback",
                )
                assertFalse(
                    context.containsBean("applicationTaskExecutor"),
                    "applicationTaskExecutor is aliased to `taskExecutor` and ends the fallback",
                )
            }
    }

    /**
     * The claim above, isolated: the ambiguity is Boot's scheduler, not the outbox poller. Without
     * this the previous test would read as evidence that TO-1's second pool is what moves `@Async`
     * off the order pool — which `variants.env` and several KDocs on this branch and TO-2 assert,
     * and which is not what the context actually contains.
     */
    @Test
    fun `the TaskExecutor ambiguity exists without the outbox poller at all`() {
        runner.run { context ->
            val executors = context.getBeanNamesForType(TaskExecutor::class.java).toList()
            assertEquals(
                setOf("orderWorkerExecutor", "taskScheduler"), executors.toSet(),
                "Boot's taskScheduler is a ThreadPoolTaskScheduler, hence already a TaskExecutor",
            )
        }
    }
}
