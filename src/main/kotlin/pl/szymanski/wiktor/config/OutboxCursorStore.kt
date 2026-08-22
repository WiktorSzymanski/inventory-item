package pl.szymanski.wiktor.config

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * The drain's position in `event_publication.seq`, persisted in the one-row `outbox_cursor` table.
 *
 * Deliberately NOT transactional. The cursor is not part of the delivery transaction and must not
 * be: [EventPublicationDirectProcessor.process] commits each delivery on its own, on a pool thread,
 * while the cursor is written once per page by the scheduler thread running the poll tick. Joining
 * them would put a single-row hot spot inside every delivery transaction and serialise the pool on
 * it.
 *
 * The consequence is that a crash between delivering a page and writing the cursor replays that
 * page. That is harmless and already the branch's model: the claim `UPDATE ... WHERE completion_date
 * IS NULL` matches 0 rows for an already-delivered publication and the caller skips, exactly as it
 * does when the sweep and the drain race for the same row.
 *
 * This is the same job Axon's TrackingToken does for a PooledStreamingEventProcessor, and naming
 * that is the point of the exercise: what separates the two families is not push versus poll, it is
 * whether "the next undelivered event" is answered by a POSITION or by a SEARCH.
 */
@Component
class OutboxCursorStore(private val jdbcTemplate: JdbcTemplate) {

    /** 0 on a fresh database, which makes the first drain start from the beginning of the table. */
    fun load(): Long =
        jdbcTemplate.queryForObject("SELECT position FROM outbox_cursor WHERE id = 1", Long::class.java) ?: 0L

    /**
     * `GREATEST` rather than a bare assignment: the cursor must never move backwards. Nothing in
     * the current topology can make it (one drain thread, one writer), but a cursor that can go
     * backwards silently redelivers, and the guard costs nothing.
     */
    fun save(position: Long) {
        jdbcTemplate.update(
            "UPDATE outbox_cursor SET position = GREATEST(position, ?), updated_at = now() WHERE id = 1",
            position,
        )
    }
}
