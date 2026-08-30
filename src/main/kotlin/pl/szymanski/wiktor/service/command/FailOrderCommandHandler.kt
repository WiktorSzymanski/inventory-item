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

data class FailOrderCommand(
    val orderId: String,
    val reason: String,
    val correlationId: UUID,
)

/**
 * Rejects a PENDING order (mirrors the ES branch's FailOrderCommand on the order aggregate) and
 * ends its saga in the same transaction.
 *
 * **The point at which this is reached moved.** On TO-3 it is called the moment a reservation
 * fails: the failed attempt's transaction has rolled back, so nothing is held and the order can be
 * rejected immediately. Here it is the LAST step of compensation, reached only once every line this
 * order reserved has been given back — `current_index` is 0 again — which may be N transactions and
 * a long time after the failure itself. Until then the order is still PENDING and its remaining
 * stock is still held, which is the latency the saga pattern trades atomicity for.
 *
 * The saga end is claimed first and guarded on `COMPENSATING AND current_index = 0`, so a
 * redelivered final release cannot reject an order twice, and a compensation that has not finished
 * walking back cannot reject one early.
 */
@Service
class FailOrderCommandHandler(
    private val orderRepo: OrderRepository,
    private val sagaCursorWriter: SagaCursorWriter,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    /** @return true if this caller rejected the order; false if the saga had already ended. */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(command: FailOrderCommand): Boolean {
        if (!sagaCursorWriter.endCompensated(command.orderId)) {
            log.info(
                "[SAGA] skipping fail orderId={} — saga is not COMPENSATING at 0 correlationId={}",
                command.orderId, command.correlationId,
            )
            return false
        }

        val order = orderRepo.findById(command.orderId)
            .orElseThrow { NotFoundException("Order ${command.orderId} not found") }
        if (order.status != OrderStatus.PENDING) {
            log.info("[ORDER] skipping fail orderId={} already status={} correlationId={}", command.orderId, order.status, command.correlationId)
            return false
        }

        val (rejected, event) = order.reject(command.reason, clock)
        orderRepo.save(rejected)

        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(event)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

        log.warn(
            "[ORDER] rejected orderId={} reason={} correlationId={}",
            command.orderId, command.reason, command.correlationId,
        )
        return true
    }
}
