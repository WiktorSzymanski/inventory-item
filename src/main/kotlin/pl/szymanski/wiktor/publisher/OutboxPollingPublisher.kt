package pl.szymanski.wiktor.publisher

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Synchronous, once-only outbox publisher.
 *
 * Each tick repeatedly claims a batch of not-yet-completed publications with FOR UPDATE SKIP LOCKED,
 * publishes each one synchronously, and stamps completion_date in the same transaction. Because
 * completion is committed before the row's lock is released, a row that has been delivered is never
 * re-selected by a later tick (or a concurrent instance) — so every event is published exactly once,
 * unlike the previous resubmitIncompletePublicationsOlderThan(ZERO) which re-dispatched the entire
 * incomplete backlog every poll.
 */
@Component
class OutboxPollingPublisher(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val serializer: EventSerializer,
    private val eventListener: InventoryEventListener,
    @Value("\${app.outbox.poll-batch-size:500}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    private data class Pending(val id: UUID, val eventType: String, val serializedEvent: String)

    @Scheduled(fixedDelayString = "\${spring.modulith.events.polling-interval:PT10S}")
    fun drain() {
        while (true) {
            val processed = transactionTemplate.execute { publishBatch() } ?: 0
            if (processed < batchSize) break
        }
    }

    private fun publishBatch(): Int {
        val pending = jdbcTemplate.query(
            """
            SELECT id, event_type, serialized_event
            FROM event_publication
            WHERE completion_date IS NULL
            ORDER BY publication_date
            FOR UPDATE SKIP LOCKED
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                Pending(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("event_type"),
                    rs.getString("serialized_event"),
                )
            },
            batchSize,
        )
        if (pending.isEmpty()) return 0

        val completedAt = Timestamp.from(Instant.now())
        for (row in pending) {
            val event = serializer.deserialize(row.serializedEvent, Class.forName(row.eventType))
            eventListener.dispatch(event)
            jdbcTemplate.update(
                "UPDATE event_publication SET completion_date = ? WHERE id = ?",
                completedAt, row.id,
            )
        }
        return pending.size
    }
}
