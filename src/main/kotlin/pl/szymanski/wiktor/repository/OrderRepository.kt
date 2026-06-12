package pl.szymanski.wiktor.repository

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import pl.szymanski.wiktor.domain.Order
import pl.szymanski.wiktor.domain.OrderStatus

@Repository
interface OrderRepository : CrudRepository<Order, String> {
    @Modifying
    @Query("UPDATE orders SET status = :status, failure_reason = :failureReason WHERE order_id = :orderId")
    fun updateStatus(orderId: String, status: OrderStatus, failureReason: String?)
}
