package pl.szymanski.wiktor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import pl.szymanski.wiktor.config.OutboxProperties

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties::class)
class InventoryReservationApplication

fun main(args: Array<String>) {
    runApplication<InventoryReservationApplication>(*args)
}
