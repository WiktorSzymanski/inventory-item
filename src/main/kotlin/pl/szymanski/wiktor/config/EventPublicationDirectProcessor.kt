package pl.szymanski.wiktor.config

import org.slf4j.LoggerFactory
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single delivery path shared by the NOTIFY listener and the backup poller.
 *
 * The claim UPDATE and the listener invocation run in one transaction: a listener failure rolls
 * the claim back, leaving the row incomplete for the backup poller (at-least-once). The claim
 * doubles as an idempotency guard — whichever path claims a row first delivers it; the other sees
 * 0 updated rows and skips, so concurrent delivery attempts are harmless.
 *
 * The listener target is invoked directly, bypassing the bean proxy: @ApplicationModuleListener is
 * meta-annotated @Async, so a proxied call would return immediately and escape this transaction,
 * silently turning the semantics back into at-most-once.
 */
@Component
class EventPublicationDirectProcessor(
    private val jdbcTemplate: JdbcTemplate,
    private val eventSerializer: EventSerializer,
    private val applicationContext: ApplicationContext,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private data class ListenerInvoker(val target: Any, val method: Method)

    /** A publication's id and its position, so the drain can advance its cursor from a page. */
    data class PublicationRef(val id: UUID, val seq: Long)

    private val invokers = ConcurrentHashMap<String, ListenerInvoker>()

    @Transactional
    fun process(publicationId: UUID) {
        val claimed = jdbcTemplate.update(
            """UPDATE event_publication
               SET completion_date = ?, status = 'COMPLETED'
               WHERE id = ? AND completion_date IS NULL""",
            OffsetDateTime.now(), publicationId,
        )
        if (claimed == 0) {
            log.debug("Publication {} already delivered by another path, skipping", publicationId)
            return
        }

        val row = jdbcTemplate.queryForMap(
            "SELECT event_type, serialized_event, listener_id FROM event_publication WHERE id = ?",
            publicationId,
        )
        val eventType = Class.forName(row["event_type"] as String)
        val event = eventSerializer.deserialize(row["serialized_event"] as String, eventType)
        val invoker = invokers.computeIfAbsent(row["listener_id"] as String) { resolveInvoker(it, eventType) }

        try {
            invoker.method.invoke(invoker.target, event)
        } catch (e: InvocationTargetException) {
            throw e.targetException as? RuntimeException ?: RuntimeException(e.targetException)
        }
        log.debug("Publication {} delivered and marked COMPLETED", publicationId)
    }

    fun findIncompleteIds(olderThan: Duration): List<UUID> =
        jdbcTemplate.queryForList(
            """SELECT id FROM event_publication
               WHERE completion_date IS NULL AND publication_date < now() - make_interval(secs => ?)""",
            UUID::class.java,
            olderThan.toMillis() / 1000.0,
        ).filterNotNull()

    /**
     * One bounded page of incomplete publications, for [EventDrainLoop]'s paged drain.
     *
     * Deliberately unordered. Completed rows are never deleted, so `ORDER BY publication_date`
     * would make every page rescan the growing prefix of already-delivered rows — millions of
     * them by the end of a load run. Without it the planner can go straight at
     * `completion_date IS NULL` via idx_event_publication_completion_date. Nothing is lost:
     * delivery is already concurrent across the pool, so no ordering was ever guaranteed.
     */
    fun findIncompleteIds(olderThan: Duration, limit: Int): List<UUID> =
        jdbcTemplate.queryForList(
            """SELECT id FROM event_publication
               WHERE completion_date IS NULL AND publication_date < now() - make_interval(secs => ?)
               LIMIT ?""",
            UUID::class.java,
            olderThan.toMillis() / 1000.0,
            limit,
        ).filterNotNull()

    /**
     * One page of publications after the drain's cursor — the cursor path's only read.
     *
     * The whole change is in `seq > ?`. [findIncompleteIds] has to prove that nothing is
     * undelivered by walking the entire `completion_date IS NULL` region, which on a branch that
     * keeps up is a walk over nothing but the dead entries its own completions left behind. This
     * query starts PAST that region: everything below the cursor, live or dead, is never touched.
     *
     * `ORDER BY seq` is required here and was forbidden on [findIncompleteIds] — for a reason that
     * does not apply. There, `ORDER BY publication_date` made every page rescan a growing prefix of
     * already-delivered rows. Here the cursor moves the scan's START, so the ordered read is a
     * forward walk of idx_event_publication_seq_incomplete and rescans nothing.
     *
     * `completion_date IS NULL` is carried so the planner can use that PARTIAL index, and it also
     * skips rows the sweep already delivered. It is not a correctness guard: [process] claims each
     * row atomically, so a row completed between this read and its delivery is skipped there.
     */
    fun findAfterCursor(cursor: Long, limit: Int): List<PublicationRef> =
        jdbcTemplate.query(
            """SELECT id, seq FROM event_publication
               WHERE seq > ? AND completion_date IS NULL
               ORDER BY seq
               LIMIT ?""",
            RowMapper { rs, _ -> PublicationRef(rs.getObject("id", UUID::class.java), rs.getLong("seq")) },
            cursor,
            limit,
        )

    /**
     * One page of publications the cursor has already passed but that were never delivered — the
     * sweep's only read.
     *
     * These exist by design, not by accident. A publication's `seq` is assigned when its row is
     * INSERTed, and on the reserve path that INSERT is statement 1 of
     * `OrderWriteCommandHandler.write` while the `inventory_state` row locks are taken by statement
     * 4. So a transaction can hold a low seq and commit long after a higher one — sequence order is
     * not commit order, and the drain, which advances to the highest seq it has SEEN, moves past
     * rows that were not yet visible. A delivery that throws lands here too: the claim rolls back
     * with it, and the drain's cursor advances anyway.
     *
     * Bounded by [limit] because that set is no longer the rare leftover the unbounded
     * [findIncompleteIds] was written for.
     */
    fun findIncompleteUpTo(cursor: Long, minAge: Duration, limit: Int): List<UUID> =
        jdbcTemplate.queryForList(
            """SELECT id FROM event_publication
               WHERE completion_date IS NULL AND seq <= ?
                 AND publication_date < now() - make_interval(secs => ?)
               ORDER BY seq
               LIMIT ?""",
            UUID::class.java,
            cursor,
            minAge.toMillis() / 1000.0,
            limit,
        ).filterNotNull()

    // listener_id format: "full.ClassName.methodName(full.ParamType)"
    private fun resolveInvoker(listenerId: String, eventType: Class<*>): ListenerInvoker {
        val withoutParams = listenerId.substringBefore("(")
        val className = withoutParams.substringBeforeLast(".")
        val methodName = withoutParams.substringAfterLast(".")

        val bean = applicationContext.getBean(Class.forName(className))
        val target = AopProxyUtils.getSingletonTarget(bean) ?: bean
        val method = target.javaClass.methods.first {
            it.name == methodName && it.parameterCount == 1
                && it.parameterTypes[0].isAssignableFrom(eventType)
        }
        return ListenerInvoker(target, method)
    }
}
