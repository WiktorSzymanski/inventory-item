package pl.szymanski.wiktor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.resilience.annotation.EnableResilientMethods
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
@EnableResilientMethods
class InventoryReservationApplication

fun main(args: Array<String>) {
    runApplication<InventoryReservationApplication>(*args)
}
