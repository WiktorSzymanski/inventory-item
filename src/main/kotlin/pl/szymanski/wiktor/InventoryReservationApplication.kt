package pl.szymanski.wiktor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableAsync
import java.time.Clock

// TO-3-mod: no @EnableResilientMethods. The only @Retryable on this branch was
// InventoryService.processOrder, and its retry is now an explicit non-blocking loop, so the
// interceptor infrastructure has nothing left to advise.
@SpringBootApplication
@EnableAsync
class InventoryReservationApplication {
    // Single shared clock for stamping event createdAt — matches Axon's GenericEventMessage.clock
    // (Clock.systemUTC()) so the publish-lag start point is identical across the TO and ES branches.
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<InventoryReservationApplication>(*args)
}
