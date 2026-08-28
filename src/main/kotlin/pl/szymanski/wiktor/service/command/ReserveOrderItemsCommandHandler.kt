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
import java.time.Clock
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
 */
@Service
class ReserveOrderItemsCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val orderRepo: OrderRepository,
    private val orderWriteCommandHandler: OrderWriteCommandHandler,
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
        val working = LinkedHashMap<String, InventoryItem>(itemIds.size)
        itemIds.forEach { working[it] = loaded.getValue(it) }

        val reservations = ArrayList<Reservation>(event.items.size)
        val reservedEvents = ArrayList<InventoryReservedEvent>(event.items.size)

        // Lines are applied in the order the client sent them, against the running working copy, so
        // two lines naming the same item see each other's decrement — the sequential semantics the
        // per-line path got from re-reading the row. Each line still yields its own reservation and
        // event, as it did there.
        event.items.forEach { line ->
            val result = working.getValue(line.itemId)
                .reserve(orderId, line.quantity, event.correlationId, clock)
            working[line.itemId] = result.updatedItem
            reservations += result.reservation
            reservedEvents += result.event
        }

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
}
