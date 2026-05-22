package pl.szymanski.wiktor.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.stereotype.Repository

@Repository
interface InventoryRepository :
    CrudRepository<InventoryProjection, String>,
    PagingAndSortingRepository<InventoryProjection, String> {

    fun findAllBy(pageable: Pageable): List<InventoryProjection>
}
