package pl.szymanski.wiktor.config

import org.slf4j.LoggerFactory
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
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
 * Single delivery path, driven on this branch by [pl.szymanski.wiktor.publisher.OutboxPollingPublisher]
 * (TO-2 additionally feeds it from a NOTIFY listener).
 *
 * The claim UPDATE and the listener invocation run in one transaction: a listener failure rolls
 * the claim back, leaving the row incomplete for the next poll (at-least-once). The claim
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
               WHERE completion_date IS NULL AND publication_date < now() - make_interval(secs => ?)
               ORDER BY publication_date""",
            UUID::class.java,
            olderThan.toMillis() / 1000.0,
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
