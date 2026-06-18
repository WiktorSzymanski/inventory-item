package pl.szymanski.wiktor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.resilience.annotation.EnableResilientMethods
import org.springframework.scheduling.annotation.EnableAsync
import java.time.Clock

@SpringBootApplication
@EnableAsync
@EnableResilientMethods
class InventoryReservationApplication {
    // Single shared clock for stamping event createdAt — matches Axon's GenericEventMessage.clock
    // (Clock.systemUTC()) so the publish-lag start point is identical across the TO and ES branches.
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<InventoryReservationApplication>(*args)
}
