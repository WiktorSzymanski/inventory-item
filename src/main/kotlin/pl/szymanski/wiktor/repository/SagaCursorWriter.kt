package pl.szymanski.wiktor.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.szymanski.wiktor.domain.SagaStatus

/**
 * Every move of a saga's cursor, as a GUARDED single statement.
 *
 * A step can be delivered twice. `@ApplicationModuleListener` is
 * `@Transactional(REQUIRES_NEW)` and Spring Modulith's `CompletionRegisteringAdvisor` marks the
 * publication complete in a transaction of its OWN, after the listener returns — so there is a
 * window in which a step's transaction has committed but its publication has not been completed,
 * and a crash there replays the step against a saga that has already moved past it. The backup
 * republisher reopens the same window on purpose.
 *
 * The guard is therefore not belt-and-braces, it is the correctness argument, and it is a PREDICATE
 * rather than a read-then-write for two reasons:
 *
 *  * A read-then-write needs the read INSIDE the step's transaction to be safe, which is a second
 *    round trip in the one place this branch is trying to keep short. The line's own data is read
 *    outside the transaction, TO-3-style; this keeps the write phase a fixed four statements.
 *  * Under `READ COMMITTED`, two concurrent deliveries of the same step serialise on the row lock
 *    and the loser re-evaluates the predicate against the winner's committed row, so it sees
 *    `current_index` already moved and updates 0 rows. A read-then-write would need `@Version` and
 *    an exception to reach the same place.
 *
 * **These statements run FIRST in their transaction**, ahead of the outbox write and the
 * inventory_state update. A delivery that has lost its claim then rolls back having touched nothing
 * else — which is what makes a replayed step a no-op rather than a second reservation. The row lock
 * this takes is held for the rest of the transaction, and costs nothing: a saga row is per-order
 * and its steps are strictly sequential, so nothing else ever contends for it.
 *
 * `version` is bumped by hand here because Spring Data JDBC's `@Version` handling lives in `save()`,
 * which this deliberately bypasses. Nothing reads the value — the predicates key off
 * `(status, current_index)`, which the steps already make unique — but leaving it frozen at 0 would
 * make the column a lie for anything that later looks at it.
 */
@Repository
class SagaCursorWriter(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Claims reserve step [lineIndex] and moves the cursor past it.
     *
     * @return true if this caller owns the step; false if the saga had already advanced past it or
     *         is no longer RUNNING, in which case the caller must roll back and do nothing.
     */
    fun advance(orderId: String, lineIndex: Int): Boolean =
        jdbcTemplate.update(ADVANCE_SQL, lineIndex + 1, orderId, lineIndex) == 1

    /**
     * Claims release step [lineIndex] and moves the cursor back onto it.
     *
     * Compensation walks the reserved prefix backwards, so the line being released is always the
     * last one still held: the cursor stands at `lineIndex + 1` before, and at `lineIndex` after.
     */
    fun retreat(orderId: String, lineIndex: Int): Boolean =
        jdbcTemplate.update(RETREAT_SQL, lineIndex, orderId, lineIndex + 1) == 1

    /**
     * Switches a RUNNING saga to COMPENSATING.
     *
     * @return true if this caller made the transition. False means compensation was already under
     *         way — a second `InventoryReservationFailedEvent` for the same order must NOT start a
     *         second walk back through the same lines.
     */
    fun beginCompensation(orderId: String, reason: String, code: String): Boolean =
        jdbcTemplate.update(COMPENSATE_SQL, reason, code, orderId) == 1

    /**
     * Ends a saga whose every line is reserved. The `current_index = lineCount` predicate is what
     * stops a replayed "last line reserved" event from ending a saga that has meanwhile been failed.
     */
    fun endCompleted(orderId: String, lineCount: Int): Boolean =
        jdbcTemplate.update(END_COMPLETED_SQL, orderId, lineCount) == 1

    /** Ends a saga whose compensation has given every line back (`current_index == 0`). */
    fun endCompensated(orderId: String): Boolean =
        jdbcTemplate.update(END_COMPENSATED_SQL, orderId) == 1

    // The status literals are interpolated from [SagaStatus] rather than typed out, so a rename of
    // the enum cannot leave a predicate here matching nothing — a failure that would present not as
    // an error but as sagas silently refusing to advance. `val`, not `const val`, for exactly that:
    // a compile-time constant cannot reference the enum.
    companion object {
        val ADVANCE_SQL: String =
            "UPDATE order_saga SET current_index = ?, version = version + 1 " +
                "WHERE order_id = ? AND status = '${SagaStatus.RUNNING}' AND current_index = ?"

        val RETREAT_SQL: String =
            "UPDATE order_saga SET current_index = ?, version = version + 1 " +
                "WHERE order_id = ? AND status = '${SagaStatus.COMPENSATING}' AND current_index = ?"

        val COMPENSATE_SQL: String =
            "UPDATE order_saga SET status = '${SagaStatus.COMPENSATING}', failure_reason = ?, " +
                "failure_code = ?, version = version + 1 " +
                "WHERE order_id = ? AND status = '${SagaStatus.RUNNING}'"

        val END_COMPLETED_SQL: String =
            "UPDATE order_saga SET status = '${SagaStatus.ENDED}', version = version + 1 " +
                "WHERE order_id = ? AND status = '${SagaStatus.RUNNING}' AND current_index = ?"

        val END_COMPENSATED_SQL: String =
            "UPDATE order_saga SET status = '${SagaStatus.ENDED}', version = version + 1 " +
                "WHERE order_id = ? AND status = '${SagaStatus.COMPENSATING}' AND current_index = 0"
    }
}
