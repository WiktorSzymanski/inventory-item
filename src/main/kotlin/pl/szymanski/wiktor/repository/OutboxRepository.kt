package pl.szymanski.wiktor.repository

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Persistable
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.data.relational.core.mapping.Table
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import java.time.OffsetDateTime
import java.util.UUID

@Table("outbox")
data class OutboxEntry(
    @Id
    @get:JvmName("getEntryId")
    val id: UUID = UUID.randomUUID(),
    val aggregateId: String,
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String,
    val payloadJson: String,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val publishedAt: OffsetDateTime? = null,
    val status: String = "PENDING",
    val attemptCount: Int = 0,
    val lastError: String? = null,
) : Persistable<UUID> {
    // Body property (excluded from data class equals/hashCode) — always true because
    // OutboxEntry is only ever saved once on creation; updates go through @Query methods.
    @Transient private val _isNew: Boolean = true

    override fun getId(): UUID = id
    override fun isNew(): Boolean = _isNew
}

@Repository
interface OutboxRepository : R2dbcRepository<OutboxEntry, UUID> {

    fun findAllByStatus(status: String, pageable: Pageable): Flux<OutboxEntry>

    @Query("""
        UPDATE outbox
        SET status = 'PUBLISHED', published_at = now(), attempt_count = attempt_count + 1
        WHERE id = :id
    """)
    suspend fun markPublished(id: UUID): Int
}
