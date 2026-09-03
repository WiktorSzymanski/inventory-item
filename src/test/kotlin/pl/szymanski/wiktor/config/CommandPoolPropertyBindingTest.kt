package pl.szymanski.wiktor.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource

/**
 * The COMMAND_POOL knob has one silent failure mode that no other test here reaches: the yaml key
 * and the Kotlin property name are joined only by Spring's relaxed binding, so a rename or a typo
 * on either side leaves the run using the shipped 112 while every artefact — the [POOLS] log line,
 * meta.json's `command_pool`, the chart built from them — reports the value that was exported. The
 * run looks like the experiment it was not.
 *
 * Two halves, and both have to hold:
 *   1. `axon.saga.command-pool-size` binds to [SagaProcessorProperties.commandPoolSize];
 *   2. application.yaml actually spells that key and reads `COMMAND_POOL` for it.
 * Asserting (1) alone would pass against a yaml that never mentions the property.
 */
class CommandPoolPropertyBindingTest {

    @Configuration
    @EnableConfigurationProperties(SagaProcessorProperties::class)
    private class PropsOnly

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(PropsOnly::class.java)

    @Test
    fun `the yaml key binds to the property the executor reads`() {
        runner.withPropertyValues("axon.saga.command-pool-size=7").run { ctx ->
            assertEquals(7, ctx.getBean(SagaProcessorProperties::class.java).commandPoolSize)
        }
    }

    @Test
    fun `an unset knob leaves the shipped default`() {
        runner.run { ctx ->
            assertEquals(
                CommandGatewayConfig.DEFAULT_COMMAND_POOL_SIZE,
                ctx.getBean(SagaProcessorProperties::class.java).commandPoolSize,
            )
        }
    }

    @Test
    fun `application yaml wires that key to COMMAND_POOL`() {
        val yaml = ClassPathResource("application.yaml").inputStream
            .bufferedReader().use { it.readText() }
        val line = yaml.lineSequence().firstOrNull { it.trimStart().startsWith("command-pool-size:") }

        assertEquals(
            "command-pool-size: \${COMMAND_POOL:${CommandGatewayConfig.DEFAULT_COMMAND_POOL_SIZE}}",
            line?.trim(),
            "the property must be wired to COMMAND_POOL with the shipped default; " +
                "without this line the env var reaches nothing and the pool silently stays at " +
                "${CommandGatewayConfig.DEFAULT_COMMAND_POOL_SIZE}",
        )
    }
}
