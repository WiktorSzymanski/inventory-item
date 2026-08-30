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
import pl.szymanski.wiktor.domain.OrderSaga
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.OrderSagaRepository
import java.time.Clock
import java.time.Instant
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
 * Admission phase (transaction A): persists the PENDING order, the saga that will drive it, and the
 * OrderCreatedEvent, atomically as one unit. This handler does no reservation work itself.
 *
 * **The saga row is created HERE rather than when OrderCreatedEvent is delivered**, and that is a
 * deliberate simplification of the failure surface. Creating it in the listener would make "the
 * order exists but its saga does not" a reachable state that every step handler would then have to
 * tell apart from "this order was never admitted" — and it would add a transaction per order to do
 * it. Committing them together means a saga exists for exactly the orders that exist, from the
 * instant either of them does.
 *
 * `startedAt` is the admission instant captured by `InventoryService.acceptOrder` BEFORE this
 * transaction opens, not `clock.instant()` from inside it. That is what keeps `order_e2e_time`
 * comparable with TO-3, where the same span starts at a `System.nanoTime()` taken before the accept
 * call; stamping it in here would silently exclude the accept transaction's own duration from every
 * end-to-end measurement on this branch alone.
 */
@Service
class CreateOrderCommandHandler(
    private val orderRepo: OrderRepository,
    private val sagaRepo: OrderSagaRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")
    private val orderCreatedCounter: Counter = meterRegistry.counter("order.create.success")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(orderId: String, command: CreateOrderCommand, acceptedAt: Instant) {
        log.info("[ORDER] admitting orderId={} userId={} itemCount={} correlationId={}", orderId, command.userId, command.items.size, command.correlationId)

        val lines = command.items.map { ReservedItem(it.itemId, it.quantity) }
        val (order, event) = Order.create(
            orderId = orderId,
            userId = command.userId,
            items = lines,
            correlationId = command.correlationId,
            clock = clock,
            additionalBytesSize = command.additionalBytesSize,
        )
        orderRepo.save(order)

        // Saved AFTER the order, because order_saga.order_id is a foreign key onto it. The line
        // list stored here is the ORDERED one — `orders.items` is a JSONB map and cannot be used to
        // recover step positions. See SagaLines.
        sagaRepo.save(
            OrderSaga.start(
                orderId = orderId,
                items = lines,
                correlationId = command.correlationId,
                startedAt = acceptedAt,
            )
        )

        // publishEvent inserts into event_publication synchronously inside this transaction, so the
        // PENDING order and its OrderCreatedEvent commit as one atomic unit (the outbox guarantee).
        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(event)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

        orderCreatedCounter.increment()
        log.info("[ORDER] admitted orderId={} correlationId={}", orderId, command.correlationId)
    }
}
