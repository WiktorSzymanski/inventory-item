package pl.szymanski.wiktor.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime

@Table("reservations")
data class Reservation(
    val itemId: String,
    @Id val reservationId: String,
    val quantity: Int,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
) : Persistable<String> {
    @Transient private val _isNew: Boolean = true
    override fun getId(): String = reservationId
    override fun isNew(): Boolean = _isNew
}
