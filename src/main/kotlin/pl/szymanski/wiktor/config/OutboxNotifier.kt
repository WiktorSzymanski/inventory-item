package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Raises **one** `NOTIFY` per transaction that writes to the outbox, from application code, inside
 * that transaction.
 *
 * This replaces V9's `AFTER INSERT ... FOR EACH ROW` trigger, and the difference is not stylistic.
 * A row-level trigger raises one notification per `event_publication` INSERT, so an order of N
 * lines raised N+1 of them — `OrderWriteCommandHandler.write` publishes one `InventoryReservedEvent`
 * per line plus an `OrderCompletedEvent`, and every one of them landed on the channel separately.
 * All N+1 said the same thing: *this transaction has outbox work*. Collapsing them to one message
 * per transaction is what the channel actually needs to carry, and it makes the traffic scale with
 * transactions rather than with `DISTINCT_ITEMS`.
 *
 * **Atomicity is unchanged.** `pg_notify` called inside a transaction queues the message in that
 * transaction; PostgreSQL emits it only at COMMIT and discards it on ROLLBACK. Exactly as under the
 * trigger, a subscriber can never be woken for rows that never landed.
 *
 * **Raised eagerly, on the first outbox row of the transaction — not from `beforeCommit`.** Both
 * are equally atomic for the reason above, so the choice is purely about where the statement sits.
 * `write` takes its exclusive `inventory_state` row locks in statement 4 and holds them to COMMIT,
 * so a `beforeCommit` callback would run the `pg_notify` inside that lock window; firing on the
 * first outbox INSERT puts it at statement 1, ahead of every lock. On a branch whose statement
 * order is load-bearing, that is the version to have.
 *
 * The `JdbcTemplate` resolves the transaction's own connection through `DataSourceUtils`, so no
 * second connection is taken and the notification really does share the writer's transaction.
 */
@Component
class OutboxNotifier(
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry,
) {
    /**
     * Notifications SENT, against `PostgresNotificationListener`'s count of those received. On a
     * branch that exists to price the channel, the two ends of it are the measurement.
     */
    private val sent: Counter = meterRegistry.counter("outbox.notify.sent")

    /**
     * Called once per outbox row written; sends at most one notification per transaction.
     *
     * With no transaction synchronization active the row was autocommitted on its own, so there is
     * nothing to collapse it into and nothing to defer it past — it goes out immediately.
     */
    fun notifyOnCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send()
            return
        }

        if (TransactionSynchronizationManager.hasResource(NOTIFIED)) return

        // Marks this transaction as having notified. Bound rather than held in a field because the
        // outbox is written from every order worker at once and the marker is per transaction,
        // which on this branch means per thread.
        TransactionSynchronizationManager.bindResource(NOTIFIED, java.lang.Boolean.TRUE)
        TransactionSynchronizationManager.registerSynchronization(ReleaseMarker)

        send()
    }

    private fun send() {
        jdbcTemplate.execute(NOTIFY_SQL)
        sent.increment()
    }

    /**
     * Releases the marker on COMMIT *and* on ROLLBACK. `afterCompletion` is the only hook that runs
     * on both; a `beforeCommit`-only release would leave a rolled-back transaction's marker bound
     * to the thread, and the next order that thread picked up would write to the outbox and never
     * notify.
     */
    private object ReleaseMarker : TransactionSynchronization {
        override fun afterCompletion(status: Int) {
            TransactionSynchronizationManager.unbindResourceIfPossible(NOTIFIED)
        }
    }

    companion object {
        /** Shared with [PostgresNotificationListener]; the two ends must never drift apart. */
        const val CHANNEL = "event_publication_notify"

        /**
         * No payload. Under the trigger it was the publication id, which a per-transaction message
         * cannot be — the transaction has many. Nothing needs one: the message says "outbox work
         * committed", and the position it committed at is already in `event_publication.seq`, which
         * is what the drain reads. See [PostgresNotificationListener] for what this branch does
         * with it, which is nothing.
         */
        private const val NOTIFY_SQL = "SELECT pg_notify('$CHANNEL', '')"

        /** Transaction-scoped key for "this transaction has already notified". */
        private val NOTIFIED = Any()
    }
}
