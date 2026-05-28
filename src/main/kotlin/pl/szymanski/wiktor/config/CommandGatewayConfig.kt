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

    // Saga processor threads submit reservation commands here instead of blocking inline.
    // Capped at 32 so total Axon-pool demand (32 TEP + 32 here + 3 other TEPs + 4 retry + 15 HTTP ≈ 86)
    // stays within the pool ceiling of 100.
    @Bean
    @Qualifier("sagaCommandExecutor")
    fun sagaCommandExecutor(): Executor = Executors.newFixedThreadPool(32)
}
