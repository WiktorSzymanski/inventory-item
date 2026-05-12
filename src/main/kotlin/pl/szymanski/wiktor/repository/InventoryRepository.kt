package pl.szymanski.wiktor.repository

import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface InventoryRepository : R2dbcRepository<InventoryProjection, String> {

    override fun findById(id: String): Mono<InventoryProjection?>

    fun findAllBy(pageable: Pageable): Flux<InventoryProjection>
}
