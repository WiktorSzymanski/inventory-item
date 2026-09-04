package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.domain.Reservation
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.service.ReserveFanoutPool
import java.time.Clock
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Reservation phase, triggered by consuming OrderCreatedEvent, and the place the transaction
 * boundary is drawn.
 *
 * **The per-line path this replaces** ran the whole phase in a single transaction: one
 * `ReserveItemCommand` per line, each joining it, each doing SELECT → `reserve()` → versioned
 * UPDATE → INSERT reservations → INSERT event_publication. Line 1's UPDATE took an exclusive row
 * lock that Postgres held until the order committed, so every later line's read, its
 * `reserveDelayMs` sleep and its outbox write all happened with that lock (and a Hikari
 * connection) held. It is still what TO-2-opt, TO-3-pessimistic and TO-3-mod-A do, which is what
 * makes them the comparison for this shape.
 *
 * Here the phase is split in three, and **only the third is transactional**:
 *
 *  1. **Read** — one `findAllById` for the whole order, replacing N SELECTs. No transaction.
 *  2. **Modify** — `InventoryItem.reserve` per line against an in-memory working copy, plus
 *     `Order.confirm`. No transaction, no database connection, and therefore no row lock. This is
 *     where `reserveDelayMs` is now paid and where InsufficientStockException is now thrown, with
 *     nothing to roll back.
 *  3. **Write** — [OrderWriteCommandHandler.write], which writes the accumulated result in four
 *     statements and nothing else.
 *
 * Two consequences of the split are worth naming rather than discovering in a run. Reads are no
 * longer serialised by row locks, so version conflicts (and therefore retries) should go UP while
 * lock hold time goes down — that trade is the result, not a regression. And the `reserveDelayMs`
 * sleep now holds neither a lock nor a connection, where on the per-line path it held both;
 * against a 50 connection pool and 200 worker threads that is a second, distinct effect riding the
 * same change.
 *
 * Atomicity is unchanged: an order is still all-or-nothing, its state and its events still commit
 * together, and a failure anywhere still leaves the order for InventoryService to reject.
 *
 * **What TO-3-parallel changes, and only this.** Phase 2 is fanned out across [ReserveFanoutPool]
 * instead of folded on the order-worker thread. TO-3 pays `lines x reserveDelayMs` there — 100 ms
 * per order at W-base/C01, 400 ms at W-fan/C01 — and pays it serially; here the groups run at once,
 * so the phase costs about one delay. Phases 1 and 3 are byte-for-byte TO-3's.
 *
 * The unit of parallelism is the ITEM, not the line: lines naming the same item stay on one thread
 * in client order, so they still see each other's decrement. Results are written back at their
 * ORIGINAL indices, so `reservations` and `reservedEvents` come out in exactly the order the
 * sequential fold produced them — same rows, same outbox sequence, same batch. Nothing downstream
 * can tell which path built them, which is what makes the A/B against TO-3 a clean one.
 *
 * This is the TO counterpart to ES-2-parallel, with one honest asymmetry worth stating: ES-2-parallel
 * removes L sequential round trips of real IO, because on that branch each line is a separate
 * aggregate load and append. Here there is no IO to remove — phase 1 is already one batched query
 * and phase 3 already one batched transaction — so what parallelises is the artificial delay and,
 * with it, the conflict window. That the TO shape has less to gain is the finding, not a defect.
 */
