package pl.szymanski.wiktor.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Clock
import java.util.UUID

enum class OrderStatus { PENDING, CONFIRMED, REJECTED }

// Wrapper instead of a bare List so Spring Data JDBC maps the lines to a single JSONB column via
// the converters in JdbcConversionsConfig; a List-typed property would be unpacked into a child
// table regardless of registered conversions.
data class OrderItems(val lines: List<ReservedItem>)

@Table("orders")
data class Order(
    @Id @Column("order_id") val orderId: String,
    val userId: String,
    // Stored as JSONB ({"<itemId>": <qty>}), matching the ES branch's orders projection.
    val items: OrderItems,
    val status: OrderStatus = OrderStatus.PENDING,
    val failureReason: String? = null,
    @Version val version: Long = 0L,
) {
    fun confirm(clock: Clock): Pair<Order, OrderCompletedEvent> {
        check(status == OrderStatus.PENDING) { "Order $orderId cannot be confirmed from status $status" }
        return Pair(
            copy(status = OrderStatus.CONFIRMED),
            OrderCompletedEvent(orderId, clock.instant()),
        )
    }

    fun reject(reason: String, clock: Clock): Pair<Order, OrderFailedEvent> {
        check(status == OrderStatus.PENDING) { "Order $orderId cannot be rejected from status $status" }
        return Pair(
            copy(status = OrderStatus.REJECTED, failureReason = reason),
            OrderFailedEvent(orderId, reason, clock.instant()),
        )
    }

    companion object {
        // Aggregate factory mirroring InventoryItem.create: produces the new PENDING Order and,
        // alongside it, the OrderCreatedEvent stamped from the shared clock at production time.
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
                Order(orderId = orderId, userId = userId, items = OrderItems(items)),
                OrderCreatedEvent(orderId, userId, items, correlationId, clock.instant(), additionalBytes),
            )
        }
    }
}
