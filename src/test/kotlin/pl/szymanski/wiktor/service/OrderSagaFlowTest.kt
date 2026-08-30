package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.task.SyncTaskExecutor
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.InventoryReservationFailedEvent
import pl.szymanski.wiktor.domain.InventoryReservationReleasedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderSaga
import pl.szymanski.wiktor.domain.OrderStatus
import pl.szymanski.wiktor.domain.Reservation
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.domain.SagaLines
import pl.szymanski.wiktor.domain.SagaStatus
import pl.szymanski.wiktor.repository.InventoryBatchWriter
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.InventoryVersionConflictException
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.OrderSagaRepository
import pl.szymanski.wiktor.repository.SagaCursorWriter
import pl.szymanski.wiktor.service.command.CompleteOrderCommandHandler
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailReservationCommandHandler
import pl.szymanski.wiktor.service.command.ReleaseReservationCommandHandler
import pl.szymanski.wiktor.service.command.ReserveOrderItemCommandHandler
import pl.szymanski.wiktor.service.command.SagaStepWriter
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * The saga end to end, over an in-memory database and a hand-cranked outbox.
 *
 * Every other test on this branch mocks one seam. This one wires the REAL handlers — reserve,
 * release, fail-reservation, complete, fail-order, the step writer and the service's step machine —
 * against in-memory stores, and closes the loop the way `event_publication` does in production: a
 * publisher that appends to a queue, and a [drain] that hands each event back to
 * [OrderReservationSaga]. That loop is the thing worth testing, because it is the thing that does
 * not exist on TO-3 at all. TO-3's reservation is one call; here an order only completes if N
 * separate transactions each publish an event that wakes the next one, and a break anywhere in that
 * chain shows up as an order that simply sits in PENDING, with no exception and no failing
 * assertion anywhere else.
 *
 * The stores enforce the two invariants that make the outcomes meaningful: `inventory_state` checks
 * the version predicate the way the real batch UPDATE does, and the saga's cursor moves only through
 * the same guarded transitions [SagaCursorWriter] issues as SQL.
 */
class OrderSagaFlowTest {

    private val clock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    private val registry = SimpleMeterRegistry()

    // ---- the in-memory database ---------------------------------------------------------------
    private val items = LinkedHashMap<String, InventoryItem>()
    private val orders = LinkedHashMap<String, Order>()
    private val sagas = LinkedHashMap<String, OrderSaga>()
    private val reservations = LinkedHashSet<Pair<String, String>>()

    /** Items whose next versioned UPDATE must lose, once each. Drives the conflict cases. */
    private val conflictOnce = HashSet<String>()

    // ---- the outbox, as a queue ---------------------------------------------------------------
    //
    // Events are STAGED, not published, and only become visible when the step's transaction commits
    // — which is the whole point of an outbox and is load-bearing for the conflict cases here.
    // `SagaStepWriter` writes the event BEFORE the versioned inventory UPDATE that can fail, so a
    // publisher that appended straight to the queue would deliver the event of a step that rolled
    // back, and the saga would advance on work that never happened.
    private val outbox = ArrayDeque<Any>()
    private val staged = mutableListOf<Any>()
    private val delivered = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { event -> staged.add(event) }

    /**
     * One transaction over the in-memory stores.
     *
     * Everything a step touches — the saga cursor, inventory rows, reservation rows and the staged
     * events — is snapshotted on entry and restored if [block] throws. Without this the tests would
     * assert against a database no real transaction could produce: a conflict would leave behind the
     * reservation row and the cursor move of the attempt that lost.
     */
    private fun <T> tx(block: () -> T): T {
        val sagaSnapshot = LinkedHashMap(sagas)
        val itemSnapshot = LinkedHashMap(items)
        val orderSnapshot = LinkedHashMap(orders)
        val reservationSnapshot = LinkedHashSet(reservations)
        val stagedMark = staged.size
        return try {
            block().also {
                outbox.addAll(staged.subList(stagedMark, staged.size))
                staged.subList(stagedMark, staged.size).clear()
            }
        } catch (e: Throwable) {
            sagas.clear(); sagas.putAll(sagaSnapshot)
            items.clear(); items.putAll(itemSnapshot)
            orders.clear(); orders.putAll(orderSnapshot)
            reservations.clear(); reservations.addAll(reservationSnapshot)
            staged.subList(stagedMark, staged.size).clear()
            throw e
        }
    }

    private val inventoryRepo = mockk<InventoryRepository>().also { repo ->
        every { repo.findById(any()) } answers { Optional.ofNullable(items[firstArg<String>()]) }
    }

