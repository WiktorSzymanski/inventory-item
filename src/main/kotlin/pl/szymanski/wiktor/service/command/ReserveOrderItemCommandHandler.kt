package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.OrderSaga
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ReserveOrderItemCommand(
    val orderId: String,
    val lineIndex: Int,
    val correlationId: UUID,
)

/** What a saga step did. Distinguishes "written" from "somebody already wrote it". */
enum class StepOutcome { APPLIED, SKIPPED }

/**
 * ONE LINE of an order, reserved on its own.
 *
 * This is the whole difference between TO-3-Saga and TO-3, and it is worth being precise about what
 * did and did not change.
 *
 * **What changed: the unit of work.** TO-3's `ReserveOrderItemsCommandHandler` (plural) read every
 * line's row in one `findAllById`, applied all of them to an in-memory working copy, and handed the
 * lot to one write transaction — so an order was atomic, and the only thing that could be observed
 * between "PENDING" and "CONFIRMED" was nothing. Here the order is N transactions. Its partial state
 * is visible to every concurrent reader, its stock is genuinely held while the rest of the lines are
 * still being attempted, and undoing it takes compensation rather than a rollback. That is not a
 * regression; it is the saga, and it is what the branch exists to price.
 *
 * **What did NOT change: the phase split.** The three phases are exactly TO-3's, one line wide:
 *
 *  1. **Read** — the saga row and the line's inventory row. No transaction.
 *  2. **Modify** — `InventoryItem.reserve` against the loaded copy. No transaction, no connection,
 *     no row lock, and this is still where `reserveDelayMs` is paid and where
 *     `InsufficientStockException` is thrown with nothing to roll back.
 *  3. **Write** — [SagaStepWriter.writeReserve], four statements and nothing else.
 *
 * Keeping that split is what makes the A/B against TO-3 read as "one transaction per order vs one
 * per line" rather than as two changes at once.
 *
 * **On conflicts.** A version conflict is still an `OptimisticLockingFailureException` retried by
 * `InventoryService`, but the retry is now one LINE, not the order. TO-3 re-reads and re-applies
 * every line — and re-pays every line's `reserveDelayMs` — when any one of them loses; this repeats
 * only the line that lost. Against that, an order now presents N independent opportunities to
 * conflict instead of one, so whether total retry work goes up or down is a measurement, not a
 * prediction.
 */
@Service
class ReserveOrderItemCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val sagaStepWriter: SagaStepWriter,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    // Same name and tags as TO-3, but ONE SAMPLE PER LINE against TO-3's one per order (a single
    // findAllById there). The saga row's own read is timed by InventoryService, which performs it:
    // it happens once per step and serves both this handler and the decision of which step to run,
    // so timing it here as well would double-count it.
    private val itemLoadTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .tag("aggregate", "InventoryItem")
        .register(meterRegistry)

    private val appendSuccessCounter: Counter = meterRegistry.counter("inventory.append.success")

    /**
     * [saga] is passed in already loaded rather than read here: `InventoryService` has to read it
     * anyway to decide WHICH step to run, and reading it twice per step would put a round trip on
     * the hot path for nothing. It is a snapshot from outside any transaction and may be stale by
     * the time the write happens — [SagaCursorWriter] is what makes that safe.
     */
    fun handle(saga: OrderSaga, command: ReserveOrderItemCommand): StepOutcome {
        val orderId = command.orderId
        val lineIndex = command.lineIndex

        // ---- Phase 1: read ---------------------------------------------------------------
        // The cheap guard. It is NOT the correctness guard — this read is outside any transaction,
        // so the saga can move between here and the write. [SagaCursorWriter] re-asserts the same
        // predicate as part of the write, and that one is authoritative. This exists to skip the
        // item read and the reserveDelayMs sleep for a redelivery that is obviously stale.
        if (!saga.isReserveStep(lineIndex)) {
            log.info(
                "[SAGA] skipping reserve orderId={} lineIndex={} saga is {} at {} correlationId={}",
                orderId, lineIndex, saga.status, saga.currentIndex, command.correlationId,
            )
            return StepOutcome.SKIPPED
        }

        val line = saga.lineAt(lineIndex)

        val itemStartNs = System.nanoTime()
        val loaded = inventoryRepo.findById(line.itemId)
        itemLoadTimer.record(System.nanoTime() - itemStartNs, TimeUnit.NANOSECONDS)
        val item = loaded.orElseThrow { NotFoundException("Item ${line.itemId} not found") }

        // ---- Phase 2: modify (no transaction, no connection, no lock) ---------------------
        val result = item.reserve(
            reservationId = orderId,
            orderId = orderId,
            lineIndex = lineIndex,
            quantity = line.quantity,
            correlationId = saga.correlationId,
            clock = clock,
        )

        // ---- Phase 3: write --------------------------------------------------------------
        val applied = sagaStepWriter.writeReserve(
            ReserveStepOutcome(
                orderId = orderId,
                lineIndex = lineIndex,
                updatedItem = result.updatedItem,
                reservation = result.reservation,
                event = result.event,
            )
        )

        if (!applied) return StepOutcome.SKIPPED

        appendSuccessCounter.increment()
        log.debug(
            "[SAGA] reserved itemId={} ({}/{}) orderId={} correlationId={}",
            line.itemId, lineIndex + 1, saga.lineCount, orderId, command.correlationId,
        )
        return StepOutcome.APPLIED
    }

}
