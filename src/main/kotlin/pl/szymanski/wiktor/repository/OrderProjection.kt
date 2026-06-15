package pl.szymanski.wiktor.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Table("orders")
data class OrderProjection(
    @Id @Column("order_id") val orderId: String,
    val userId: String,
    val status: String = "PENDING",
    val items: Map<String, Int> = mapOf(),
    @Column("failure_reason") val failureReason: String? = null,
)

@Repository
interface OrderRepository : CrudRepository<OrderProjection, String>
