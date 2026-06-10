package pl.szymanski.wiktor.repository

import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import pl.szymanski.wiktor.domain.Reservation

@Repository
interface ReservationRepository : CrudRepository<Reservation, String>