@Service
class ReserveOrderItemsCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val orderRepo: OrderRepository,
    private val orderWriteCommandHandler: OrderWriteCommandHandler,
    private val reserveFanoutPool: ReserveFanoutPool,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    // Same name and tag as the per-line path, so the dashboards resolve unchanged — but ONE sample
    // per order here, where that path recorded one per line.
    //
    // Split by {aggregate} because this handler loads two different things: the order row once, and
    // the order's inventory rows once. Pooled into one histogram their p50 tracks the mix rather
    // than the cost of either, and the ES branches — which tag the same metric with the Axon
    // aggregate type — have nothing to line up against.
    private val itemLoadTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .tag("aggregate", "InventoryItem")
        .register(meterRegistry)

    private val orderLoadTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .tag("aggregate", "Order")
        .register(meterRegistry)

    private val appendSuccessCounter: Counter = meterRegistry.counter("inventory.append.success")

    fun handle(event: OrderCreatedEvent) {
        val orderId = event.orderId
        log.info("[ORDER] processing orderId={} userId={} itemCount={} correlationId={}", orderId, event.userId, event.items.size, event.correlationId)

        // ---- Phase 1: read ---------------------------------------------------------------
        // Idempotency guard: OrderCreatedEvent may be re-delivered by the backup poller after a
        // crash. A previously confirmed/rejected order is skipped; a still-PENDING order (including
        // one whose prior attempt rolled back and is being retried) proceeds.
        // Timed before the guard below, and before the throw: a missing or already-settled order
        // still cost a full round trip, and dropping those samples would fit the histogram to the
        // orders that went on to do work.
        val orderStartNs = System.nanoTime()
        val found = orderRepo.findById(orderId)
        orderLoadTimer.record(System.nanoTime() - orderStartNs, TimeUnit.NANOSECONDS)
        val order = found.orElseThrow { NotFoundException("Order $orderId not found") }
        if (order.status != OrderStatus.PENDING) {
            log.info("[ORDER] skipping orderId={} already status={} correlationId={}", orderId, order.status, event.correlationId)
            return
        }

        // Sorted here so the working copy — and therefore the batch UPDATE built from it — carries
        // the same global item_id lock order the per-line path got from its `sortedBy`.
        val itemIds = event.items.map { it.itemId }.distinct().sorted()

        val dbStartNs = System.nanoTime()
        val loaded = inventoryRepo.findAllById(itemIds).associateBy { it.id }
        itemLoadTimer.record(System.nanoTime() - dbStartNs, TimeUnit.NANOSECONDS)

        val missing = itemIds.firstOrNull { it !in loaded }
        if (missing != null) {
            throw NotFoundException("Item $missing not found")
        }

        // ---- Phase 2: modify (no transaction, no connection, no lock) ---------------------
        // Grouped BY ITEM, and one task per group — never one per line. Two lines naming the same
        // item have to see each other's decrement, which is the semantics the per-line path got
        // from re-reading the row; running them on separate threads against the same loaded copy
        // would lose one of the two decrements outright. Within a group the fold is sequential and
        // in client order, exactly as it was.
        val groups = event.items.withIndex().groupBy { it.value.itemId }

        // Written at the line's ORIGINAL index rather than appended, so the two lists come out in
        // the order the client sent the lines whatever order the groups happen to finish in. The
        // slots are disjoint per group and every write is published to this thread by Future.get()
        // below, so no synchronisation is needed on them.
        val reservationSlots = arrayOfNulls<Reservation>(event.items.size)
        val eventSlots = arrayOfNulls<InventoryReservedEvent>(event.items.size)

        val tasks = groups.map { (itemId, lines) ->
            Callable {
                var item = loaded.getValue(itemId)
                lines.forEach { (index, line) ->
                    val result = item.reserve(orderId, line.quantity, event.correlationId, clock)
                    item = result.updatedItem
                    reservationSlots[index] = result.reservation
                    eventSlots[index] = result.event
                }
                itemId to item
            }
        }

        // invokeAll returns only once every group has finished, so this is the join. A group that
        // threw surfaces here, in awaitOrRethrow, with nothing written anywhere yet.
        val reserved = reserveFanoutPool.invokeAll(tasks)
            .map { awaitOrRethrow(it) }
            .toMap()

        // Rebuilt in the sorted itemIds order, which is the global lock order phase 3's batch
        // UPDATE inherits from it. Every id in itemIds came from a line, so every one has a group.
        val working = LinkedHashMap<String, InventoryItem>(itemIds.size)
        itemIds.forEach { working[it] = reserved.getValue(it) }

        val reservations = reservationSlots.map { checkNotNull(it) }
        val reservedEvents = eventSlots.map { checkNotNull(it) }

        val (confirmed, completedEvent) = order.confirm(clock)

        // ---- Phase 3: write --------------------------------------------------------------
        orderWriteCommandHandler.write(
            OrderReserveOutcome(
                confirmedOrder = confirmed,
                updatedItems = working.values.toList(),
                reservations = reservations,
                reservedEvents = reservedEvents,
                completedEvent = completedEvent,
            )
        )

        appendSuccessCounter.increment()
        log.info("[ORDER] confirmed orderId={} correlationId={}", orderId, event.correlationId)
    }

    /**
     * Unwraps the fan-out so a caller sees what the sequential fold would have thrown.
     *
     * This matters more than it looks. `InventoryService.runOrderTask` decides between RETRY and
     * terminal FAIL by testing the exception against `OptimisticLockingFailureException` /
     * `PessimisticLockingFailureException`; an `ExecutionException` wrapper matches neither, so a
     * wrapped `InsufficientStockException` would still fail the order but would arrive as a
     * different exception with a different message than TO-3 records for the same input, and any
     * future conflict raised in this phase would silently stop being retried. Kotlin has no checked
     * exceptions, so rethrowing the cause needs no declaration even though the domain exceptions
     * extend `Exception` rather than `RuntimeException`.
     */
    private fun <T> awaitOrRethrow(future: Future<T>): T =
        try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            // Restore the flag the catch cleared, so a shutdown in progress still reads as one to
            // everything above this frame.
            Thread.currentThread().interrupt()
            throw e
        }
}
