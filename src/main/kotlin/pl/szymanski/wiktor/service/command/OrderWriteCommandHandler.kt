package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.Reservation
import pl.szymanski.wiktor.repository.InventoryBatchWriter
import pl.szymanski.wiktor.repository.OrderRepository
import java.util.concurrent.TimeUnit

/**
 * Everything one order changes, computed in full before any of it is written. Produced by
 * [ReserveOrderItemsCommandHandler]'s read/modify phases with no transaction open, consumed by
 * [OrderWriteCommandHandler.write] inside one.
 */
data class OrderReserveOutcome(
    val confirmedOrder: Order,
    val updatedItems: List<InventoryItem>,
    val reservations: List<Reservation>,
    val reservedEvents: List<InventoryReservedEvent>,
    val completedEvent: OrderCompletedEvent,
)

/**
 * The write transaction, and the only transaction on the reserve path. It reads nothing and decides
 * nothing — every value it writes was computed outside it — so its duration is the duration of four
 * statements rather than of the whole order.
 *
 * **Statement order is load-bearing.** The exclusive row locks on `inventory_state` are taken by
 * the last statement and held only until COMMIT; on the per-line path this replaces they were taken
 * by line 1 and held across every later line's SELECT, its `reserveDelayMs` sleep and its outbox
 * INSERT. The reservations INSERT ahead of it takes only `FOR KEY SHARE` through the foreign key,
 * which does not conflict with another order's `FOR NO KEY UPDATE` of the same rows.
 *
 * The outbox guarantee is unchanged: `publishEvent` goes through the branch's event multicaster,
 * which writes the `event_publication` row synchronously inside the current transaction, so state
 * and events still commit or roll back as one unit.
 *
 * This lives on its own bean rather than as a method on the handler because the handler's entry
 * point is deliberately NOT transactional — a self-call would bypass the proxy and silently run
 * the whole thing outside a transaction.
 */
@Service
class OrderWriteCommandHandler(
    private val inventoryBatchWriter: InventoryBatchWriter,
    private val orderRepo: OrderRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    meterRegistry: MeterRegistry,
) {
    // Same name and tag as the per-line path, so the dashboards resolve unchanged — but ONE sample
    // per order here, where that path recorded one per line. Compare with ITEMS_PER_ORDER in hand.
    //
    // Two things this span is NOT, both deliberate, both previously misdescribed here as making it
    // equivalent to the per-line path's:
    //
    //  * It is not durability. `write` is @Transactional, so the proxy COMMITs after the method
    //    returns and the WAL flush lands outside every sample below.
    //  * It is not state-writes-only. `startNs` is taken at entry, AHEAD of the event_publication
    //    INSERTs, so this encloses `outbox.write.time` rather than excluding it — on TO the outbox
    //    write is part of what an order costs to persist, and it is the nearest analogue to the ES
    //    branches' single append. The per-line path times only its two saves and excludes the
    //    outbox, so the two spans differ by exactly the outbox write; they are not interchangeable.
    //
    // Split by outcome because recording only on success dropped every attempt that lost, and the
    // losers are the SLOW ones: statement 4 blocks on the inventory_state row lock until the holder
    // commits and only then fails its version predicate, so a success-only histogram gets BETTER as
    // contention gets worse. `committed` is bit-for-bit the series this timer always recorded, so
    // archived runs stay comparable; `conflict` is the mass that used to be invisible. A query
    // wanting the old number matches outcome!="conflict", which also selects the untagged series a
    // replayed pre-change run carries.
    private val committedWriteTimer: Timer = dbWriteTimer(meterRegistry, "committed")
    private val conflictWriteTimer: Timer = dbWriteTimer(meterRegistry, "conflict")
    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun write(outcome: OrderReserveOutcome) {
        val startNs = System.nanoTime()

        try {
            // 1. Outbox first: no inventory lock is held yet, so N event_publication INSERTs no
            //    longer sit inside the lock window the way they did on the per-line path.
            val outboxStartNs = System.nanoTime()
            outcome.reservedEvents.forEach { applicationEventPublisher.publishEvent(it) }
            applicationEventPublisher.publishEvent(outcome.completedEvent)
            outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

            // 2. Reservations: FK share locks only.
            inventoryBatchWriter.insertAll(outcome.reservations)

            // 3. The order aggregate, already transitioned to CONFIRMED outside this transaction.
            //    Its own @Version still guards the write.
            orderRepo.save(outcome.confirmedOrder)

            // 4. LAST: the versioned batch UPDATE. Everything before this point is lock-free with
            //    respect to inventory_state; a conflict here throws OptimisticLockingFailureException
            //    and rolls the whole outcome back, which is what InventoryService retries.
            inventoryBatchWriter.updateAll(outcome.updatedItems)
        } catch (e: ConcurrencyFailureException) {
            // The union of OptimisticLockingFailureException and PessimisticLockingFailureException,
            // i.e. exactly what InventoryService.runOrderTask classifies as a conflict and retries.
            // Timed and rethrown: the attempt still fails, it just stops being invisible. Anything
            // else (NotFound, a driver fault) is left unsampled — it is not the biased mass, and
            // giving it a bucket would only invite it to be read as write cost.
            conflictWriteTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
            throw e
        }

        committedWriteTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
    }

    private companion object {
        fun dbWriteTimer(meterRegistry: MeterRegistry, outcome: String): Timer =
            Timer.builder("state_persist_time")
                .tag("source", "db_write")
                .tag("outcome", outcome)
                .register(meterRegistry)
    }
}
