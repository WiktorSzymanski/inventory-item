package pl.szymanski.wiktor.config

import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoException
import com.mongodb.MongoWriteException
import org.axonframework.common.jdbc.PersistenceExceptionResolver

/**
 * Turns a MongoDB write failure into "this was a concurrency conflict", which
 * [org.axonframework.eventsourcing.eventstore.AbstractEventStorageEngine] then reports as a
 * `ConcurrencyException`. It is the direct counterpart of `SQLStateResolver()` on the Postgres
 * branches, and it is load-bearing for exactly the same reason.
 *
 * This branch keeps its parent's `NullLockFactory`: nothing serialises two commands against one
 * `InventoryItem`, so the unique index on `{aggregateIdentifier, sequenceNumber}` is the ONLY
 * conflict detector, and [ConcurrencyRetryScheduler] is the only thing that makes a loser
 * succeed. That scheduler declines to retry anything without a `ConcurrencyException` in its
 * cause chain. So if a conflict arrives here unrecognised, every losing command fails
 * TERMINALLY into the saga's abandon() path and the run reads as a high rejection rate rather
 * than as an error -- plausible-looking, and wrong.
 *
 * Why this exists at all, when `MongoEventStorageEngine` already installs a default resolver:
 * that default matches duplicate-key alone (`DuplicateKeyException`, or a
 * `MongoBulkWriteException` carrying code 11000). Under a real transaction manager -- which
 * this branch wires, because the single-node replica set is what gives a multi-event append
 * atomicity -- the SAME race can instead surface as a **WriteConflict (code 112)**, raised when
 * two transactions touch the same document and one is aborted by the server. A write conflict
 * and a duplicate key are the same event to this application: some other command got there
 * first, reload and retry. Matching only the first would leave the second terminal.
 *
 * `TransientTransactionError` is included for the same reason. MongoDB labels an aborted
 * transaction that is safe to retry wholesale, and the driver may report it without a code this
 * resolver would otherwise recognise.
 */
object MongoConflictResolver : PersistenceExceptionResolver {

    /** Duplicate key on insert. The unique-index violation that replaces PG's 23505. */
    private const val DUPLICATE_KEY = 11000

    /** Duplicate key raised by an update rather than an insert. */
    private const val DUPLICATE_KEY_ON_UPDATE = 11001

    /**
     * Two transactions raced for the same document and the server aborted this one. Only
     * reachable because appends run inside a transaction; on a transaction-less engine the same
     * race can only ever be [DUPLICATE_KEY].
     */
    private const val WRITE_CONFLICT = 112

    private const val TRANSIENT_LABEL = "TransientTransactionError"

    /**
     * Walks the cause chain rather than testing the top exception. Axon, Spring and the driver
     * all wrap, and a conflict that arrives inside an `UncategorizedMongoDbException` is still a
     * conflict.
     */
    override fun isDuplicateKeyViolation(exception: Exception): Boolean {
        var cause: Throwable? = exception
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (isConflict(cause)) return true
            cause = cause.cause
        }
        return false
    }

    private fun isConflict(t: Throwable): Boolean = when (t) {
        is MongoBulkWriteException ->
            t.writeErrors.any { isConflictCode(it.code) } || hasTransientLabel(t)
        is MongoWriteException ->
            isConflictCode(t.error.code) || hasTransientLabel(t)
        // com.mongodb.DuplicateKeyException is a MongoException subtype in the 5.x driver, so
        // this arm covers it as well as MongoCommandException's WriteConflict.
        is MongoException ->
            isConflictCode(t.code) || hasTransientLabel(t)
        else -> false
    }

    private fun isConflictCode(code: Int): Boolean =
        code == DUPLICATE_KEY || code == DUPLICATE_KEY_ON_UPDATE || code == WRITE_CONFLICT

    private fun hasTransientLabel(e: MongoException): Boolean = e.hasErrorLabel(TRANSIENT_LABEL)
}
