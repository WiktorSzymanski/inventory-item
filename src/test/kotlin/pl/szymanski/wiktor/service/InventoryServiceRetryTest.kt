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
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.resilience.annotation.EnableResilientMethods
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateOrderReservationCommand
import pl.szymanski.wiktor.service.command.CreateOrderReservationCommandHandler
import pl.szymanski.wiktor.service.command.OrderItem

@SpringJUnitConfig(classes = [InventoryServiceRetryTest.RetryTestConfig::class])
class InventoryServiceRetryTest {
    @Autowired
    private lateinit var inventoryService: InventoryService

    @Autowired
    private lateinit var createOrderReservationCommandHandler: CreateOrderReservationCommandHandler

    @Test
    fun `order processing retries optimistic locking failures`() {
        val command = CreateOrderReservationCommand("USER-1", listOf(OrderItem("ITEM-001", 1)))

        every { createOrderReservationCommandHandler.handle("ORDER-1", command) } throws
            OptimisticLockingFailureException("conflict") andThen Unit

        inventoryService.processOrder("ORDER-1", command)

        verify(exactly = 2) { createOrderReservationCommandHandler.handle("ORDER-1", command) }
    }

    @Test
    fun `order processing throws exhausted exception after retry attempts are exhausted`() {
        val command = CreateOrderReservationCommand("USER-1", listOf(OrderItem("ITEM-001", 1)))

        every { createOrderReservationCommandHandler.handle("ORDER-1", command) } throws
            OptimisticLockingFailureException("conflict")

        try {
            inventoryService.processOrder("ORDER-1", command)
            fail("Expected OptimisticLockingFailureException")
        } catch (_: OptimisticLockingFailureException) {
        }

        verify(exactly = 5) { createOrderReservationCommandHandler.handle("ORDER-1", command) }
    }

    @Configuration
    @EnableResilientMethods
    @Import(InventoryService::class)
    class RetryTestConfig {
        @Bean
        fun inventoryRepository(): InventoryRepository = mockk()

        @Bean
        fun orderRepository(): OrderRepository = mockk()

        @Bean
        fun createInventoryItemCommandHandler(): CreateInventoryItemCommandHandler = mockk()

        @Bean
        fun createOrderReservationCommandHandler(): CreateOrderReservationCommandHandler = mockk()

        @Bean
        fun orderWorkerExecutor(): TaskExecutor = SyncTaskExecutor()

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
