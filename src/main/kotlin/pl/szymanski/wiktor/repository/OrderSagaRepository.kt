package pl.szymanski.wiktor.repository

import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import pl.szymanski.wiktor.domain.OrderSaga

/**
 * Reads, and the initial INSERT. Every subsequent move of the saga's cursor goes through
 * [SagaCursorWriter] instead, because a step transition is a GUARDED update — it must apply only if
 * the saga is still waiting for that exact step — and `save()` cannot express a predicate.
 */
@Repository
interface OrderSagaRepository : CrudRepository<OrderSaga, String>
