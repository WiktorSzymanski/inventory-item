package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import pl.szymanski.wiktor.service.ReserveFanoutPool
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService

/**
 * That adding the fan-out pool changes NOTHING about how Spring resolves executors.
 *
 * This is the failure mode the branch is most exposed to and the one that would never announce
 * itself. `@Async` (`InventoryService.onOrderCreated`, the listener that marks publications
 * complete) and `@Scheduled` (the outbox republisher, OutboxMetrics) both pick their executor by
 * TYPE. Introducing a further bean that answers to `Executor`, `TaskExecutor` or
 * `ScheduledExecutorService` can silently move one of them somewhere else — no boot failure, no
 * log, just a bench run whose delivery numbers no longer mean what TO-3's mean.
 *
 * [OrderWorkerPoolAutoConfigurationTest] guards the same class of failure for the order pool, but
 * its assertions cannot catch this one: `applicationTaskExecutor` is
 * `@ConditionalOnMissingBean(Executor.class)` and stays backed off whether the context holds two
 * Executors or three.
 *
 * So the assertion here is DIFFERENTIAL rather than absolute — the same context is stood up with
 * and without [ReserveFanoutConfig] and the by-type lookups are required to be identical. That is
 * deliberate, and not merely convenient: measured, `TaskExecutor` already resolves to TWO beans
 * here (`orderWorkerExecutor` and Boot's `taskScheduler`, an `AsyncTaskExecutor`), so an absolute
 * assertion of the form "the order pool is the only one" would be false on TO-3 as well and would
 * pin a state this branch never had. What the branch owes its baseline is that it changed nothing,
 * which is precisely what a differential test says.
 */
class ReserveFanoutIsNotAnExecutorTest {

    private val base = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                TaskExecutionAutoConfiguration::class.java,
                TaskSchedulingAutoConfiguration::class.java,
                TaskExecutorMetricsAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("app.order-worker.threads=3", "app.reserve-fanout.threads=7")

    private val withoutFanout = base.withUserConfiguration(
        OrderWorkerConfig::class.java, TestBeans::class.java,
    )

    private val withFanout = base.withUserConfiguration(
        OrderWorkerConfig::class.java, ReserveFanoutConfig::class.java, TestBeans::class.java,
    )

    /** `@EnableScheduling` for the same reason [OrderWorkerPoolAutoConfigurationTest] needs it. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class TestBeans {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Test
    fun `the fan-out pool answers to no executor type`() {
        withFanout.run { context ->
            val pool = context.getBean("reserveFanoutExecutor")
            assertTrue(pool is ReserveFanoutPool)

            // Each of these would hand the bean to a different piece of Spring's autoconfiguration.
            assertFalse(pool is Executor, "an Executor bean is a candidate for @Async resolution")
            assertFalse(pool is TaskExecutor, "a TaskExecutor bean is a candidate for @Async resolution")
            assertFalse(
                pool is ScheduledExecutorService,
                "a ScheduledExecutorService bean backs off Boot's taskScheduler and migrates @Scheduled work",
            )
            assertFalse(pool is TaskScheduler, "a TaskScheduler bean backs off Boot's own")
        }
    }

    @Test
    fun `adding the fan-out pool leaves executor resolution byte-identical`() {
        fun namesFor(runner: ApplicationContextRunner): Map<String, List<String>> {
            lateinit var seen: Map<String, List<String>>
            runner.run { context ->
                seen = mapOf(
                    "Executor" to context.getBeanNamesForType(Executor::class.java).sorted(),
                    "TaskExecutor" to context.getBeanNamesForType(TaskExecutor::class.java).sorted(),
                    "TaskScheduler" to context.getBeanNamesForType(TaskScheduler::class.java).sorted(),
                    "ScheduledExecutorService" to
                        context.getBeanNamesForType(ScheduledExecutorService::class.java).sorted(),
                )
            }
            return seen
        }

        assertEquals(
            namesFor(withoutFanout),
            namesFor(withFanout),
            "the fan-out pool changed an executor by-type lookup — @Async or @Scheduled has moved",
        )
    }

    @Test
    fun `the width is a knob, and it reaches the dashboards`() {
        withFanout.run { context ->
            val registry = context.getBean(MeterRegistry::class.java)

            // Hand-bound in ReserveFanoutConfig; without it the "Busy threads by pool" panel would
            // simply have no series for this pool, which reads as a run that never fanned out.
            val core = registry.find("executor.pool.core").tag("name", "reserveFanoutExecutor").gauge()
            assertNotNull(core, "executor.pool.core{name=reserveFanoutExecutor} missing — metrics not bound")
            assertEquals(7.0, core!!.value(), "app.reserve-fanout.threads=7 must bind")

            // Meaningful here where it is not on the order pool: this is a plain ThreadPoolExecutor,
            // so max is the configured width rather than Integer.MAX_VALUE.
            val max = registry.find("executor.pool.max").tag("name", "reserveFanoutExecutor").gauge()
            assertNotNull(max, "executor.pool.max{name=reserveFanoutExecutor} missing")
            assertEquals(7.0, max!!.value())

            assertNotNull(
                registry.find("executor.queued").tag("name", "reserveFanoutExecutor").gauge(),
                "executor.queued{name=reserveFanoutExecutor} missing — queue depth is how overflow is seen",
            )
        }
    }
}
