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
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.function.Supplier

/**
 * Stores event publications in the DB atomically with the business transaction but never invokes
 * AFTER_COMMIT listeners synchronously. All delivery is handled exclusively by the polling scheduler
 * via [resubmitIncompletePublicationsOlderThan].
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
 * This is also the only place in the codebase that writes `event_publication` rows, which makes it
 * the only place that can signal [OutboxNotifyCoalescer] — see [signalAfterCommit]. `notifierSupplier`
 * is lazy for the same reason the other three are: resolving it for a framework event with no
 * AFTER_COMMIT listener attached would force the coalescer, and the standalone JDBC connection its
 * flush loop opens, into existence part-way through context startup.
 */
class PollingOnlyEventMulticaster(
    registrySupplier: Supplier<EventPublicationRegistry>,
    private val repositorySupplier: Supplier<EventPublicationRepository>,
    environmentSupplier: Supplier<Environment>,
    private val notifierSupplier: Supplier<OutboxNotifyCoalescer>,
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
            signalAfterCommit()
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

    /**
     * Signals [OutboxNotifyCoalescer] only once this transaction has actually committed — never
     * from `beforeCommit`, and never unconditionally. A transaction that rolls back (an optimistic
     * lock conflict on `inventory_state`, which this branch's own runs show is not rare) must never
     * raise a wake-up for a row that does not exist; registering on `afterCommit` makes that
     * impossible by construction rather than relying on `pg_notify`'s own queued-in-the-transaction
     * behaviour, which this class no longer calls at all.
     *
     * No per-transaction dedupe marker, unlike the eager-call design this replaces: [OutboxNotifyCoalescer.signal]
     * is an idempotent, lock-free flag write, so registering it once per event (up to six times for
     * one order) costs nothing worth guarding against — the marker existed there to avoid repeat
     * JDBC round trips, and there are none here to avoid.
     */
    private fun signalAfterCommit() {
        val notifier = notifierSupplier.get()
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifier.signal()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = notifier.signal()
            },
        )
    }
}
