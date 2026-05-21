package pl.szymanski.wiktor.repository

import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.stereotype.Repository
import pl.szymanski.wiktor.domain.InventoryItem

@Repository
interface InventoryRepository : CrudRepository<InventoryItem, String>, PagingAndSortingRepository<InventoryItem, String>
