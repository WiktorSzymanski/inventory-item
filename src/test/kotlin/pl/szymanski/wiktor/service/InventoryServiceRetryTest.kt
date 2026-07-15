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
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailOrderCommandHandler
import pl.szymanski.wiktor.service.command.ReserveOrderItemsCommandHandler
import java.time.Instant
import java.util.UUID

@SpringJUnitConfig(classes = [InventoryServiceRetryTest.RetryTestConfig::class])
class InventoryServiceRetryTest {
    @Autowired
    private lateinit var inventoryService: InventoryService

    @Autowired
    private lateinit var reserveOrderItemsCommandHandler: ReserveOrderItemsCommandHandler

    private fun orderCreatedEvent() = OrderCreatedEvent(
        orderId = "ORDER-1",
        userId = "USER-1",
        items = listOf(ReservedItem("ITEM-001", 1)),
        correlationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `order processing retries optimistic locking failures`() {
        val event = orderCreatedEvent()

        every { reserveOrderItemsCommandHandler.handle(event) } throws
            OptimisticLockingFailureException("conflict") andThen Unit

        inventoryService.processOrder(event)

        verify(exactly = 2) { reserveOrderItemsCommandHandler.handle(event) }
    }

    @Test
    fun `order processing throws exhausted exception after retry attempts are exhausted`() {
        val event = orderCreatedEvent()

        every { reserveOrderItemsCommandHandler.handle(event) } throws
            OptimisticLockingFailureException("conflict")

        try {
            inventoryService.processOrder(event)
            fail("Expected OptimisticLockingFailureException")
        } catch (_: OptimisticLockingFailureException) {
        }

        verify(exactly = 5) { reserveOrderItemsCommandHandler.handle(event) }
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
        fun createOrderCommandHandler(): CreateOrderCommandHandler = mockk()

        @Bean
        fun reserveOrderItemsCommandHandler(): ReserveOrderItemsCommandHandler = mockk()

        @Bean
        fun failOrderCommandHandler(): FailOrderCommandHandler = mockk(relaxed = true)

        @Bean
        fun orderWorkerExecutor(): TaskExecutor = SyncTaskExecutor()

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
