package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.dao.OptimisticLockingFailureException
import pl.szymanski.wiktor.domain.OrderSaga
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.domain.SagaLines
import pl.szymanski.wiktor.exception.InsufficientStockException
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.OrderSagaRepository
import pl.szymanski.wiktor.service.command.CompleteOrderCommandHandler
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailReservationCommand
import pl.szymanski.wiktor.service.command.FailReservationCommandHandler
import pl.szymanski.wiktor.service.command.ReleaseReservationCommandHandler
import pl.szymanski.wiktor.service.command.ReserveOrderItemCommandHandler
import pl.szymanski.wiktor.service.command.StepOutcome
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Retry POLICY: 5 total attempts on the 25/50/100/200 ms curve, and the same counter arithmetic
 * TO-3 produced. Only the thread the waiting happens on is different, and that is
 * [OrderRetryUnblocksWorkerTest]'s job.
 *
 * **What the port from TO-3 changed, and what it did not.** The budget and the curve are identical,
 * because the branches are only comparable if they are. What is retried is not: TO-3 retries an
 * ORDER, re-reading and re-applying every line, and this retries the one LINE that conflicted.
 *
 * The terminal disposition changed with it. On TO-3 an exhausted order goes straight to
 * `FailOrderCommandHandler` — the reserve transaction rolled back, so there is nothing held and the
 * order can be rejected on the spot. Here it goes to `FailReservationCommandHandler`, which only
 * STARTS compensation; the rejection happens N releases later, driven by events, and is therefore
 * asserted in [OrderSagaCompensationTest] rather than here.
 *
 * The delays are asserted as WINDOWS rather than exact values, because this branch jitters each one
 * over [0.5 x base, 1.5 x base). What the loop must still guarantee is the attempt budget and the
 * curve those windows are centred on; the spread itself, and the fact that its mean IS the curve,
 * are [OrderRetryJitterTest]'s job.
 *
 * No Spring context: with the retry loop explicit rather than an AOP interceptor, the service is
 * just a constructor call. The scheduler here records each delay and runs the retry INLINE, so the
 * whole loop is deterministic and nothing sleeps.
 */
class InventoryServiceRetryTest {

    private val reserveOrderItemCommandHandler: ReserveOrderItemCommandHandler = mockk()
    private val failReservationCommandHandler: FailReservationCommandHandler = mockk(relaxed = true)
    private val failOrderCommandHandler: FailOrderCommandHandler = mockk(relaxed = true)
    private val sagaRepo: OrderSagaRepository = mockk()
    private val meterRegistry = SimpleMeterRegistry()
    private val scheduledDelaysMs = mutableListOf<Long>()

    private val saga = OrderSaga(
        orderId = "ORDER-1",
        correlationId = UUID.randomUUID(),
        lines = SagaLines(listOf(ReservedItem("ITEM-001", 1))),
        startedAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
    )

    private lateinit var inventoryService: InventoryService

    @BeforeEach
    fun setUp() {
        every { sagaRepo.findById("ORDER-1") } returns Optional.of(saga)
        inventoryService = InventoryService(
            inventoryRepository = mockk<InventoryRepository>(),
            orderRepository = mockk<OrderRepository>(),
            orderSagaRepository = sagaRepo,
            createInventoryItemCommandHandler = mockk<CreateInventoryItemCommandHandler>(),
            createOrderCommandHandler = mockk<CreateOrderCommandHandler>(),
            reserveOrderItemCommandHandler = reserveOrderItemCommandHandler,
            releaseReservationCommandHandler = mockk<ReleaseReservationCommandHandler>(relaxed = true),
            failReservationCommandHandler = failReservationCommandHandler,
            completeOrderCommandHandler = mockk<CompleteOrderCommandHandler>(relaxed = true),
            failOrderCommandHandler = failOrderCommandHandler,
            orderWorkerExecutor = SyncTaskExecutor(),
            // Records the delay and runs the retry inline, so the loop is deterministic. Which pool
            // the real scheduler puts it on is OrderRetryPoolTopologyTest's job.
            retryScheduler = { delayMs, task ->
                scheduledDelaysMs += delayMs
                task.run()
            },
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            meterRegistry = meterRegistry,
        )
    }

