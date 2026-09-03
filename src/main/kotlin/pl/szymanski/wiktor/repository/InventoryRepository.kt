package pl.szymanski.wiktor.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

/**
 * Read-only as far as the application is concerned: the controller's GET endpoints use it, and
 * every WRITE goes through [pl.szymanski.wiktor.subscription.InventoryProjectionUpdater], which
 * needs the revision guard that a `CrudRepository.save` cannot express.
 *
 * `MongoRepository` already extends the paging interface, so ES-2's separate
 * `PagingAndSortingRepository` is redundant here; `findAllBy(Pageable)` is the same derived
 * query it was.
 */
@Repository
interface InventoryRepository : MongoRepository<InventoryProjection, String> {
    fun findAllBy(pageable: Pageable): List<InventoryProjection>
}
