package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.SagaCursorWriter
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CompleteOrderCommand(
    val orderId: String,
    val lineCount: Int,
    val correlationId: UUID,
)

/**
 * Confirms an order whose every line is reserved, and ends its saga in the same transaction.
 *
 * TO-3 has no such handler: there, `Order.confirm` happens inside the one write transaction that
 * also reserves every line, so confirmation is not a separate decision and cannot be observed
 * separately. Here the last reserve step has already committed by the time this runs, so the order
 * spends a real interval CONFIRMABLE but not yet CONFIRMED — the mirror image of the ES branch's
 * saga, which likewise sends `CompleteOrderCommand` after the final `InventoryReservedEvent` comes
 * back.
 *
 * Both writes are guarded, and they guard different things. The saga guard
 * ([SagaCursorWriter.endCompleted]) requires the saga to still be RUNNING with every line reserved,
 * which is what stops a redelivered "last line" event from confirming an order that has meanwhile
 * been failed. The order's own PENDING check is the same one [FailOrderCommandHandler] makes, and
 * keeps confirm and reject from racing each other into a double transition.
 */
@Service
class CompleteOrderCommandHandler(
    private val orderRepo: OrderRepository,
    private val sagaCursorWriter: SagaCursorWriter,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    /** @return true if this caller confirmed the order; false if the saga had already ended. */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(command: CompleteOrderCommand): Boolean {
        val orderId = command.orderId

        // Claimed first, for the reason every other step claims first: nothing below runs twice.
        if (!sagaCursorWriter.endCompleted(orderId, command.lineCount)) {
            log.info(
                "[SAGA] skipping complete orderId={} — saga is not RUNNING at {} correlationId={}",
                orderId, command.lineCount, command.correlationId,
            )
            return false
        }

        val order = orderRepo.findById(orderId)
            .orElseThrow { NotFoundException("Order $orderId not found") }
        if (order.status != OrderStatus.PENDING) {
            log.info(
                "[ORDER] skipping confirm orderId={} already status={} correlationId={}",
                orderId, order.status, command.correlationId,
            )
            return false
        }

        val (confirmed, event) = order.confirm(clock)
        orderRepo.save(confirmed)

        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(event)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

        log.info("[ORDER] confirmed orderId={} correlationId={}", orderId, command.correlationId)
        return true
    }
}