    private fun advance() = inventoryService.submitAdvance("ORDER-1", saga.correlationId, first = true)

    /**
     * One scheduled delay per retry, each inside its own attempt's jitter window. Asserting the
     * window rather than the value keeps this test deterministic without stubbing the RNG.
     */
    private fun assertDelaysOnCurve(expectedRetries: Int) {
        assertEquals(expectedRetries, scheduledDelaysMs.size, "wrong number of retries scheduled")
        scheduledDelaysMs.forEachIndexed { attempt, delay ->
            val base = OrderRetryPolicy.baseDelayMsFor(attempt)
            assertTrue(
                delay in (base / 2)..(base * 3 / 2),
                "retry $attempt waited ${delay}ms, outside [${base / 2}, ${base * 3 / 2}] for a ${base}ms base",
            )
        }
    }

    private fun retryCount() = meterRegistry.counter("inventory.optimistic.retry").count()
    private fun exhaustedCount() = meterRegistry.counter("inventory.optimistic.exhausted").count()

    @Test
    fun `a conflict is retried and the step applies on the second attempt`() {
        every { reserveOrderItemCommandHandler.handle(saga, any()) } throws
            OptimisticLockingFailureException("conflict") andThen StepOutcome.APPLIED

        advance()

        verify(exactly = 2) { reserveOrderItemCommandHandler.handle(saga, any()) }
        assertDelaysOnCurve(expectedRetries = 1)
        verify(exactly = 0) { failReservationCommandHandler.handle(any(), any()) }
        assertEquals(1.0, retryCount())
        assertEquals(0.0, exhaustedCount())
    }

    @Test
    fun `a persistent conflict is attempted five times on the unchanged backoff, then compensated`() {
        every { reserveOrderItemCommandHandler.handle(saga, any()) } throws
            OptimisticLockingFailureException("conflict")

        advance()

        verify(exactly = 5) { reserveOrderItemCommandHandler.handle(saga, any()) }
        assertDelaysOnCurve(expectedRetries = 4)
        // NOT FailOrderCommandHandler. Rejecting the order now would strand whatever earlier lines
        // this order already holds; compensation has to walk them back first.
        verify(exactly = 0) { failOrderCommandHandler.handle(any()) }
        verify(exactly = 1) {
            failReservationCommandHandler.handle(
                saga,
                match<FailReservationCommand> { it.reasonCode == "optimistic_exhausted" && it.lineIndex == 0 },
            )
        }
        // Identical to TO-3: every failed attempt counts as a retry, the last one included.
        assertEquals(5.0, retryCount())
        assertEquals(1.0, exhaustedCount())
    }

    @Test
    fun `accumulated backoff is priced once, at the step's terminal outcome`() {
        every { reserveOrderItemCommandHandler.handle(saga, any()) } throws
            OptimisticLockingFailureException("conflict")

        advance()

        val backoff = meterRegistry.timer("order.retry.backoff.time", "outcome", "failed")
        assertEquals(1L, backoff.count())
        // 375 ms is the curve's total and the jittered EXPECTATION; one step draws somewhere in
        // [187.5, 562.5]. What must hold is that the recorded figure is the sum of the delays
        // actually scheduled, not the nominal curve.
        assertEquals(
            scheduledDelaysMs.sum().toDouble(),
            backoff.totalTime(TimeUnit.MILLISECONDS),
            "backoff must price the jittered waits, not the curve",
        )
    }

    @Test
    fun `a business rejection is not retried`() {
        every { reserveOrderItemCommandHandler.handle(saga, any()) } throws
            InsufficientStockException("out of stock")

        advance()

        verify(exactly = 1) { reserveOrderItemCommandHandler.handle(saga, any()) }
        assertEquals(emptyList<Long>(), scheduledDelaysMs)
        verify(exactly = 1) {
            failReservationCommandHandler.handle(
                saga,
                match<FailReservationCommand> { it.reasonCode == "insufficient_stock" },
            )
        }
        assertEquals(0.0, retryCount())
        assertEquals(0.0, exhaustedCount())
    }
}
