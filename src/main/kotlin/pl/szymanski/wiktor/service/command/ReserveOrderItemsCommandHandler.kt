package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderReservationCreatedEvent
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.OrderRepository
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Reservation phase (transaction B), triggered by consuming OrderCreatedEvent. Reserves every
 * requested line by dispatching one ReserveItemCommand per item — each joins this transaction
 * (Propagation.REQUIRED) so the whole order is reserved atomically — then confirms the order and
 * records the summarising OrderReservationCreatedEvent to the outbox. If any item fails, the
 * transaction rolls back in its entirety (state, reservations, and events alike).
 */
@Service
class ReserveOrderItemsCommandHandler(
    private val reserveItemCommandHandler: ReserveItemCommandHandler,
    private val orderRepo: OrderRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")
    private val appendSuccessCounter: Counter = meterRegistry.counter("inventory.append.success")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(event: OrderCreatedEvent) {
        val orderId = event.orderId
        log.info("[ORDER] processing orderId={} userId={} itemCount={} correlationId={}", orderId, event.userId, event.items.size, event.correlationId)

        // Idempotency guard: OrderCreatedEvent may be re-delivered by the backup poller after a
        // crash. A previously confirmed/rejected order is skipped; a still-PENDING order (including
        // one whose prior attempt rolled back and is being retried) proceeds.
        val order = orderRepo.findById(orderId)
            .orElseThrow { NotFoundException("Order $orderId not found") }
        if (order.status != OrderStatus.PENDING) {
            log.info("[ORDER] skipping orderId={} already status={} correlationId={}", orderId, order.status, event.correlationId)
            return
        }

        event.items.forEach { line ->
            reserveItemCommandHandler.handle(
                ReserveItemCommand(orderId, line.itemId, line.quantity, event.correlationId)
            )
        }

        orderRepo.updateStatus(orderId, OrderStatus.CONFIRMED, null)

        // Stamp the summary event at its production point (mirrors Axon's apply()-time @Timestamp).
        val orderEvent = OrderReservationCreatedEvent(
            orderId = orderId,
            userId = event.userId,
            items = event.items,
            correlationId = event.correlationId,
            createdAt = clock.instant(),
        )
        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(orderEvent)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

        appendSuccessCounter.increment()
        log.info("[ORDER] confirmed orderId={} correlationId={}", orderId, event.correlationId)
    }
}
