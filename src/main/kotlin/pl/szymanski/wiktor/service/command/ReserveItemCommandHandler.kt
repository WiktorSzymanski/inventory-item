package pl.szymanski.wiktor.service.command

import com.github.benmanes.caffeine.cache.Cache
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.szymanski.wiktor.domain.InventoryItem
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
 * The item state is read through the Caffeine cache: a hit skips the DB fetch entirely (only DB
 * fetches of misses are timed as state load). An optimistic/pessimistic conflict evicts the entry
 * so the @Retryable retry rereads fresh from the DB, and the version-guarded post-commit merge
 * keeps cached entries monotonic with the DB.
 */
@Service
class ReserveItemCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val reservationRepo: ReservationRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val inventoryStateCache: Cache<String, InventoryItem>,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val dbFetchTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .register(meterRegistry)
    private val dbWriteTimer: Timer = Timer.builder("state_persist_time")
        .tag("source", "db_write")
        .register(meterRegistry)
    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(command: ReserveItemCommand) {
        val item = inventoryStateCache.getIfPresent(command.itemId) ?: run {
            val dbStartNs = System.nanoTime()
            val fetched = inventoryRepo.findById(command.itemId)
                .orElseThrow { NotFoundException("Item ${command.itemId} not found") }
            dbFetchTimer.record(System.nanoTime() - dbStartNs, TimeUnit.NANOSECONDS)
            fetched
        }

        val result = item.reserve(command.orderId, command.quantity, command.correlationId, clock)

        val dbWriteStartNs = System.nanoTime()
        val savedItem = try {
            val saved = inventoryRepo.save(result.updatedItem)
            reservationRepo.save(result.reservation)
            saved
        } catch (e: OptimisticLockingFailureException) {
            // Evict so the @Retryable retry rereads fresh from the DB instead of re-serving the
            // stale cached version into the optimistic UPDATE (which would guarantee exhaustion).
            inventoryStateCache.invalidate(command.itemId)
            throw e
        } catch (e: PessimisticLockingFailureException) {
            inventoryStateCache.invalidate(command.itemId)
            throw e
        }
        dbWriteTimer.record(System.nanoTime() - dbWriteStartNs, TimeUnit.NANOSECONDS)

        // Refresh the cache only after the DB commit succeeds, outside the transaction. The
        // version guard keeps the cache monotonic so a slow writer can't drag it behind the DB.
        registerCacheRefreshAfterCommit(savedItem)

        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(result.event)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)
    }

    /**
     * Merge the just-saved (version-incremented) item into the cache after the transaction
     * commits. The merge is monotonic per key — it never overwrites a newer version with an
     * older one — so concurrent post-commit refreshes that land out of order cannot drag the
     * cache behind the DB. Falls back to an immediate merge when no transaction is active.
     */
    private fun registerCacheRefreshAfterCommit(savedItem: InventoryItem) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = mergeIntoCache(savedItem)
            })
        } else {
            mergeIntoCache(savedItem)
        }
    }

    private fun mergeIntoCache(item: InventoryItem) {
        inventoryStateCache.asMap().merge(item.id, item) { old, new ->
            if (new.version > old.version) new else old
        }
    }
}
