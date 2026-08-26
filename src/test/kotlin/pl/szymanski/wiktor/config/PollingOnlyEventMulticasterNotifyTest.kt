package pl.szymanski.wiktor.config

import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.core.env.Environment
import org.springframework.modulith.events.core.EventPublicationRegistry
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.TargetEventPublication
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalApplicationListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * [PollingOnlyEventMulticaster] is the only place in the codebase that writes `event_publication`
 * rows, which makes it the only place that can signal [OutboxNotifyCoalescer].
 *
 * Two properties are load-bearing here, and both differ from what V2's trigger gave for free by
 * running inside Postgres. It must signal only from `afterCommit` — never eagerly, and never for a
 * transaction that rolls back — because [OutboxNotifyCoalescer.signal] is a plain in-memory flag
 * write with no transaction of its own to be discarded on ROLLBACK the way a trigger's queued
 * `pg_notify` was. And it must not touch the notifier for an event with no AFTER_COMMIT listener:
 * framework events (every `ContextRefreshedEvent` and friend) go through this same method, and
 * `notifierSupplier` is lazy specifically so resolving one for such an event does not force
 * [OutboxNotifyCoalescer] — and the standalone JDBC connection its flush loop opens — into
 * existence part-way through context startup.
 */
class PollingOnlyEventMulticasterNotifyTest {

    private val repository = mockk<EventPublicationRepository>(relaxed = true)
    private val registry = mockk<EventPublicationRegistry>(relaxed = true)
    private val environment = mockk<Environment>(relaxed = true)
    private val notifier = mockk<OutboxNotifyCoalescer>(relaxed = true)

    private data class TestEvent(val id: String = "e1")

    private fun multicaster(vararg listeners: ApplicationListener<*>) =
        PollingOnlyEventMulticaster({ registry }, { repository }, { environment }, { notifier }).apply {
            setBeanFactory(DefaultListableBeanFactory())
            listeners.forEach { addApplicationListener(it) }
        }

    private fun publish(multicaster: PollingOnlyEventMulticaster, event: TestEvent = TestEvent()) =
        multicaster.multicastEvent(PayloadApplicationEvent(this, event), null)

    @AfterEach
    fun tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    /** What Spring's transaction manager does around a successful commit, in the same order. */
    private fun commitTransaction() {
        val synchronizations = TransactionSynchronizationManager.getSynchronizations()
        synchronizations.forEach { it.beforeCommit(false) }
        synchronizations.forEach { it.beforeCompletion() }
        TransactionSynchronizationManager.clearSynchronization()
        synchronizations.forEach { it.afterCommit() }
        synchronizations.forEach { it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED) }
    }

    /** A rollback skips `afterCommit` entirely, which is exactly the case this file guards. */
    private fun rollbackTransaction() {
        val synchronizations = TransactionSynchronizationManager.getSynchronizations()
        synchronizations.forEach { it.beforeCompletion() }
        TransactionSynchronizationManager.clearSynchronization()
        synchronizations.forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
    }

    @Test
    fun `persisting a publication signals only after commit, not eagerly`() {
        TransactionSynchronizationManager.initSynchronization()
        val multicaster = multicaster(AfterCommitListener("listener-1"))

        publish(multicaster)

        verify(exactly = 1) { repository.create(any<TargetEventPublication>()) }
        verify { notifier wasNot Called }

        commitTransaction()

        verify(exactly = 1) { notifier.signal() }
    }

    @Test
    fun `a rolled back transaction never signals`() {
        TransactionSynchronizationManager.initSynchronization()
        val multicaster = multicaster(AfterCommitListener("listener-1"))

        publish(multicaster)
        rollbackTransaction()

        verify { notifier wasNot Called }
    }

    @Test
    fun `several listeners on one event still register just as many rows but the notifier sees each signal`() {
        TransactionSynchronizationManager.initSynchronization()
        val multicaster = multicaster(
            AfterCommitListener("listener-1"),
            AfterCommitListener("listener-2"),
            AfterCommitListener("listener-3"),
        )

        publish(multicaster)
        commitTransaction()

        // One multicastEvent call, one signal registration — collapsing across listeners on the
        // SAME event was never this class's job on this branch; TargetEventPublication rows are
        // still one per listener, same as before this change.
        verify(exactly = 3) { repository.create(any<TargetEventPublication>()) }
        verify(exactly = 1) { notifier.signal() }
    }

    @Test
    fun `several events in one transaction each register a signal, and that is fine`() {
        TransactionSynchronizationManager.initSynchronization()
        val multicaster = multicaster(AfterCommitListener("listener-1"))

        repeat(6) { publish(multicaster) }
        commitTransaction()

        // Unlike the eager per-transaction design TO-1-2 used, there is no dedupe marker here:
        // OutboxNotifyCoalescer.signal() is idempotent and lock-free, so registering it six times
        // for one order (one InventoryReservedEvent per line plus OrderCompletedEvent) costs
        // nothing worth guarding against — the marker existed there to avoid repeat JDBC round
        // trips, and there are none here to avoid.
        verify(exactly = 6) { notifier.signal() }
    }

    @Test
    fun `an event with no after-commit listener never touches the notifier`() {
        val multicaster = multicaster(ApplicationListener<PayloadApplicationEvent<TestEvent>> { })

        publish(multicaster)

        verify(exactly = 0) { repository.create(any<TargetEventPublication>()) }
        verify { notifier wasNot Called }
    }

    @Test
    fun `an event published outside a transaction signals immediately`() {
        val multicaster = multicaster(AfterCommitListener("listener-1"))

        publish(multicaster)

        verify(exactly = 1) { notifier.signal() }
    }

    /** The shape `@ApplicationModuleListener` produces: transactional, AFTER_COMMIT, identified. */
    private class AfterCommitListener(private val id: String) :
        TransactionalApplicationListener<PayloadApplicationEvent<TestEvent>> {

        override fun getTransactionPhase() = TransactionPhase.AFTER_COMMIT

        override fun getListenerId() = id

        override fun addCallback(callback: TransactionalApplicationListener.SynchronizationCallback) = Unit

        override fun processEvent(event: PayloadApplicationEvent<TestEvent>) = Unit

        override fun onApplicationEvent(event: PayloadApplicationEvent<TestEvent>) = Unit
    }
}
