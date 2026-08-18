package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderItems
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.repository.InventoryBatchWriter
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Optional
import java.util.UUID
import javax.sql.DataSource

/**
 * THE test for the split reserve path. The whole shape is one claim — that loading items and
 * running the domain logic happen with no transaction open, and that the transaction holds nothing
 * but writes — and every performance number this branch produces is meaningless if that claim is
 * false.
 *
 * It is also a claim no unit test with mocked collaborators can check, because "is a transaction
 * open" is a property of [TransactionSynchronizationManager], not of the collaborators. So this
 * runs a real Spring context with real `@Transactional` proxying and a real
 * [DataSourceTransactionManager]; only the JDBC underneath it is mocked. Each collaborator then
 * records what the synchronization manager said at the moment it was called.
 *
 * The failure mode it exists to catch is quiet: put `@Transactional` back on
 * [ReserveOrderItemsCommandHandler.handle], or fold [OrderWriteCommandHandler] into a self-called
 * private method that bypasses the proxy, and every other test in this repo still passes.
 */
@SpringJUnitConfig(classes = [ReserveOrderItemsTransactionBoundaryTest.TestConfig::class])
class ReserveOrderItemsTransactionBoundaryTest {

    @Autowired
    private lateinit var handler: ReserveOrderItemsCommandHandler

    @Autowired
    private lateinit var probe: TransactionProbe

    /**
     * Where each collaborator was called from: inside a transaction, or outside one. Recorded as
     * the call happens, by the mocks in [TestConfig].
     */
    class TransactionProbe {
        var txDuringItemLoad: Boolean? = null
        var txDuringDomainLogic: Boolean? = null
        var txDuringEventPublish: Boolean? = null
        var txDuringReservationInsert: Boolean? = null
        var txDuringOrderSave: Boolean? = null
        var txDuringItemUpdate: Boolean? = null

        /** The Spring context is cached across methods, so the probe has to be cleared per test. */
        fun reset() {
            txDuringItemLoad = null
            txDuringDomainLogic = null
            txDuringEventPublish = null
            txDuringReservationInsert = null
            txDuringOrderSave = null
            txDuringItemUpdate = null
        }
    }

    @BeforeEach
    fun clearProbe() = probe.reset()

    @Configuration
    @EnableTransactionManagement
    class TestConfig {
        val probe = TransactionProbe()

        @Bean fun transactionProbe(): TransactionProbe = probe

        @Bean fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        /**
         * `InventoryItem.reserve` stamps its event from this clock, straight after the
         * `reserveDelayMs` sleep — so the first `instant()` of an order is a probe planted in the
         * middle of the modify phase, which is the one phase no mock can otherwise observe.
         */
        @Bean
        fun clock(): Clock = object : Clock() {
            private val delegate = systemUTC()
            override fun getZone(): ZoneId = delegate.zone
            override fun withZone(zone: ZoneId): Clock = delegate.withZone(zone)
            override fun instant(): Instant {
                if (probe.txDuringDomainLogic == null) {
                    probe.txDuringDomainLogic = TransactionSynchronizationManager.isActualTransactionActive()
                }
                return delegate.instant()
            }
        }

        /**
         * A real transaction manager is the point — it is what binds and unbinds
         * [TransactionSynchronizationManager].
         */
        @Bean
        fun transactionManager(): PlatformTransactionManager {
            val connection = mockk<Connection>(relaxed = true)
            val dataSource = mockk<DataSource>(relaxed = true)
            every { dataSource.connection } returns connection
            return DataSourceTransactionManager(dataSource)
        }

        @Bean
        fun inventoryRepository(): InventoryRepository {
            val repo = mockk<InventoryRepository>(relaxed = true)
            val ids = slot<Iterable<String>>()
            every { repo.findAllById(capture(ids)) } answers {
                probe.txDuringItemLoad = TransactionSynchronizationManager.isActualTransactionActive()
                // reserveDelayMs > 0 puts a real sleep on the modify phase, so txDuringDomainLogic
                // records a span of time rather than an instant that happens to land outside.
                ids.captured.map { InventoryItem(id = it, availableQty = 100, reserveDelayMs = 20, version = 7L) }
            }
            return repo
        }

