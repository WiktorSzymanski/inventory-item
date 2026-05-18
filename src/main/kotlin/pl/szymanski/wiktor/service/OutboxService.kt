package pl.szymanski.wiktor.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.repository.OutboxEntry
import pl.szymanski.wiktor.repository.OutboxRepository
import java.util.UUID

@Service
class OutboxService(
    private val repository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
) {
    private val appendSuccessCounter: Counter = meterRegistry.counter("inventory.append.success")

    suspend fun insertEntry(aggregateId: String, eventType: String, payload: Any) {
        val payloadJson = objectMapper.writeValueAsString(payload)
        val entry = OutboxEntry(
            aggregateId = aggregateId,
            eventType = eventType,
            payloadJson = payloadJson
        )
        repository.save(entry).awaitSingle()
        appendSuccessCounter.increment()
    }

    suspend fun pollPending(batchSize: Int): List<OutboxEntry> =
        repository.findAllByStatus(
            "PENDING",
            PageRequest.of(0, batchSize, Sort.by("createdAt").ascending()),
        ).collectList().awaitSingle()

    suspend fun markPublished(id: UUID): Int =
        repository.markPublished(id)
}
