package pl.szymanski.wiktor.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.springframework.core.task.TaskExecutor
import pl.szymanski.wiktor.domain.OrderSaga
import pl.szymanski.wiktor.domain.ReservedItem
import pl.szymanski.wiktor.domain.SagaLines
import pl.szymanski.wiktor.domain.SagaStatus
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.repository.OrderRepository
import pl.szymanski.wiktor.repository.OrderSagaRepository
import pl.szymanski.wiktor.service.command.CompleteOrderCommandHandler
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailOrderCommandHandler
import pl.szymanski.wiktor.service.command.FailReservationCommandHandler
import pl.szymanski.wiktor.service.command.ReleaseReservationCommandHandler
import pl.szymanski.wiktor.service.command.ReserveOrderItemCommandHandler
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * A wired [InventoryService] over mock collaborators, so the tests that are about the SERVICE — the
 * retry topology, the step machine — do not each rebuild an eight-argument constructor and drift
 * apart as it changes.
 *
 * The saga repository is stubbed from an in-memory map rather than relaxed, because on this branch
 * the saga row is what decides which step runs: a relaxed mock returning `Optional.empty()` would
 * make every test fail in the same uninformative way, at the load, before reaching what it is
 * actually asserting.
 */
internal class SagaServiceHarness(
    val executor: TaskExecutor,
    val retryScheduler: OrderRetryScheduler,
    val registry: MeterRegistry = SimpleMeterRegistry(),
    val clock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
) {
    /** The pool topology in miniature: one executor serving first attempts and retries alike. */
    constructor(pool: OrderWorkerPool, registry: MeterRegistry = SimpleMeterRegistry()) :
        this(pool, OrderRetryScheduler { delayMs, task -> pool.schedule(delayMs, task) }, registry)

    private val sagas = LinkedHashMap<String, OrderSaga>()

    val sagaRepo: OrderSagaRepository = mockk<OrderSagaRepository>().also { repo ->
        every { repo.findById(any()) } answers { Optional.ofNullable(sagas[firstArg()]) }
    }

    val reserveHandler: ReserveOrderItemCommandHandler = mockk()
    val releaseHandler: ReleaseReservationCommandHandler = mockk(relaxed = true)
    val failReservationHandler: FailReservationCommandHandler = mockk(relaxed = true)
    val completeHandler: CompleteOrderCommandHandler = mockk(relaxed = true)
    val failOrderHandler: FailOrderCommandHandler = mockk(relaxed = true)

    val service = InventoryService(
        inventoryRepository = mockk<InventoryRepository>(),
        orderRepository = mockk<OrderRepository>(),
        orderSagaRepository = sagaRepo,
        createInventoryItemCommandHandler = mockk<CreateInventoryItemCommandHandler>(),
        createOrderCommandHandler = mockk<CreateOrderCommandHandler>(),
        reserveOrderItemCommandHandler = reserveHandler,
        releaseReservationCommandHandler = releaseHandler,
        failReservationCommandHandler = failReservationHandler,
        completeOrderCommandHandler = completeHandler,
        failOrderCommandHandler = failOrderHandler,
        orderWorkerExecutor = executor,
        retryScheduler = retryScheduler,
        clock = clock,
        meterRegistry = registry,
    )

    /** Registers a saga the service will find, and returns it. */
    fun givenSaga(
        orderId: String,
        lineCount: Int = 1,
        currentIndex: Int = 0,
        status: SagaStatus = SagaStatus.RUNNING,
        failureReason: String? = null,
        failureCode: String? = null,
    ): OrderSaga {
        val saga = OrderSaga(
            orderId = orderId,
            correlationId = UUID.randomUUID(),
            lines = SagaLines((0 until lineCount).map { ReservedItem("ITEM-$it", 1) }),
            currentIndex = currentIndex,
            status = status,
            failureReason = failureReason,
            failureCode = failureCode,
            startedAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
        )
        sagas[orderId] = saga
        return saga
    }
}
