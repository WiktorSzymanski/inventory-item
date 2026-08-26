package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * The contract that replaces the V9 `AFTER INSERT ... FOR EACH ROW` trigger: **one** notification
 * per transaction that writes to the outbox, raised by application code inside that transaction.
 *
 * Two properties are load-bearing and both are asserted here.
 *
 * It must collapse: `OrderWriteCommandHandler.write` publishes one `InventoryReservedEvent` per
 * order line plus an `OrderCompletedEvent`, so the trigger raised N+1 notifications where this
 * raises 1. A per-call notification would be the trigger again, in Kotlin.
 *
 * And it must fire EAGERLY, on the first outbox row, rather than from a `beforeCommit` callback.
 * `pg_notify` queues in the transaction and PostgreSQL emits it at COMMIT either way, so both are
 * equally atomic — but statement 4 of `write` takes the `inventory_state` row locks and holds them
 * to COMMIT, so a `beforeCommit` callback would put the `pg_notify` inside that lock window. This
 * puts it ahead of every lock, which is where the branch's statement ordering wants it.
 */
class OutboxNotifierTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val registry = SimpleMeterRegistry()

    private fun notifier() = OutboxNotifier(jdbc, registry)

    private fun sentCount() = registry.counter("outbox.notify.sent").count().toInt()

    init {
        every { jdbc.execute(any<String>()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
        TransactionSynchronizationManager.getResourceMap().keys.toList()
            .forEach { TransactionSynchronizationManager.unbindResourceIfPossible(it) }
    }

    /** What Spring's transaction manager does around a successful commit, in the same order. */
    private fun commitTransaction() {
        val synchronizations = TransactionSynchronizationManager.getSynchronizations()
        synchronizations.forEach { it.beforeCommit(false) }
        synchronizations.forEach { it.beforeCompletion() }
        TransactionSynchronizationManager.clearSynchronization()
        synchronizations.forEach { it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED) }
    }

    /** A rollback skips `beforeCommit` entirely, which is the case the resource marker must survive. */
    private fun rollbackTransaction() {
        val synchronizations = TransactionSynchronizationManager.getSynchronizations()
        synchronizations.forEach { it.beforeCompletion() }
        TransactionSynchronizationManager.clearSynchronization()
        synchronizations.forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
    }

    @Test
    fun `many outbox rows in one transaction raise exactly one notification`() {
        val notifier = notifier()
        TransactionSynchronizationManager.initSynchronization()

        repeat(8) { notifier.notifyOnCommit() }
        commitTransaction()

        verify(exactly = 1) { jdbc.execute(any<String>()) }
        assertEquals(1, sentCount())
    }

    @Test
    fun `the notification is raised on the first row, not deferred to commit`() {
        val notifier = notifier()
        TransactionSynchronizationManager.initSynchronization()

        notifier.notifyOnCommit()

        // Already issued, with the transaction still wide open and no lock yet taken.
        verify(exactly = 1) { jdbc.execute(any<String>()) }

        // And the commit callbacks add nothing on top of it.
        commitTransaction()
        verify(exactly = 1) { jdbc.execute(any<String>()) }
    }

    @Test
    fun `the payload is empty and the channel is the one the listener listens on`() {
        val sql = slot<String>()
        every { jdbc.execute(capture(sql)) } just Runs

        TransactionSynchronizationManager.initSynchronization()
        notifier().notifyOnCommit()

        assertEquals("SELECT pg_notify('event_publication_notify', '')", sql.captured)
    }

    @Test
    fun `each transaction gets its own notification`() {
        val notifier = notifier()

        repeat(3) {
            TransactionSynchronizationManager.initSynchronization()
            notifier.notifyOnCommit()
            notifier.notifyOnCommit()
            commitTransaction()
        }

        verify(exactly = 3) { jdbc.execute(any<String>()) }
        assertEquals(3, sentCount())
    }

    @Test
    fun `a committed transaction leaves no bound resource behind`() {
        TransactionSynchronizationManager.initSynchronization()
        notifier().notifyOnCommit()
        commitTransaction()

        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
    }

    @Test
    fun `a rolled back transaction leaves no bound resource behind`() {
        TransactionSynchronizationManager.initSynchronization()
        notifier().notifyOnCommit()
        rollbackTransaction()

        // The notification itself needs no undoing: pg_notify queued inside the transaction is
        // discarded by PostgreSQL on ROLLBACK. Only the marker has to be released, or the next
        // transaction on this thread would think it had already notified.
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
    }

    @Test
    fun `an outbox row written outside a transaction notifies immediately`() {
        notifier().notifyOnCommit()

        verify(exactly = 1) { jdbc.execute(any<String>()) }
        assertEquals(1, sentCount())
    }
}
