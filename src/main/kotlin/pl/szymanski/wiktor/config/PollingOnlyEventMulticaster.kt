package pl.szymanski.wiktor.config

import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.core.ResolvableType
import org.springframework.core.env.Environment
import org.springframework.modulith.events.core.EventPublicationRegistry
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import org.springframework.modulith.events.support.PersistentApplicationEventMulticaster
import java.time.Instant
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalApplicationListener
import java.util.function.Supplier

/**
 * Stores event publications in the DB atomically with the business transaction but never invokes
 * AFTER_COMMIT listeners synchronously. All delivery is handled exclusively by OutboxPollingPublisher,
 * which claims incomplete rows with FOR UPDATE SKIP LOCKED and publishes each exactly once.
 *
 * Storage goes through [EventPublicationRepository.create] directly, bypassing
 * [DefaultEventPublicationRegistry.store]. That higher-level method adds every publication to an
 * in-memory PublicationsInProgress map keyed by object identity. In the polling-only model the
 * event is later deserialized from JSON into a new object, so the original key is never matched and
 * the entry would never be cleaned up. The repository layer has no such map.
 *
 * The parent's storePublications() and getEventToPersist() are private so their one-liner logic
 * is replicated here inline.
 *
 * Being the ONLY writer of event_publication rows also makes this the only place that can raise the
 * outbox notification from application code, which is what [OutboxNotifier] is called for below.
 * The supplier is lazy for the same reason the other three are: this bean is built inside a
 * BeanDefinitionRegistryPostProcessor, before OutboxNotifier and its JdbcTemplate exist.
 */
class PollingOnlyEventMulticaster(
    registrySupplier: Supplier<EventPublicationRegistry>,
    private val repositorySupplier: Supplier<EventPublicationRepository>,
    environmentSupplier: Supplier<Environment>,
    private val notifierSupplier: Supplier<OutboxNotifier>,
) : PersistentApplicationEventMulticaster(registrySupplier, environmentSupplier) {

    @Suppress("UNCHECKED_CAST")
    override fun multicastEvent(event: ApplicationEvent, eventType: ResolvableType?) {
        val type = eventType ?: ResolvableType.forInstance(event)
        val listeners = getApplicationListeners(event, type)

        if (listeners.isEmpty()) return

        // Replicates parent's private getEventToPersist()
        val eventToPersist: Any = if (event is PayloadApplicationEvent<*>) event.payload else event

        val afterCommitListeners = listeners
            .filterIsInstance<TransactionalApplicationListener<*>>()
            .filter { it.transactionPhase == TransactionPhase.AFTER_COMMIT }

        if (afterCommitListeners.isNotEmpty()) {
            val now = Instant.now()
            val repository = repositorySupplier.get()
            afterCommitListeners.forEach { listener ->
                repository.create(
                    TargetEventPublication.of(eventToPersist, PublicationTargetIdentifier.of(listener.listenerId), now)
                )
            }

            // ONE notification for the rows just written, and at most one for the whole
            // transaction however many times it lands here. Deliberately inside this branch: an
            // event with no AFTER_COMMIT listener wrote nothing, and every framework event during
            // context startup is such an event — resolving the supplier for one of those would
            // force OutboxNotifier into existence mid-refresh for a notification with no rows
            // behind it.
            notifierSupplier.get().notifyOnCommit()
        }

        // Invoke only non-AFTER_COMMIT listeners immediately (framework events, BEFORE_COMMIT, etc.)
        // AFTER_COMMIT listeners are intentionally skipped — the scheduler drives their invocation.
        for (listener in listeners) {
            (listener as ApplicationListener<ApplicationEvent>)
            if (listener !is TransactionalApplicationListener<*> ||
                listener.transactionPhase != TransactionPhase.AFTER_COMMIT
            ) {
                listener.onApplicationEvent(event)
            }
        }
    }
}
