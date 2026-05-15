package pl.szymanski.wiktor.service

import io.kurrent.dbclient.WrongExpectedVersionException
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.exception.OptimisticLockExhaustedException
import pl.szymanski.wiktor.repository.InventoryProjection
import pl.szymanski.wiktor.repository.InventoryRepository
import pl.szymanski.wiktor.service.command.CreateInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.CreateItemCommand
import pl.szymanski.wiktor.service.command.ReserveInventoryItemCommandHandler
import pl.szymanski.wiktor.service.command.ReserveItemCommand
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

@Service
class InventoryService(
    private val inventoryRepository: InventoryRepository,
    private val createInventoryItemCommandHandler: CreateInventoryItemCommandHandler,
    private val reserveInventoryItemCommandHandler: ReserveInventoryItemCommandHandler,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun createItem(command: CreateItemCommand): InventoryItem =
        withOptimisticRetry(command.correlationId.toString()) { createInventoryItemCommandHandler.handle(command) }

    suspend fun getItem(itemId: String): InventoryProjection? =
        inventoryRepository.findById(itemId).awaitSingleOrNull()

    suspend fun getItems(pageable: Pageable): Page<InventoryProjection> {
        val items = inventoryRepository.findAllBy(pageable).collectList().awaitSingle()
        val total = inventoryRepository.count().awaitSingle()
        return PageImpl(items, pageable, total)
    }

    suspend fun reserveItem(command: ReserveItemCommand) =
        withOptimisticRetry(command.correlationId.toString()) { reserveInventoryItemCommandHandler.handle(command) }

    suspend fun <T> withOptimisticRetry(
        correlationId: String,
        maxAttempts: Int = 5,
        initialBackoffMs: Long = 25,
        maxBackoffMs: Long = 500,
        operation: suspend () -> T,
    ): T {
        var attempt = 0
        var backoffMs = initialBackoffMs
        var lastError: WrongExpectedVersionException? = null

        while (attempt < maxAttempts) {
            try {
                return operation()
            } catch (e: WrongExpectedVersionException) {
                log.info("[RETRY] attempt={} failed for correlationId={}", attempt, correlationId)
                meterRegistry.counter("inventory.optimistic.retry").increment()

                lastError = e
                attempt++

                if (attempt >= maxAttempts) break

                val jitterMs = Random.nextLong(0, backoffMs / 2 + 1)
                delay((backoffMs + jitterMs).milliseconds)
                backoffMs = min(backoffMs * 2, maxBackoffMs)
            }
        }

        meterRegistry.counter("inventory.optimistic.exhausted").increment()
        log.warn("[RETRY] exhausted correlationId={} attempts={}", correlationId, maxAttempts)
        throw OptimisticLockExhaustedException(
            "Optimistic lock retries exhausted after $maxAttempts attempts"
        ).also { it.initCause(lastError) }
    }
}
