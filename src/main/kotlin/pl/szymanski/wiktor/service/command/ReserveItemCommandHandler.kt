package pl.szymanski.wiktor.service.command

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.szymanski.wiktor.exception.NotFoundException
import pl.szymanski.wiktor.repository.InventoryRepository
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ReserveItemCommand(
    val id: String,
    val reservationId: String,
    val quantity: Int,
    val correlationId: UUID = UUID.randomUUID(),
)

@Service
class ReserveInventoryItemCommandHandler(
    private val inventoryRepo: InventoryRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val dbFetchTimer: Timer = Timer.builder("state_load_time")
        .tag("source", "db_fetch")
        .register(meterRegistry)
    private val appendSuccessCounter: Counter = meterRegistry.counter("inventory.append.success")

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = [Exception::class])
    suspend fun handle(command: ReserveItemCommand): String {
        log.info("[RESERVE] itemId={} reservationId={} quantity={} correlationId={}", command.id, command.reservationId, command.quantity, command.correlationId)
        val dbStartNs = System.nanoTime()
        val found = inventoryRepo.findById(command.id).awaitSingleOrNull()
        dbFetchTimer.record(System.nanoTime() - dbStartNs, TimeUnit.NANOSECONDS)

        val (item, event) = found?.reserve(command.reservationId, command.quantity, command.correlationId)
            ?: throw NotFoundException("Item ${command.id} not found")

        inventoryRepo.save(item).awaitSingle()
        applicationEventPublisher.publishEvent(event)
        appendSuccessCounter.increment()
        log.info("[RESERVE] success itemId={} reservationId={} correlationId={}", command.id, command.reservationId, command.correlationId)
        return command.reservationId
    }
}
