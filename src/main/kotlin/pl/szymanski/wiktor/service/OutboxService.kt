package pl.szymanski.wiktor.service

import com.fasterxml.jackson.databind.ObjectMapper
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
) {

    suspend fun insertEntry(aggregateId: String, eventType: String, payload: Any) {
        val payloadJson = objectMapper.writeValueAsString(payload)
        val entry = OutboxEntry(
            aggregateId = aggregateId,
            eventType = eventType,
            payloadJson = payloadJson
        )
        repository.save(entry).awaitSingle()
    }

    suspend fun pollPending(batchSize: Int): List<OutboxEntry> =
        repository.findAllByStatus(
            "PENDING",
            PageRequest.of(0, batchSize, Sort.by("createdAt").ascending()),
        ).collectList().awaitSingle()

    suspend fun markPublished(id: UUID): Int =
        repository.markPublished(id)
}