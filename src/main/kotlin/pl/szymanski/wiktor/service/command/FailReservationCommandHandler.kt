package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.InventoryReservationFailedEvent
import pl.szymanski.wiktor.domain.OrderSaga
import pl.szymanski.wiktor.repository.SagaCursorWriter
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class FailReservationCommand(
    val orderId: String,
    val lineIndex: Int,
    val reason: String,
    /** Classification for `orders_completed{reason}`; see V8__order_saga.sql. */
    val reasonCode: String,
    val correlationId: UUID,
)

/**
 * Turns a failed reserve step into a durable saga signal.
 *
 * This handler exists because of a boundary that has no analogue on TO-3: the transaction that
 * discovered the failure is GONE. A reserve step fails either because the stock was not there
 * (thrown in the modify phase, before a transaction was ever opened) or because its retries were
 * exhausted (the last attempt's transaction rolled back). Either way there is nothing left to
 * piggyback the failure onto, and an exception on a worker thread is not a fact anybody else can
 * observe — the saga is waiting on an EVENT.
 *
 * On TO-3 this whole class collapses into `catch`: the reserve was one transaction, so its rollback
 * both undoes the work and ends the story, and `FailOrderCommandHandler` is called directly. Here
 * the failure has to be written down before it can be acted on, because acting on it means N more
 * transactions that themselves have to survive a crash.
 *
 * The transition is guarded: only a saga still `RUNNING` starts compensating, and only the caller
 * that made that transition publishes the event. Without that, a redelivered failure — or a second
 * line failing while the first failure is already being compensated — would start a second walk back
 * through the same lines and release the same stock twice.
 */
@Service
class FailReservationCommandHandler(
    private val sagaCursorWriter: SagaCursorWriter,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    /** @return true if this caller started compensation; false if it was already under way. */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(saga: OrderSaga, command: FailReservationCommand): Boolean {
        val orderId = command.orderId

        if (!sagaCursorWriter.beginCompensation(orderId, command.reason, command.reasonCode)) {
            log.info(
                "[SAGA] compensation already under way orderId={} status={} correlationId={}",
                orderId, saga.status, command.correlationId,
            )
            return false
        }

        // The item this failed on. Read from the saga rather than carried on the command: the
        // caller catching the exception is a retry loop that knows a line INDEX, and resolving the
        // index to an item there would duplicate the saga's own line list at the one point where
        // the two disagreeing would be hardest to notice.
        //
        // A failure at index == lineCount cannot happen — nothing reserves past the last line — but
        // an out-of-range index would throw here, inside a transaction, after the status has moved.
        // Clamped instead, because losing the compensation entirely is a worse outcome than naming
        // the wrong item in a log line.
        val itemId = command.lineIndex
            .takeIf { it in 0 until saga.lineCount }
            ?.let { saga.lineAt(it).itemId }
            ?: ""

        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(
            InventoryReservationFailedEvent(
                id = itemId,
                orderId = orderId,
                lineIndex = command.lineIndex,
                reason = command.reason,
                correlationId = saga.correlationId,
                createdAt = clock.instant(),
            )
        )
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

        log.warn(
            "[SAGA] reservation failed itemId={} lineIndex={} orderId={} reason={} correlationId={}",
            itemId, command.lineIndex, orderId, command.reason, command.correlationId,
        )
        return true
    }
}
