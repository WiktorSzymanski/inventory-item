package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.ReservationRepository
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ReserveItemCommand(
    val orderId: String,
    val itemId: String,
    val quantity: Int,
    val correlationId: UUID,
)

/**
 * Reserves a single inventory item. Runs with Propagation.REQUIRED so it JOINS the enclosing
 * order-reservation transaction: every item in an order is reserved within one transaction and
 * commits (or rolls back) atomically, preserving TO's confirmed-only-if-all-reserved rule.
 *
 * Per-item load and save (rather than the previous batch I/O) mirror the ES branch's
 * per-aggregate command model; state_load_time/state_persist_time therefore become per-item.
 *
 * This branch's defining delta against TO-3: the load is `SELECT … FOR UPDATE`, so the row lock is
 * taken at read time and held for the rest of the enclosing transaction. Concurrent reserves of
 * the same item queue on the lock instead of racing to the write and losing on `@Version`, which
 * means state_load_time{source=db_fetch} now includes lock-wait time — that measurement IS the
 * cost of the strategy, so the timer deliberately still wraps the whole call.
 */
@Service
class ReserveItemCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val reservationRepo: ReservationRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val dbFetchTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .register(meterRegistry)
    // Split by outcome for the same reason the split-path branches split it: recording only on the
    // way out drops every attempt that threw, and those are the slow ones. The bias is structurally
    // smaller HERE than there — this branch takes its row lock at read time, so the wait is already
    // priced into state_load_time{source=db_fetch} rather than hiding in the write — but the tag has
    // to exist on every TO branch or a cross-branch query means different things on different ones.
    //
    // Note this span is NOT the split path's: it covers the two saves only, and the outbox write is
    // timed separately below and excluded. The two differ by exactly the outbox write.
    private val committedWriteTimer: Timer = dbWriteTimer(meterRegistry, "committed")
    private val conflictWriteTimer: Timer = dbWriteTimer(meterRegistry, "conflict")
    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(command: ReserveItemCommand) {
        val dbStartNs = System.nanoTime()
        val item = inventoryRepo.findForUpdateById(command.itemId)
            .orElseThrow { NotFoundException("Item ${command.itemId} not found") }
        dbFetchTimer.record(System.nanoTime() - dbStartNs, TimeUnit.NANOSECONDS)

        val result = item.reserve(command.orderId, command.quantity, command.correlationId, clock)

        val dbWriteStartNs = System.nanoTime()
        try {
            inventoryRepo.save(result.updatedItem)
            reservationRepo.save(result.reservation)
        } catch (e: ConcurrencyFailureException) {
            // The union of the optimistic and pessimistic lock failures, i.e. exactly what
            // InventoryService.runOrderTask classifies as a conflict and retries. Timed and
            // rethrown, so a losing attempt costs a sample rather than disappearing.
            conflictWriteTimer.record(System.nanoTime() - dbWriteStartNs, TimeUnit.NANOSECONDS)
            throw e
        }
        committedWriteTimer.record(System.nanoTime() - dbWriteStartNs, TimeUnit.NANOSECONDS)

        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(result.event)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)
    }

    private companion object {
        fun dbWriteTimer(meterRegistry: MeterRegistry, outcome: String): Timer =
            Timer.builder("state_persist_time")
                .tag("source", "db_write")
                .tag("outcome", outcome)
                .register(meterRegistry)
    }
}
