package pl.szymanski.wiktor.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class SagaStatus { RUNNING, COMPENSATING, ENDED }

/**
 * The saga's line list, in the order the client sent it.
 *
 * A wrapper rather than a bare `List` for the same reason [OrderItems] is one — Spring Data JDBC
 * unpacks a List-typed property into a child table regardless of registered conversions — but it is
 * NOT [OrderItems] and must not reuse its converter. `OrderItemsToJsonbConverter` stores
 * `{"<itemId>": qty}`, a MAP, which is fine for the orders projection (it mirrors the ES branch's)
 * and useless here: a map has no order and no duplicates, and this saga's entire cursor is a
 * POSITION in this list.
 */
data class SagaLines(val lines: List<ReservedItem>)

/**
 * The state machine that replaces TO-3's single order-wide transaction.
 *
 * TO-3 reserves a whole order at once: read every line's row, apply every line to an in-memory
 * working copy, write the lot in four statements. There is no intermediate state to persist because
 * there is no intermediate state — the order is reserved or it is not.
 *
 * Here a line is a transaction, so the progress between them has to live somewhere durable, and
 * this row is it. It is the hand-written counterpart to Axon's `saga_entry`: Spring Modulith ships
 * an event publication registry, resubmission and `Moments`, but no saga abstraction at all, so the
 * association (order id), the state (index, status) and the lifecycle (ended) are all spelled out.
 *
 * **[currentIndex] is a single cursor read in two directions**, which is the whole trick to keeping
 * this row small:
 *
 *  - `RUNNING` — it is the index of the NEXT LINE TO RESERVE, counting up. Lines
 *    `[0, currentIndex)` are reserved.
 *  - `COMPENSATING` — the same invariant holds, so it counts back DOWN as lines are released, and
 *    `currentIndex == 0` means every reservation this order made has been given back.
 *
 * So "which lines are held right now" is `lines[0 until currentIndex]` in both phases, and no
 * second column is needed to track compensation separately.
 *
 * **There are no transition methods here, unlike [Order].** A `copy(currentIndex = …)` handed to
 * `save()` would be the obvious shape and would be WRONG: every transition of this row has to carry
 * a predicate — "apply only if the saga is still waiting for exactly this step" — because a step can
 * be delivered twice. Spring Modulith completes a publication in a transaction of its own AFTER the
 * listener returns, so a crash in that window replays the step against a saga that has already moved
 * past it, and the backup republisher reopens the window deliberately. Every move therefore goes
 * through [pl.szymanski.wiktor.repository.SagaCursorWriter] as a guarded UPDATE, and what lives here
 * are the read-side predicates the handlers use to skip obviously stale work early.
 */
@Table("order_saga")
data class OrderSaga(
    @Id @Column("order_id") val orderId: String,
    val correlationId: UUID,
    val lines: SagaLines,
    val currentIndex: Int = 0,
    val status: SagaStatus = SagaStatus.RUNNING,
    val failureReason: String? = null,
    val failureCode: String? = null,
    val startedAt: OffsetDateTime,
    @Version val version: Long = 0L,
) {
    // @Transient, and it matters. Spring Data JDBC discovers persistent properties from property
    // DESCRIPTORS as well as fields, so a getter-only Kotlin property is a candidate column — this
    // one would become `line_count`, which does not exist, and the failure lands at the first
    // INSERT rather than at startup.
    @get:Transient
    val lineCount: Int get() = lines.lines.size

    fun lineAt(index: Int): ReservedItem = lines.lines[index]

    /** True once every line has been reserved, i.e. the order is ready to be confirmed. */
    fun allReserved(): Boolean = status == SagaStatus.RUNNING && currentIndex >= lineCount

    /**
     * Whether a reserve of [lineIndex] is the step this saga is actually waiting for. False for a
     * redelivered step (the saga has moved on), and false once compensation has begun.
     */
    fun isReserveStep(lineIndex: Int): Boolean =
        status == SagaStatus.RUNNING && currentIndex == lineIndex && lineIndex < lineCount

    /**
     * Whether a release of [lineIndex] is the step this saga is waiting for. Compensation walks the
     * reserved prefix backwards, so the line being released is always the last one still held —
     * `currentIndex - 1`.
     */
    fun isReleaseStep(lineIndex: Int): Boolean =
        status == SagaStatus.COMPENSATING && currentIndex == lineIndex + 1 && lineIndex >= 0

    companion object {
        /**
         * [startedAt] is the ADMISSION instant, taken before the accept transaction opens, not a
         * timestamp read here. `order_e2e_time` and `saga_lifetime` are both measured from it, and
         * on TO-3 the equivalent span starts at a `System.nanoTime()` captured at the same point —
         * stamping it inside the transaction would quietly shorten every end-to-end sample on this
         * branch by the accept transaction's own duration.
         */
        fun start(
            orderId: String,
            items: List<ReservedItem>,
            correlationId: UUID,
            startedAt: Instant,
        ): OrderSaga = OrderSaga(
            orderId = orderId,
            correlationId = correlationId,
            lines = SagaLines(items),
            startedAt = OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
        )
    }
}