    private val orderRepo = mockk<OrderRepository>().also { repo ->
        every { repo.findById(any()) } answers { Optional.ofNullable(orders[firstArg<String>()]) }
        every { repo.save(any<Order>()) } answers { firstArg<Order>().also { orders[it.orderId] = it } }
    }

    private val sagaRepo = mockk<OrderSagaRepository>().also { repo ->
        every { repo.findById(any()) } answers { Optional.ofNullable(sagas[firstArg<String>()]) }
    }

    private val batchWriter = mockk<InventoryBatchWriter>().also { writer ->
        every { writer.updateAll(any()) } answers {
            val rows = firstArg<List<InventoryItem>>()
            rows.map { row ->
                val current = items.getValue(row.id)
                // The version predicate, exactly as the real batch UPDATE applies it: a row that
                // moved since the read phase matches nothing and the whole step rolls back.
                if (current.version != row.version || conflictOnce.remove(row.id)) {
                    throw InventoryVersionConflictException(row.id, row.version)
                }
                row.copy(version = row.version + 1).also { items[row.id] = it }
            }
        }
        every { writer.insertAll(any()) } answers {
            firstArg<List<Reservation>>().forEach { reservations += it.itemId to it.reservationId }
        }
        every { writer.deleteReservation(any(), any()) } answers {
            if (reservations.remove(firstArg<String>() to secondArg<String>())) 1 else 0
        }
    }

    /**
     * The guarded transitions, in memory. Deliberately spelled out rather than relaxed-mocked: these
     * predicates are the branch's idempotency argument, so a test that let any transition through
     * unconditionally would prove nothing about redelivery.
     */
    private val cursor = mockk<SagaCursorWriter>().also { c ->
        every { c.advance(any(), any()) } answers {
            val orderId = firstArg<String>()
            val lineIndex = secondArg<Int>()
            transition(orderId, { it.isReserveStep(lineIndex) }, { it.copy(currentIndex = lineIndex + 1) })
        }
        every { c.retreat(any(), any()) } answers {
            val orderId = firstArg<String>()
            val lineIndex = secondArg<Int>()
            transition(orderId, { it.isReleaseStep(lineIndex) }, { it.copy(currentIndex = lineIndex) })
        }
        every { c.beginCompensation(any(), any(), any()) } answers {
            val orderId = firstArg<String>()
            val reason = secondArg<String>()
            val code = thirdArg<String>()
            transition(
                orderId,
                { it.status == SagaStatus.RUNNING },
                { it.copy(status = SagaStatus.COMPENSATING, failureReason = reason, failureCode = code) },
            )
        }
        every { c.endCompleted(any(), any()) } answers {
            val orderId = firstArg<String>()
            val lineCount = secondArg<Int>()
            transition(
                orderId,
                { it.status == SagaStatus.RUNNING && it.currentIndex == lineCount },
                { it.copy(status = SagaStatus.ENDED) },
            )
        }
        every { c.endCompensated(any()) } answers {
            transition(
                firstArg<String>(),
                { it.status == SagaStatus.COMPENSATING && it.currentIndex == 0 },
                { it.copy(status = SagaStatus.ENDED) },
            )
        }
    }

    private fun transition(
        orderId: String,
        guard: (OrderSaga) -> Boolean,
        move: (OrderSaga) -> OrderSaga,
    ): Boolean {
        val saga = sagas[orderId] ?: return false
        if (!guard(saga)) return false
        sagas[orderId] = move(saga).copy(version = saga.version + 1)
        return true
    }

    // ---- the real components under test -------------------------------------------------------
    //
    // Each @Transactional entry point is wrapped in [tx], because there is no Spring proxy here to
    // supply one. The wrapping is the only thing the test adds; the logic inside is the shipped
    // code.
    private val stepWriter = spyk(SagaStepWriter(cursor, batchWriter, publisher, registry)) {
        every { writeReserve(any()) } answers { tx { callOriginal() } }
        every { writeRelease(any()) } answers { tx { callOriginal() } }
    }

    private val failReservationHandler =
        spyk(FailReservationCommandHandler(cursor, publisher, clock, registry)) {
            every { handle(any(), any()) } answers { tx { callOriginal() } }
        }

    private val completeHandler =
        spyk(CompleteOrderCommandHandler(orderRepo, cursor, publisher, clock, registry)) {
            every { handle(any()) } answers { tx { callOriginal() } }
        }

    private val failOrderHandler =
        spyk(FailOrderCommandHandler(orderRepo, cursor, publisher, clock, registry)) {
            every { handle(any()) } answers { tx { callOriginal() } }
        }

