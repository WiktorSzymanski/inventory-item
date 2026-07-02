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
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.repository.OrderRepository
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OrderItem(val itemId: String, val quantity: Int)

data class CreateOrderCommand(
    val userId: String,
    val items: List<OrderItem>,
    val correlationId: UUID = UUID.randomUUID(),
    val additionalBytesSize: Int = 0,
)

/**
 * Admission phase (transaction A): persists the PENDING order and, atomically alongside it,
 * records the OrderCreatedEvent to the outbox. The event is what triggers the reservation phase
 * (a separate transaction on the worker pool); this handler does no reservation work itself.
 */
@Service
class CreateOrderCommandHandler(
    private val orderRepo: OrderRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")
    private val orderCreatedCounter: Counter = meterRegistry.counter("order.create.success")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(orderId: String, command: CreateOrderCommand) {
        log.info("[ORDER] admitting orderId={} userId={} itemCount={} correlationId={}", orderId, command.userId, command.items.size, command.correlationId)

        val (order, event) = Order.create(
            orderId = orderId,
            userId = command.userId,
            items = command.items.map { ReservedItem(it.itemId, it.quantity) },
            correlationId = command.correlationId,
            clock = clock,
            additionalBytesSize = command.additionalBytesSize,
        )
        orderRepo.save(order)

        // publishEvent inserts into event_publication synchronously inside this transaction, so the
        // PENDING order and its OrderCreatedEvent commit as one atomic unit (the outbox guarantee).
        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(event)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

        orderCreatedCounter.increment()
        log.info("[ORDER] admitted orderId={} correlationId={}", orderId, command.correlationId)
    }
}
