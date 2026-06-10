package pl.szymanski.wiktor.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("orders")
data class Order(
    @Id @Column("order_id") val orderId: String,
    val userId: String,
) : Persistable<String> {
    @Transient private val _isNew: Boolean = true
    override fun getId(): String = orderId
    override fun isNew(): Boolean = _isNew
}
