package pl.szymanski.wiktor.service.command

import com.github.benmanes.caffeine.cache.Cache
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.OrderReservationCreatedEvent
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.ReservationRepository
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OrderItem(val itemId: String, val quantity: Int)

data class CreateOrderReservationCommand(
    val userId: String,
    val items: List<OrderItem>,
    val correlationId: UUID = UUID.randomUUID(),
)

@Service
class CreateOrderReservationCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val reservationRepo: ReservationRepository,
    private val orderRepo: OrderRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val inventoryStateCache: Cache<String, InventoryItem>,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val dbFetchTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .register(meterRegistry)
    private val dbWriteTimer: Timer = Timer.builder("state_persist_time")
        .tag("source", "db_write")
        .register(meterRegistry)
    private val outboxWriteTimer: Timer = meterRegistry.timer("outbox.write.time")
    private val appendSuccessCounter: Counter = meterRegistry.counter("inventory.append.success")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    fun handle(orderId: String, command: CreateOrderReservationCommand) {
        log.info("[ORDER] processing orderId={} userId={} itemCount={} correlationId={}", orderId, command.userId, command.items.size, command.correlationId)

        val itemIds = command.items.map { it.itemId }
        // Cache hits are effectively free; only the DB fetch of the misses is timed as state load.
        val cached = inventoryStateCache.getAllPresent(itemIds)
        val missingIds = itemIds.filterNot { cached.containsKey(it) }
        val fetched = if (missingIds.isEmpty()) {
            emptyMap()
        } else {
            val dbStartNs = System.nanoTime()
            val byId = inventoryRepo.findAllById(missingIds).associateBy { it.id }
            dbFetchTimer.record(System.nanoTime() - dbStartNs, TimeUnit.NANOSECONDS)
            byId
        }
        val foundItems = cached + fetched

        val results = command.items.map { orderItem ->
            val item = foundItems[orderItem.itemId]
                ?: throw NotFoundException("Item ${orderItem.itemId} not found")
            item.reserve(orderId, orderItem.quantity, command.correlationId)
        }

        val dbWriteStartNs = System.nanoTime()
        val savedItems = try {
            val saved = inventoryRepo.saveAll(results.map { it.updatedItem })
            reservationRepo.saveAll(results.map { it.reservation })
            orderRepo.updateStatus(orderId, OrderStatus.CONFIRMED, null)
            saved
        } catch (e: OptimisticLockingFailureException) {
            // Evict so the @Retryable retry rereads fresh from the DB instead of re-serving the
            // stale cached version into the optimistic UPDATE (which would guarantee exhaustion).
            inventoryStateCache.invalidateAll(itemIds)
            throw e
        } catch (e: PessimisticLockingFailureException) {
            inventoryStateCache.invalidateAll(itemIds)
            throw e
        }
        dbWriteTimer.record(System.nanoTime() - dbWriteStartNs, TimeUnit.NANOSECONDS)

        // Refresh the cache only after the DB commit succeeds, outside the transaction. The
        // version guard keeps the cache monotonic so a slow writer can't drag it behind the DB.
        registerCacheRefreshAfterCommit(savedItems)

        // publishEvent inserts into event_publication synchronously inside this transaction,
        // so this timer captures the outbox write overhead of the TO pattern.
        val outboxStartNs = System.nanoTime()
        results.forEach { applicationEventPublisher.publishEvent(it.event) }
        applicationEventPublisher.publishEvent(
            OrderReservationCreatedEvent(
                orderId = orderId,
                userId = command.userId,
                items = command.items.map { ReservedItem(it.itemId, it.quantity) },
                correlationId = command.correlationId,
            )
        )
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)

        appendSuccessCounter.increment()
        log.info("[ORDER] confirmed orderId={} correlationId={}", orderId, command.correlationId)
    }

    /**
     * Merge the just-saved (version-incremented) items into the cache after the transaction
     * commits. The merge is monotonic per key — it never overwrites a newer version with an
     * older one — so concurrent post-commit refreshes that land out of order cannot drag the
     * cache behind the DB. Falls back to an immediate merge when no transaction is active.
     */
    private fun registerCacheRefreshAfterCommit(savedItems: Iterable<InventoryItem>) {
        val items = savedItems.toList()
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = mergeIntoCache(items)
            })
        } else {
            mergeIntoCache(items)
        }
    }

    private fun mergeIntoCache(items: List<InventoryItem>) {
        items.forEach { item ->
            inventoryStateCache.asMap().merge(item.id, item) { old, new ->
                if (new.version > old.version) new else old
            }
        }
    }
}
