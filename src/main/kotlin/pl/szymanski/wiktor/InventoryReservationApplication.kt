package pl.szymanski.wiktor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
class InventoryReservationApplication

fun main(args: Array<String>) {
    runApplication<InventoryReservationApplication>(*args)
}
