package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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
 */
@Service
class ReserveItemCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val reservationRepo: ReservationRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
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
        val dbStartNs = System.nanoTime()
        val item = inventoryRepo.findById(command.itemId)
            .orElseThrow { NotFoundException("Item ${command.itemId} not found") }
        dbFetchTimer.record(System.nanoTime() - dbStartNs, TimeUnit.NANOSECONDS)

        val result = item.reserve(command.orderId, command.quantity, command.correlationId, clock)

        val dbWriteStartNs = System.nanoTime()
        inventoryRepo.save(result.updatedItem)
        reservationRepo.save(result.reservation)
        dbWriteTimer.record(System.nanoTime() - dbWriteStartNs, TimeUnit.NANOSECONDS)

        val outboxStartNs = System.nanoTime()
        applicationEventPublisher.publishEvent(result.event)
        outboxWriteTimer.record(System.nanoTime() - outboxStartNs, TimeUnit.NANOSECONDS)
    }
}
