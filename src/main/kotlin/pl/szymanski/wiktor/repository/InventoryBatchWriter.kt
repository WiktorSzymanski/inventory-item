package pl.szymanski.wiktor.repository

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.szymanski.wiktor.domain.InventoryItem
import pl.szymanski.wiktor.domain.Reservation
import java.sql.PreparedStatement

/**
 * A lost update, carrying the row that lost it.
 *
 * Still an [OptimisticLockingFailureException], so `InventoryService.runOrderTask` retries it
 * exactly as it retried the one Spring Data JDBC used to raise. The item id is on it so a branch
 * that caches inventory state can evict the ONE entry that is now stale instead of the whole
 * order's worth — over-eviction would work, but it would understate the cache's hit rate on
 * precisely the orders TO-4 exists to measure.
 */
class InventoryVersionConflictException(
    val itemId: String,
    val expectedVersion: Long,
) : OptimisticLockingFailureException(
    "Optimistic lock failed on inventory_state item_id=$itemId expected version=$expectedVersion"
)

/**
 * The write half of the split reserve path: everything an order changes, in as few round trips as
 * the driver allows, so the write transaction is short enough to be worth calling a write
 * transaction. The per-line path this replaces reached the same rows through
 * [InventoryRepository.save] once per line, which is `2N` round trips inside the transaction that
 * already holds every one of those rows' locks.
 *
 * Spring Data JDBC has no batching of its own — `saveAll` is a loop — and its `@Version` handling
 * is per-`save`, so the version predicate is spelled out here instead.
 */
@Repository
class InventoryBatchWriter(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * One versioned `UPDATE` per item, in a single batch.
     *
     * `reserve_delay_ms` and `additional_bytes` are rewritten even though a reserve never changes
     * them. That is not redundancy: Spring Data JDBC's generated UPDATE rewrites every mapped
     * column, and rewriting the (TOASTed) `additional_bytes` payload on every reserve is precisely
     * the cost the PAYLOAD_BYTES lever exists to measure. Dropping them from the SET list would
     * make this path cheaper than the per-line one it replaces for a reason that has nothing to do
     * with transaction shape, and would silently un-measure the lever on every TO branch at once.
     *
     * Rows go out sorted by `item_id`. Postgres executes a batch's statements in array order, so
     * this is the same global lock order the per-line path got from `sortedBy { it.itemId }`, and
     * it keeps two orders that share items from deadlocking on each other.
     *
     * Returns the rows AS THEY NOW STAND IN THE DATABASE — same values, version bumped by one —
     * in the order they were written, the way [InventoryRepository.save] returned its saved
     * entity. TO-4 needs exactly that to refresh its inventory-state cache after commit; the
     * branches without a cache ignore it. Bumping the version here rather than at each call site
     * keeps the `+ 1` in the one place that also writes it.
     */
    fun updateAll(items: List<InventoryItem>): List<InventoryItem> {
        if (items.isEmpty()) return emptyList()
        val rows = items.sortedBy { it.id }

        val updated = jdbcTemplate.batchUpdate(
            UPDATE_ITEM_SQL,
            object : BatchPreparedStatementSetter {
                override fun getBatchSize(): Int = rows.size

                override fun setValues(ps: PreparedStatement, i: Int) {
                    val item = rows[i]
                    ps.setInt(1, item.availableQty)
                    ps.setInt(2, item.reserveDelayMs)
                    ps.setString(3, item.additionalBytes)
                    ps.setLong(4, item.version + 1)
                    ps.setString(5, item.id)
                    ps.setLong(6, item.version)
                }
            },
        )

        // A zero row count means the version predicate missed, i.e. somebody committed a change to
        // that row between this order's read phase and now. Raised as the exception Spring Data
        // JDBC would have raised, so InventoryService.runOrderTask retries the order unchanged.
        updated.forEachIndexed { i, count ->
            if (count == 0) {
                val item = rows[i]
                throw InventoryVersionConflictException(item.id, item.version)
            }
        }

        return rows.map { it.copy(version = it.version + 1) }
    }

    /** One `INSERT` per reserved line, in a single batch. Mirrors [ReservationRepository.save]. */
    fun insertAll(reservations: List<Reservation>) {
        if (reservations.isEmpty()) return

        jdbcTemplate.batchUpdate(
            INSERT_RESERVATION_SQL,
            object : BatchPreparedStatementSetter {
                override fun getBatchSize(): Int = reservations.size

                override fun setValues(ps: PreparedStatement, i: Int) {
                    val reservation = reservations[i]
                    ps.setString(1, reservation.itemId)
                    ps.setString(2, reservation.reservationId)
                    ps.setInt(3, reservation.quantity)
                    ps.setObject(4, reservation.createdAt)
                }
            },
        )
    }

    companion object {
        const val UPDATE_ITEM_SQL: String =
            "UPDATE inventory_state " +
                "SET available_qty = ?, reserve_delay_ms = ?, additional_bytes = ?, version = ? " +
                "WHERE item_id = ? AND version = ?"

        const val INSERT_RESERVATION_SQL: String =
            "INSERT INTO reservations (item_id, reservation_id, quantity, created_at) VALUES (?, ?, ?, ?)"
    }
}
