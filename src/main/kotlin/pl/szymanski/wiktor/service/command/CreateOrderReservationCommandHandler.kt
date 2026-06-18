package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.OrderReservationCreatedEvent
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.ReservationRepository
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OrderItem(val itemId: String, val quantity: Int)

data class CreateOrderReservationCommand(
    val userId: String,
    val items: List<OrderItem>,
    val correlationId: UUID = UUID.randomUUID(),
)

@Service
class CreateOrderReservationCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val reservationRepo: ReservationRepository,
    private val orderRepo: OrderRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val dbFetchTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .register(meterRegistry)
    private val dbWriteTimer: Timer = Timer.builder("state_persist_time")
        .tag("source", "db_write")
        .register(meterRegistry)
    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")
    private val appendSuccessCounter: Counter = meterRegistry.counter("inventory.append.success")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(orderId: String, command: CreateOrderReservationCommand) {
        log.info("[ORDER] processing orderId={} userId={} itemCount={} correlationId={}", orderId, command.userId, command.items.size, command.correlationId)

        val dbStartNs = System.nanoTime()
        val foundItems = inventoryRepo.findAllById(command.items.map { it.itemId }).associateBy { it.id }
        dbFetchTimer.record(System.nanoTime() - dbStartNs, TimeUnit.NANOSECONDS)

        val results = command.items.map { orderItem ->
            val item = foundItems[orderItem.itemId]
                ?: throw NotFoundException("Item ${orderItem.itemId} not found")
            item.reserve(orderId, orderItem.quantity, command.correlationId, clock)
        }

        // Stamp the order event at its production point, alongside the per-item events and before
        // the DB writes, so createdAt reflects event creation (mirrors Axon's apply()-time @Timestamp).
        val orderEvent = OrderReservationCreatedEvent(
            orderId = orderId,
            userId = command.userId,
            items = command.items.map { ReservedItem(it.itemId, it.quantity) },
            correlationId = command.correlationId,
            createdAt = clock.instant(),
        )

        val dbWriteStartNs = System.nanoTime()
        inventoryRepo.saveAll(results.map { it.updatedItem })
        reservationRepo.saveAll(results.map { it.reservation })
        orderRepo.updateStatus(orderId, OrderStatus.CONFIRMED, null)
        dbWriteTimer.record(System.nanoTime() - dbWriteStartNs, TimeUnit.NANOSECONDS)

        // publishEvent inserts into event_publication synchronously inside this transaction,
        // so this timer captures the outbox write overhead of the TO pattern.
        val outboxStartNs = System.nanoTime()
        results.forEach { applicationEventPublisher.publishEvent(it.event) }
        applicationEventPublisher.publishEvent(orderEvent)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

        appendSuccessCounter.increment()
        log.info("[ORDER] confirmed orderId={} correlationId={}", orderId, command.correlationId)
    }
}
