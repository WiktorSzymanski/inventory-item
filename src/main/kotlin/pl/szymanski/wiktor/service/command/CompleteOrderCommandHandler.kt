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
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CompleteOrderCommand(
    val orderId: String,
    val correlationId: UUID,
)

/**
 * Confirms a PENDING order (mirrors the ES branch's CompleteOrderCommand on the order aggregate).
 * Runs with Propagation.REQUIRED so it JOINS the enclosing reservation transaction: the CONFIRMED
 * state and its OrderCompletedEvent commit atomically with the reservations, or roll back with them.
 * The order is reloaded here rather than passed in, mirroring ES's per-command aggregate load.
 */
@Service
class CompleteOrderCommandHandler(
    private val orderRepo: OrderRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(command: CompleteOrderCommand) {
        val order = orderRepo.findById(command.orderId)
            .orElseThrow { NotFoundException("Order ${command.orderId} not found") }
        if (order.status != OrderStatus.PENDING) {
            log.info("[ORDER] skipping complete orderId={} already status={} correlationId={}", command.orderId, order.status, command.correlationId)
            return
        }

        val (confirmed, event) = order.confirm(clock)
        orderRepo.save(confirmed)

        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(event)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)
    }
}