    private val service = InventoryService(
        inventoryRepository = inventoryRepo,
        orderRepository = orderRepo,
        orderSagaRepository = sagaRepo,
        createInventoryItemCommandHandler = mockk<CreateInventoryItemCommandHandler>(),
        createOrderCommandHandler = mockk<CreateOrderCommandHandler>(),
        reserveOrderItemCommandHandler = ReserveOrderItemCommandHandler(inventoryRepo, stepWriter, clock, registry),
        releaseReservationCommandHandler = ReleaseReservationCommandHandler(inventoryRepo, stepWriter, clock, registry),
        failReservationCommandHandler = failReservationHandler,
        completeOrderCommandHandler = completeHandler,
        failOrderCommandHandler = failOrderHandler,
        orderWorkerExecutor = SyncTaskExecutor(),
        // Inline and instant: the backoff CURVE is InventoryServiceRetryTest's subject, and sleeping
        // here would only make this test slow.
        retryScheduler = { _, task -> task.run() },
        clock = clock,
        meterRegistry = registry,
    )

    private val saga = OrderReservationSaga(service)

    // ---- fixture ------------------------------------------------------------------------------
    private val correlationId: UUID = UUID.randomUUID()

    private fun seed(vararg stock: Pair<String, Int>) {
        stock.forEach { (id, qty) -> items[id] = InventoryItem(id = id, availableQty = qty) }
    }

    private fun placeOrder(vararg lines: Pair<String, Int>): OrderCreatedEvent {
        val ordered = lines.map { ReservedItem(it.first, it.second) }
        orders["ORDER-1"] = Order("ORDER-1", "USER-1", pl.szymanski.wiktor.domain.OrderItems(ordered))
        sagas["ORDER-1"] = OrderSaga(
            orderId = "ORDER-1",
            correlationId = correlationId,
            lines = SagaLines(ordered),
            startedAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
        )
        return OrderCreatedEvent("ORDER-1", "USER-1", ordered, correlationId, Instant.EPOCH)
    }

    /**
     * The outbox drain: hand every queued event back to the saga until nothing is left, exactly as
     * `@ApplicationModuleListener` deliveries would. Bounded, so a saga that fails to terminate
     * fails the test instead of hanging it.
     */
    private fun drain(seedEvent: Any) {
        outbox.addLast(seedEvent)
        var budget = 200
        while (outbox.isNotEmpty()) {
            assertTrue(budget-- > 0, "the saga never reached a terminal state; delivered $delivered")
            val event = outbox.removeFirst()
            delivered += event
            when (event) {
                is OrderCreatedEvent -> saga.on(event)
                is InventoryReservedEvent -> saga.on(event)
                is InventoryReservationFailedEvent -> saga.on(event)
                is InventoryReservationReleasedEvent -> saga.on(event)
                else -> Unit // OrderCompletedEvent / OrderFailedEvent reach the publisher, not the saga
            }
        }
    }

    private fun reservedLines() = delivered.filterIsInstance<InventoryReservedEvent>().map { it.lineIndex }
    private fun releasedLines() = delivered.filterIsInstance<InventoryReservationReleasedEvent>().map { it.lineIndex }

    // ---- the flows ----------------------------------------------------------------------------

    @Test
    fun `an order is reserved one line at a time, in order, and then confirmed`() {
        seed("ITEM-A" to 10, "ITEM-B" to 10, "ITEM-C" to 10)

        drain(placeOrder("ITEM-A" to 1, "ITEM-B" to 2, "ITEM-C" to 3))

        assertEquals(listOf(0, 1, 2), reservedLines(), "lines must be reserved in the order sent")
        assertEquals(OrderStatus.CONFIRMED, orders.getValue("ORDER-1").status)
        assertEquals(SagaStatus.ENDED, sagas.getValue("ORDER-1").status)
        assertEquals(9, items.getValue("ITEM-A").availableQty)
        assertEquals(8, items.getValue("ITEM-B").availableQty)
        assertEquals(7, items.getValue("ITEM-C").availableQty)
        assertEquals(3, reservations.size)
    }

