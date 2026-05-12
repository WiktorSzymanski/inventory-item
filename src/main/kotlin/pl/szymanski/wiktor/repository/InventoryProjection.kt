package pl.szymanski.wiktor.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("inventory_state")
data class InventoryProjection(
    @Id @Column("item_id") val id: String,
    val availableQty: Int,
    val reservations: Map<String, Int> = mapOf(),
    val lastEventRevision: Long = -1L,
)
