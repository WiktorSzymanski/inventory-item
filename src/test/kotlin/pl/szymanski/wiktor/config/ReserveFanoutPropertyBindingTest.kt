package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * That the shipped `application.yaml` actually reaches [ReserveFanoutProperties].
 *
 * [ReserveFanoutIsNotAnExecutorTest] sets the width with `withPropertyValues`, which proves the
 * class binds but says nothing about the KEY. A typo in the yaml — `reserve_fanout`, `fanout`, a
 * `threads` nested one level too deep — binds nothing, raises nothing, and leaves the pool on
 * [ReserveFanoutProperties]'s deliberately small test placeholder. The branch would then run its
 * headline feature four threads wide and the only evidence would be a bench number that failed to
 * move. Pinning the real file is the difference between "the property class works" and "the
 * property is set".
 *
 * The two values asserted are the ones docker-compose also defaults to, so a drift between the two
 * files shows up here as well as in `scripts/tests/test_compose_files.py`.
 */
class ReserveFanoutPropertyBindingTest {

    @Configuration(proxyBeanMethods = false)
    class TestBeans {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Test
    fun `the shipped yaml gives the fan-out pool 150 threads and a 1000-deep queue`() {
        ApplicationContextRunner()
            .withUserConfiguration(ReserveFanoutConfig::class.java, TestBeans::class.java)
            // The real file, loaded through Boot's own config-data machinery so it is resolved
            // exactly as the application resolves it — including the `${RESERVE_FANOUT_THREADS:150}`
            // fallback, since the variable is unset here. TestPropertySourceUtils is NOT an
            // alternative: it reads .properties and quietly loads nothing from a .yaml, which makes
            // this test pass or fail for a reason that has nothing to do with the branch.
            .withInitializer(ConfigDataApplicationContextInitializer())
            .run { context ->
                val props = context.getBean(ReserveFanoutProperties::class.java)
                assertEquals(150, props.threads, "app.reserve-fanout.threads did not bind from application.yaml")
                assertEquals(
                    1000, props.queueCapacity,
                    "app.reserve-fanout.queue-capacity did not bind — relaxed binding to queueCapacity is what makes the kebab-case key work",
                )
            }
    }
}
