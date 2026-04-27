package pl.szymanski.wiktor.repository

import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import pl.szymanski.wiktor.domain.InventoryItem
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface InventoryRepository : R2dbcRepository<InventoryItem, String> {

    override fun findById(id: String): Mono<InventoryItem?>

    fun findAllBy(pageable: Pageable): Flux<InventoryItem>
}
