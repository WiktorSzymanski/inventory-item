package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.gateway.DefaultCommandGateway
import org.axonframework.commandhandling.gateway.RetryScheduler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@Configuration
class CommandGatewayConfig {

    @Bean
    fun retryScheduler(meterRegistry: MeterRegistry): RetryScheduler = ConcurrencyRetryScheduler(
        retryExecutor = Executors.newScheduledThreadPool(4),
        maxRetries = 5,
        initialDelayMs = 25L,
        meterRegistry = meterRegistry,
    )

    @Bean
    fun commandGateway(commandBus: CommandBus, retryScheduler: RetryScheduler): DefaultCommandGateway =
        DefaultCommandGateway.builder()
            .commandBus(commandBus)
            .retryScheduler(retryScheduler)
            .build()

    @Bean
    @Qualifier("sagaCommandExecutor")
    fun sagaCommandExecutor(): Executor = Executors.newFixedThreadPool(64)
}
