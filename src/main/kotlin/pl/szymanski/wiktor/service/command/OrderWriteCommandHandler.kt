package pl.szymanski.wiktor.service.command

import com.github.benmanes.caffeine.cache.Cache
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.domain.Reservation
import pl.szymanski.wiktor.repository.InventoryBatchWriter
import pl.szymanski.wiktor.repository.InventoryVersionConflictException
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
 *
 * It is also where TO-4's inventory-state cache is kept honest, for the same two reasons the
 * per-line path kept it honest at its own save(): a conflict evicts the row that lost, so the
 * retry rereads it from the database instead of re-serving the stale version into an UPDATE whose
 * predicate can no longer match; and a success refreshes the cache AFTER commit, monotonically, so
 * a slow writer cannot drag it behind the database.
 */
@Service
class OrderWriteCommandHandler(
    private val inventoryBatchWriter: InventoryBatchWriter,
    private val orderRepo: OrderRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val inventoryStateCache: Cache<String, InventoryItem>,
    meterRegistry: MeterRegistry,
) {
    // Same name and tag as the per-line path, so the dashboards resolve unchanged — but ONE sample
    // per order here, where that path recorded one per line. Compare with ITEMS_PER_ORDER in hand.
    // As there, the timer stops before COMMIT, so the two measure the same span of work.
    private val dbWriteTimer: Timer = Timer.builder("state_persist_time")
        .tag("source", "db_write")
        .register(meterRegistry)
    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun write(outcome: OrderReserveOutcome) {
        val startNs = System.nanoTime()

        // 1. Outbox first: no inventory lock is held yet, so N event_publication INSERTs no longer
        //    sit inside the lock window the way they did on the per-line path.
        val outboxStartNs = System.nanoTime()
        outcome.reservedEvents.forEach { applicationEventPublisher.publishEvent(it) }
        applicationEventPublisher.publishEvent(outcome.completedEvent)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

        // 2. Reservations: FK share locks only.
        inventoryBatchWriter.insertAll(outcome.reservations)

        // 3. The order aggregate, already transitioned to CONFIRMED outside this transaction. Its
        //    own @Version still guards the write.
        orderRepo.save(outcome.confirmedOrder)

        // 4. LAST: the versioned batch UPDATE. Everything before this point is lock-free with
        //    respect to inventory_state; a conflict here throws OptimisticLockingFailureException
        //    and rolls the whole outcome back, which is what InventoryService retries.
        val written = try {
            inventoryBatchWriter.updateAll(outcome.updatedItems)
        } catch (e: InventoryVersionConflictException) {
            // Only the row that lost is stale — the rest of this order's rows still match what the
            // cache holds, because the rollback put the database back where they read it. Evicting
            // the whole order's worth would also be correct, and would understate the hit rate on
            // exactly the contended orders this branch exists to measure.
            inventoryStateCache.invalidate(e.itemId)
            throw e
        } catch (e: PessimisticLockingFailureException) {
            // No row named, so nothing better than the whole order to evict. Unreachable on the
            // optimistic branches; kept because TO-3-pessimistic shares this file's shape.
            inventoryStateCache.invalidateAll(outcome.updatedItems.map { it.id })
            throw e
        }

        // Refresh only after the DB commit succeeds, outside the transaction, from the rows the
        // writer says it wrote (version already bumped). The merge is monotonic per key, so two
        // post-commit refreshes landing out of order cannot move the cache backwards.
        registerCacheRefreshAfterCommit(written)

        dbWriteTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS)
    }

    /**
     * Merge the just-written rows into the cache after the transaction commits. Falls back to an
     * immediate merge when no transaction is active, which is what the unit tests exercise.
     */
    private fun registerCacheRefreshAfterCommit(written: List<InventoryItem>) {
        if (written.isEmpty()) return
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = written.forEach(::mergeIntoCache)
            })
        } else {
            written.forEach(::mergeIntoCache)
        }
    }

    private fun mergeIntoCache(item: InventoryItem) {
        inventoryStateCache.asMap().merge(item.id, item) { old, new ->
            if (new.version > old.version) new else old
        }
    }
}
