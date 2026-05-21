package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.resilience.annotation.EnableResilientMethods
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.ReserveInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.ReserveItemCommand

@SpringJUnitConfig(classes = [InventoryServiceRetryTest.RetryTestConfig::class])
class InventoryServiceRetryTest {
    @Autowired
    private lateinit var inventoryService: InventoryService

    @Autowired
    private lateinit var reserveInventoryItemCommandHandler: ReserveInventoryItemCommandHandler

    @Test
    fun `reserve item retries optimistic locking failures`() {
        val command = ReserveItemCommand("ITEM-001", "RES-1", 1)

        every { reserveInventoryItemCommandHandler.handle(command) } throws
            OptimisticLockingFailureException("conflict") andThen "RES-1"

        inventoryService.reserveItem(command)

        verify(exactly = 2) { reserveInventoryItemCommandHandler.handle(command) }
    }

    @Test
    fun `reserve item throws exhausted exception after retry attempts are exhausted`() {
        val command = ReserveItemCommand("ITEM-001", "RES-1", 1)

        every { reserveInventoryItemCommandHandler.handle(command) } throws
            OptimisticLockingFailureException("conflict")

        try {
            inventoryService.reserveItem(command)
            fail("Expected OptimisticLockingFailureException")
        } catch (_: OptimisticLockingFailureException) {
        }

        verify(exactly = 5) { reserveInventoryItemCommandHandler.handle(command) }
    }

    @Configuration
    @EnableResilientMethods
    @Import(InventoryService::class)
    class RetryTestConfig {
        @Bean
        fun inventoryRepository(): InventoryRepository = mockk()

        @Bean
        fun createInventoryItemCommandHandler(): CreateInventoryItemCommandHandler = mockk()

        @Bean
        fun reserveInventoryItemCommandHandler(): ReserveInventoryItemCommandHandler = mockk()

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
