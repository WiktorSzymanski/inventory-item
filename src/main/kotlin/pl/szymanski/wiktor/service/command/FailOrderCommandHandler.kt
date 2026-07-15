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

data class FailOrderCommand(
    val orderId: String,
    val reason: String,
    val correlationId: UUID,
)

/**
 * Rejects a PENDING order (mirrors the ES branch's FailOrderCommand on the order aggregate).
 * Called from the worker after the reservation transaction has rolled back, so it starts its own
 * transaction: the REJECTED state and its OrderFailedEvent commit as one atomic unit.
 */
@Service
class FailOrderCommandHandler(
    private val orderRepo: OrderRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(command: FailOrderCommand) {
        val order = orderRepo.findById(command.orderId)
            .orElseThrow { NotFoundException("Order ${command.orderId} not found") }
        if (order.status != OrderStatus.PENDING) {
            log.info("[ORDER] skipping fail orderId={} already status={} correlationId={}", command.orderId, order.status, command.correlationId)
            return
        }

        val (rejected, event) = order.reject(command.reason, clock)
        orderRepo.save(rejected)

        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(event)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)
    }
}
