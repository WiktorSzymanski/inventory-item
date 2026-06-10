package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderReservationCreatedEvent
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.ReservationRepository
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
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val dbFetchTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .register(meterRegistry)
    private val appendSuccessCounter: Counter = meterRegistry.counter("inventory.append.success")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(command: CreateOrderReservationCommand): String {
        val orderId = UUID.randomUUID().toString()
        log.info("[ORDER] orderId={} userId={} itemCount={} correlationId={}", orderId, command.userId, command.items.size, command.correlationId)

        // Sort by itemId so all concurrent transactions acquire row locks in the same order,
        // preventing circular-wait deadlocks.
        val sortedItems = command.items.sortedBy { it.itemId }

        val dbStartNs = System.nanoTime()
        val foundItems = inventoryRepo.findAllById(sortedItems.map { it.itemId }).associateBy { it.id }
        dbFetchTimer.record(System.nanoTime() - dbStartNs, TimeUnit.NANOSECONDS)

        sortedItems.forEach { orderItem ->
            if (!foundItems.containsKey(orderItem.itemId))
                throw NotFoundException("Item ${orderItem.itemId} not found")
        }

        val results = sortedItems.map { orderItem ->
            foundItems[orderItem.itemId]!!.reserve(orderId, orderItem.quantity, command.correlationId)
        }

        orderRepo.save(Order(orderId = orderId, userId = command.userId))
        inventoryRepo.saveAll(results.map { it.updatedItem })
        reservationRepo.saveAll(results.map { it.reservation })

        applicationEventPublisher.publishEvent(
            OrderReservationCreatedEvent(
                orderId = orderId,
                userId = command.userId,
                items = command.items.map { ReservedItem(it.itemId, it.quantity) },
                correlationId = command.correlationId,
            )
        )

        appendSuccessCounter.increment()
        log.info("[ORDER] success orderId={} correlationId={}", orderId, command.correlationId)
        return orderId
    }
}
