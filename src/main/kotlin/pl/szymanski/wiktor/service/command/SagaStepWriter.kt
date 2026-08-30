package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservationReleasedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.Reservation
import pl.szymanski.wiktor.repository.InventoryBatchWriter
import pl.szymanski.wiktor.repository.SagaCursorWriter
import java.util.concurrent.TimeUnit

/**
 * Everything ONE LINE changes, computed in full before any of it is written. The per-line
 * counterpart to TO-3's `OrderReserveOutcome`, which carried a whole order.
 */
data class ReserveStepOutcome(
    val orderId: String,
    val lineIndex: Int,
    val updatedItem: InventoryItem,
    val reservation: Reservation,
    val event: InventoryReservedEvent,
)

/** The compensating shape: the item as restored, and the reservation row to remove. */
data class ReleaseStepOutcome(
    val orderId: String,
    val lineIndex: Int,
    val restoredItem: InventoryItem,
    val reservationId: String,
    val event: InventoryReservationReleasedEvent,
)

/**
 * The write transaction, and the only transaction on a saga step. It reads nothing and decides
 * nothing beyond whether the step is still this caller's to make — every value it writes was
 * computed outside it.
 *
 * **This is where the branch differs from TO-3 in kind rather than in degree.** TO-3's
 * `OrderWriteCommandHandler` writes an order: N reservations, N versioned item updates, the order
 * row and N+1 outbox rows, in one commit that is all-or-nothing. This writes a LINE. An order is
 * therefore N transactions, its partial state is visible to everyone in between, and the thing that
 * makes it whole again on failure is compensation rather than rollback.
 *
 * **Statement order is load-bearing, in two different ways.**
 *
 *  1. The saga cursor is claimed FIRST. A replayed step then rolls back — or rather, commits
 *     nothing — having touched neither the outbox nor inventory_state, which is what makes a
 *     redelivery a no-op instead of a second reservation. See [SagaCursorWriter].
 *  2. The versioned `inventory_state` UPDATE is LAST, as it is on TO-3, so the exclusive row lock
 *     is held only until COMMIT rather than across the outbox write. The lock window is now one
 *     line wide instead of one order wide, which is the effect this branch exists to measure.
 *
 * The outbox guarantee is unchanged and is doing MORE work here than on TO-3: `publishEvent` writes
 * the `event_publication` row synchronously inside this transaction, so the step's state change and
 * the event that drives the NEXT step commit as one unit. On TO-3 the outbox only had to reach the
 * mock-Kafka listener; here it is also the saga's own transport.
 */
@Service
class SagaStepWriter(
    private val sagaCursorWriter: SagaCursorWriter,
    private val inventoryBatchWriter: InventoryBatchWriter,
    private val applicationEventPublisher: ApplicationEventPublisher,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    // Same name and tags as TO-3, so the dashboards resolve unchanged — but ONE SAMPLE PER LINE
    // here, where TO-3 records one per order. A panel comparing the two must divide by
    // ITEMS_PER_ORDER or it is comparing a line against an order.
    //
    // The span itself is the same shape it is on TO-3: `startNs` is taken at entry, ahead of the
    // event_publication INSERT, so it ENCLOSES outbox.write.time rather than excluding it, and it
    // ends before the proxy's COMMIT so it is not durability.
    //
    // Split by outcome for the reason TO-3 splits it: recording only successes drops every attempt
    // that lost, and the losers are the slow ones — the final UPDATE blocks on the row lock until
    // the holder commits and only then fails its version predicate.
    private val committedWriteTimer: Timer = dbWriteTimer(meterRegistry, "committed")
    private val conflictWriteTimer: Timer = dbWriteTimer(meterRegistry, "conflict")
    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    /**
     * @return true if this caller owned the step and wrote it; false if the saga had already moved
     *         past it, in which case nothing was written and the caller must treat the step as
     *         done rather than retry or fail it.
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun writeReserve(outcome: ReserveStepOutcome): Boolean {
        val startNs = System.nanoTime()

        // 1. Claim the step. Nothing below this line runs for a redelivery.
        if (!sagaCursorWriter.advance(outcome.orderId, outcome.lineIndex)) {
            log.info(
                "[SAGA] reserve step already applied orderId={} lineIndex={} — no-op",
                outcome.orderId, outcome.lineIndex,
            )
            return false
        }

        try {
            // 2. Outbox: no inventory lock is held yet. This row is what wakes the saga for the
            //    next line, so the step and its continuation are the same commit.
            val outboxStartNs = System.nanoTime()
            applicationEventPublisher.publishEvent(outcome.event)
            outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

            // 3. The reservation row: FK share lock only.
            inventoryBatchWriter.insertAll(listOf(outcome.reservation))

            // 4. LAST: the versioned UPDATE. A conflict here throws and rolls the whole step back,
            //    cursor claim included, so the retry re-runs a clean step.
            inventoryBatchWriter.updateAll(listOf(outcome.updatedItem))
        } catch (e: ConcurrencyFailureException) {
            conflictWriteTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
            throw e
        }

        committedWriteTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
        return true
    }

    /**
     * The compensating step, in the same four-statement shape and for the same reasons.
     *
     * @return true if this caller owned the step and wrote it.
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun writeRelease(outcome: ReleaseStepOutcome): Boolean {
        val startNs = System.nanoTime()

        if (!sagaCursorWriter.retreat(outcome.orderId, outcome.lineIndex)) {
            log.info(
                "[SAGA] release step already applied orderId={} lineIndex={} — no-op",
                outcome.orderId, outcome.lineIndex,
            )
            return false
        }

        try {
            val outboxStartNs = System.nanoTime()
            applicationEventPublisher.publishEvent(outcome.event)
            outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

            inventoryBatchWriter.deleteReservation(outcome.restoredItem.id, outcome.reservationId)

            inventoryBatchWriter.updateAll(listOf(outcome.restoredItem))
        } catch (e: ConcurrencyFailureException) {
            conflictWriteTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
            throw e
        }

        committedWriteTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
        return true
    }

    private companion object {
        fun dbWriteTimer(meterRegistry: MeterRegistry, outcome: String): Timer =
            Timer.builder("state_persist_time")
                .tag("source", "db_write")
                .tag("outcome", outcome)
                .register(meterRegistry)
    }
}