    @Test
    fun `a line that is out of stock releases the lines already held, backwards, and rejects`() {
        // ITEM-C cannot satisfy line 2, and by then lines 0 and 1 have COMMITTED — which is the
        // whole difference from TO-3, where nothing had been written yet and a rollback sufficed.
        seed("ITEM-A" to 10, "ITEM-B" to 10, "ITEM-C" to 1)

        drain(placeOrder("ITEM-A" to 1, "ITEM-B" to 2, "ITEM-C" to 3))

        assertEquals(listOf(0, 1), reservedLines())
        assertEquals(listOf(1, 0), releasedLines(), "compensation must walk the reserved prefix backwards")
        assertEquals(OrderStatus.REJECTED, orders.getValue("ORDER-1").status)
        assertEquals(SagaStatus.ENDED, sagas.getValue("ORDER-1").status)
        // Every reservation given back: the stock is exactly as seeded.
        assertEquals(10, items.getValue("ITEM-A").availableQty)
        assertEquals(10, items.getValue("ITEM-B").availableQty)
        assertEquals(1, items.getValue("ITEM-C").availableQty)
        assertTrue(reservations.isEmpty(), "compensation must delete every reservation row: $reservations")
        assertEquals(
            1.0,
            registry.counter("orders.completed", "outcome", "rejected", "reason", "insufficient_stock").count(),
        )
    }

    @Test
    fun `an order whose very first line is out of stock is rejected with nothing to compensate`() {
        seed("ITEM-A" to 0)

        drain(placeOrder("ITEM-A" to 1))

        assertEquals(emptyList<Int>(), reservedLines())
        assertEquals(emptyList<Int>(), releasedLines())
        assertEquals(OrderStatus.REJECTED, orders.getValue("ORDER-1").status)
        assertEquals(SagaStatus.ENDED, sagas.getValue("ORDER-1").status)
    }

    @Test
    fun `a version conflict retries only the line that lost, not the whole order`() {
        // The difference from TO-3 in one assertion. There, a conflict on line 1 re-reads and
        // re-applies lines 0, 1 and 2; here line 0 stays committed and only line 1 runs again.
        seed("ITEM-A" to 10, "ITEM-B" to 10, "ITEM-C" to 10)
        conflictOnce += "ITEM-B"

        drain(placeOrder("ITEM-A" to 1, "ITEM-B" to 2, "ITEM-C" to 3))

        assertEquals(listOf(0, 1, 2), reservedLines(), "a conflict must not re-emit an already-reserved line")
        assertEquals(1.0, registry.counter("inventory.optimistic.retry").count())
        assertEquals(OrderStatus.CONFIRMED, orders.getValue("ORDER-1").status)
        assertEquals(8, items.getValue("ITEM-B").availableQty, "the retried line must be applied exactly once")
    }

    @Test
    fun `a redelivered step event does not reserve anything twice`() {
        // The crash window this branch is built around: a step's transaction committed but its
        // publication was not completed, so the event arrives again after a restart. The saga has
        // moved on, so the replay must re-run the CURRENT step and the cursor guard must make the
        // stale one a no-op.
        seed("ITEM-A" to 10, "ITEM-B" to 10)
        val created = placeOrder("ITEM-A" to 1, "ITEM-B" to 2)

        drain(created)
        val stateAfter = items.toMap()
        val reservedAfter = reservations.toSet()

        // Every event the run produced, delivered a second time — the worst case the republisher
        // can generate.
        delivered.toList().forEach { drain(it) }

        assertEquals(stateAfter, items, "a redelivery must not move stock")
        assertEquals(reservedAfter, reservations, "a redelivery must not add reservation rows")
        assertEquals(OrderStatus.CONFIRMED, orders.getValue("ORDER-1").status)
        assertEquals(
            1.0,
            registry.counter("orders.completed", "outcome", "confirmed", "reason", "none").count(),
            "the order's terminal outcome must be counted exactly once",
        )
    }

    @Test
    fun `the saga metrics mirror the ES branch's for a confirmed order`() {
        seed("ITEM-A" to 10)

        drain(placeOrder("ITEM-A" to 1))

        assertEquals(1.0, registry.counter("saga.completed", "outcome", "completed").count())
        assertEquals(1L, registry.find("saga.lifetime").tag("outcome", "completed").timer()!!.count())
        assertEquals(1L, registry.find("order.e2e.time").tag("outcome", "confirmed").timer()!!.count())
        assertEquals(0.0, registry.counter("saga.command.failed", "stage", "release").count())
    }

    @Test
    fun `state_persist_time is sampled once per step, not once per order`() {
        // The metric-semantics change this branch has to be read with in hand: TO-3 records one
        // sample per ORDER here. Four samples for a three-line order = three reserves plus the
        // confirm... which is NOT a step write, so it is three.
        seed("ITEM-A" to 10, "ITEM-B" to 10, "ITEM-C" to 10)

        drain(placeOrder("ITEM-A" to 1, "ITEM-B" to 1, "ITEM-C" to 1))

        val committed = registry.find("state_persist_time")
            .tag("source", "db_write").tag("outcome", "committed").timer()!!
        assertEquals(3L, committed.count())
    }
}
