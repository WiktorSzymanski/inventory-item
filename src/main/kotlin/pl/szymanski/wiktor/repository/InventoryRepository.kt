package pl.szymanski.wiktor.repository

import org.springframework.data.relational.core.sql.LockMode
import org.springframework.data.relational.repository.Lock
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.stereotype.Repository
import pl.szymanski.wiktor.domain.InventoryItem
import java.util.Optional

@Repository
interface InventoryRepository : CrudRepository<InventoryItem, String>, PagingAndSortingRepository<InventoryItem, String> {
    /**
     * Loads an item for the reserve path with its row lock already held: renders
     * `SELECT … WHERE item_id = ? FOR UPDATE`. This is the one mechanism that separates this
     * branch from TO-3, where the same read is unlocked and the conflict is only detected at
     * write time by the `@Version` predicate.
     *
     * Must be called inside a transaction — the lock is released at commit or rollback, so
     * calling it outside one takes and drops the lock in the same statement, which locks nothing.
     */
    @Lock(LockMode.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: String): Optional<InventoryItem>
}
