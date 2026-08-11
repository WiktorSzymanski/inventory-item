package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.OrderRepository

/**
 * Reservation phase (transaction B), triggered by consuming OrderCreatedEvent. Reserves every
 * requested line by dispatching one ReserveItemCommand per item — each joins this transaction
 * (Propagation.REQUIRED) so the whole order is reserved atomically — then confirms the order via
 * CompleteOrderCommand, which transitions the aggregate to CONFIRMED and records the
 * OrderCompletedEvent to the outbox within this same transaction. If any item fails, the
 * transaction rolls back in its entirety (state, reservations, and events alike).
 */
@Service
class ReserveOrderItemsCommandHandler(
    private val reserveItemCommandHandler: ReserveItemCommandHandler,
    private val completeOrderCommandHandler: CompleteOrderCommandHandler,
    private val orderRepo: OrderRepository,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

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

        // Lines are reserved in itemId order, NOT in the order the client sent them. On this
        // branch each line's load is a SELECT … FOR UPDATE, so this loop IS the lock acquisition
        // order, and Postgres holds every row lock until this transaction commits. With all lines
        // in one transaction, two orders sharing items in opposite order would deadlock (tx A
        // holds item-3 and wants item-5 while tx B holds item-5 and wants item-3). A global lock
        // order makes that circular wait impossible; contenders simply queue. Semantically a
        // no-op: same rows, same atomicity, same all-or-nothing rule.
        event.items.sortedBy { it.itemId }.forEach { line ->
            reserveItemCommandHandler.handle(
                ReserveItemCommand(orderId, line.itemId, line.quantity, event.correlationId)
            )
        }

        completeOrderCommandHandler.handle(CompleteOrderCommand(orderId, event.correlationId))

        appendSuccessCounter.increment()
        log.info("[ORDER] confirmed orderId={} correlationId={}", orderId, event.correlationId)
    }
}