        @Bean
        fun orderRepository(): OrderRepository {
            val repo = mockk<OrderRepository>(relaxed = true)
            every { repo.findById(any()) } answers {
                Optional.of(
                    Order(
                        orderId = firstArg(),
                        userId = "USER-1",
                        items = OrderItems(listOf(ReservedItem("ITEM-A", 1))),
                    )
                )
            }
            every { repo.save(any<Order>()) } answers {
                probe.txDuringOrderSave = TransactionSynchronizationManager.isActualTransactionActive()
                firstArg()
            }
            return repo
        }

        @Bean
        fun inventoryBatchWriter(): InventoryBatchWriter {
            val writer = mockk<InventoryBatchWriter>(relaxed = true)
            every { writer.updateAll(any()) } answers {
                probe.txDuringItemUpdate = TransactionSynchronizationManager.isActualTransactionActive()
                firstArg<List<InventoryItem>>()
            }
            every { writer.insertAll(any()) } answers {
                probe.txDuringReservationInsert = TransactionSynchronizationManager.isActualTransactionActive()
            }
            return writer
        }

        @Bean
        fun applicationEventPublisher(): ApplicationEventPublisher {
            val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
            every { publisher.publishEvent(any<Any>()) } answers {
                probe.txDuringEventPublish = TransactionSynchronizationManager.isActualTransactionActive()
            }
            return publisher
        }

        @Bean
        fun orderWriteCommandHandler(
            inventoryBatchWriter: InventoryBatchWriter,
            orderRepository: OrderRepository,
            applicationEventPublisher: ApplicationEventPublisher,
            meterRegistry: MeterRegistry,
        ) = OrderWriteCommandHandler(inventoryBatchWriter, orderRepository, applicationEventPublisher, meterRegistry)

        @Bean
        fun reserveOrderItemsCommandHandler(
            inventoryRepository: InventoryRepository,
            orderRepository: OrderRepository,
            orderWriteCommandHandler: OrderWriteCommandHandler,
            clock: Clock,
            meterRegistry: MeterRegistry,
        ) = ReserveOrderItemsCommandHandler(
            inventoryRepository, orderRepository, orderWriteCommandHandler, clock, meterRegistry,
        )
    }

    private fun orderCreated(vararg itemIds: String) = OrderCreatedEvent(
        orderId = "ORDER-1",
        userId = "USER-1",
        items = itemIds.map { ReservedItem(it, 1) },
        correlationId = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `items are read, and the domain logic runs, with no transaction open`() {
        handler.handle(orderCreated("ITEM-C", "ITEM-A", "ITEM-B"))

        assertEquals(false, probe.txDuringItemLoad, "the item load must not be inside a transaction")
        assertEquals(
            false, probe.txDuringDomainLogic,
            "InventoryItem.reserve — and therefore the reserveDelayMs sleep — must not be inside a transaction",
        )
    }

    @Test
    fun `every write happens inside a transaction`() {
        handler.handle(orderCreated("ITEM-A", "ITEM-B"))

        assertEquals(true, probe.txDuringEventPublish, "outbox writes must be inside the transaction")
        assertEquals(true, probe.txDuringReservationInsert, "reservation inserts must be inside the transaction")
        assertEquals(true, probe.txDuringOrderSave, "the order save must be inside the transaction")
        assertEquals(true, probe.txDuringItemUpdate, "the inventory update must be inside the transaction")
    }

    @Test
    fun `handle leaves no transaction bound to the calling thread`() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        handler.handle(orderCreated("ITEM-A"))
        assertFalse(
            TransactionSynchronizationManager.isActualTransactionActive(),
            "the write transaction must be committed and unbound by the time handle returns",
        )
    }
}
