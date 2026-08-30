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

data class ReleaseReservationCommand(
    val orderId: String,
    val lineIndex: Int,
    val correlationId: UUID,
)

/**
 * Compensation for one previously reserved line — the step TO-3 has no equivalent of, because
 * TO-3 never needs one.
 *
 * On TO-3 a failed order is undone by `ROLLBACK`: every line was reserved in the same transaction
 * as the line that failed, so nothing was ever visible and nothing has to be given back. Here the
 * earlier lines committed, minutes of wall time may separate them from the failure, and other orders
 * have been reading and reserving against the reduced stock in between. Undoing them is therefore a
 * business operation with its own transaction, its own event and its own failure modes — the
 * defining cost of the saga pattern, and the thing this handler exists to make measurable.
 *
 * The phase split, the retry classification and the four-statement write are identical to
 * [ReserveOrderItemCommandHandler]'s; only the direction differs. Compensation walks the reserved
 * prefix BACKWARDS (line k-1, then k-2, … then 0), one transaction per line, so a crash part way
 * through it resumes exactly where it stopped rather than restarting or double-releasing.
 *
 * There is no `reserveDelayMs` on this path and no stock check: a release restores state this same
 * item produced, so there is nothing to reject. The ES branch's `ReleaseReservationCommand` is the
 * same shape for the same reason.
 */
@Service
class ReleaseReservationCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val sagaStepWriter: SagaStepWriter,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val itemLoadTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .tag("aggregate", "InventoryItem")
        .register(meterRegistry)

    private val releasedCounter: Counter = meterRegistry.counter("inventory.release.success")

    /** [saga] is loaded by `InventoryService`; see [ReserveOrderItemCommandHandler.handle]. */
    fun handle(saga: OrderSaga, command: ReleaseReservationCommand): StepOutcome {
        val orderId = command.orderId
        val lineIndex = command.lineIndex

        // ---- Phase 1: read ---------------------------------------------------------------
        if (!saga.isReleaseStep(lineIndex)) {
            log.info(
                "[SAGA] skipping release orderId={} lineIndex={} saga is {} at {} correlationId={}",
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
        val result = item.release(
            reservationId = orderId,
            orderId = orderId,
            lineIndex = lineIndex,
            quantity = line.quantity,
            correlationId = saga.correlationId,
            clock = clock,
        )

        // ---- Phase 3: write --------------------------------------------------------------
        val applied = sagaStepWriter.writeRelease(
            ReleaseStepOutcome(
                orderId = orderId,
                lineIndex = lineIndex,
                restoredItem = result.updatedItem,
                reservationId = orderId,
                event = result.event,
            )
        )

        if (!applied) return StepOutcome.SKIPPED

        releasedCounter.increment()
        log.debug(
            "[SAGA] released itemId={} line {} orderId={} correlationId={}",
            line.itemId, lineIndex, orderId, command.correlationId,
        )
        return StepOutcome.APPLIED
    }
}
