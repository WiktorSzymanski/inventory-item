package pl.szymanski.wiktor.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Clock
import java.util.UUID

enum class OrderStatus { PENDING, CONFIRMED, REJECTED }

@Table("orders")
data class Order(
    @Id @Column("order_id") val orderId: String,
    val userId: String,
    val status: OrderStatus = OrderStatus.PENDING,
    val failureReason: String? = null,
) : Persistable<String> {
    @Transient private val _isNew: Boolean = true
    override fun getId(): String = orderId
    override fun isNew(): Boolean = _isNew

    companion object {
        // Aggregate factory mirroring InventoryItem.create: produces the new PENDING Order and,
        // alongside it, the OrderCreatedEvent stamped from the shared clock at production time.
        // Subsequent status transitions (CONFIRMED/REJECTED) are applied via OrderRepository.updateStatus
        // rather than by re-saving the aggregate, because Persistable.isNew is always true here.
        fun create(
            orderId: String,
            userId: String,
            items: List<ReservedItem>,
            correlationId: UUID,
            clock: Clock,
            additionalBytesSize: Int = 0,
        ): Pair<Order, OrderCreatedEvent> {
            val additionalBytes = if (additionalBytesSize > 0) "x".repeat(additionalBytesSize) else ""
            return Pair(
                Order(orderId = orderId, userId = userId),
                OrderCreatedEvent(orderId, userId, items, correlationId, clock.instant(), additionalBytes),
            )
        }
    }
}
