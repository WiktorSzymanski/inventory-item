package pl.szymanski.wiktor.repository

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * The inventory read model. Collection name matches ES-2's `inventory_state` table so the
 * per-relation dashboard panels line up across the two branches -- see [pl.szymanski.wiktor.config.MongoCollections].
 *
 * `lastEventRevision` is the monotonic guard that makes projection writes idempotent under
 * at-least-once delivery and replay. It carries over from the Postgres branches unchanged; only
 * the statement that enforces it moves, from `WHERE last_event_revision < :revision` to a Mongo
 * query predicate. See [pl.szymanski.wiktor.subscription.InventoryProjectionUpdater].
 */
@Document(collection = "inventory_state")
data class InventoryProjection(
    @Id val id: String,
    val availableQty: Int,
    val lastEventRevision: Long = -1L,
)
