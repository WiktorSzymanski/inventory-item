package pl.szymanski.wiktor.service

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.domain.InventoryReservationFailedEvent
import pl.szymanski.wiktor.domain.InventoryReservationReleasedEvent
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import java.util.concurrent.CompletableFuture

/**
 * The saga, and the whole of it.
 *
 * Spring Modulith has no saga abstraction — it ships an event publication registry, resubmission
 * and `Moments`, and nothing that orchestrates a multi-step process — so this is what one looks like
 * built out of the parts it does ship. The three parts map cleanly onto Axon's, which is what makes
 * this branch comparable with ES-4's `OrderReservationSaga`:
 *
 * | Axon                              | here                                              |
 * |-----------------------------------|---------------------------------------------------|
 * | `saga_entry` + association values | the `order_saga` row, keyed by order id           |
 * | tracking event processor          | `@ApplicationModuleListener` over `event_publication` |
 * | `@StartSaga` / `@EndSaga`         | the row's `status`, moved by guarded UPDATEs      |
 *
 * **Every handler here does the same thing, and that is the design rather than an accident.** A
 * saga step is chosen from the saga's STATE, never from the event that woke it: the event says
 * where the saga was when it was written, and by the time it arrives — possibly twice, possibly
 * after a restart, possibly out of order relative to a republished sibling — the row is the only
 * thing that knows where the saga actually is. So all four triggers reduce to "read the row, run
 * the step it is waiting for", and the interesting logic lives in
 * [InventoryService.submitAdvance] rather than in four near-identical handlers here.
 *
 * The four events are exactly the transitions of the machine:
 *
 * ```
 *  OrderCreatedEvent ─────────────▶ reserve line 0
 *  InventoryReservedEvent ────────▶ reserve line k+1, or complete the order
 *  InventoryReservationFailedEvent ▶ release line k-1, or reject the order outright
 *  InventoryReservationReleasedEvent ▶ release line k-2, … or reject the order
 * ```
 *
 * **On returning `CompletableFuture`.** Spring Modulith's `CompletionRegisteringAdvisor` completes
 * a publication when the listener returns — unless the listener returns a `CompletableFuture`, in
 * which case it completes on `thenApply` and marks the publication FAILED on
 * `exceptionallyCompose`. That is what lets the step run on the order-worker pool, with its
 * conflict backoff held in a `DelayedWorkQueue` rather than on a thread, WITHOUT the publication
 * being completed the instant the work is merely submitted. Completing early would mean a crash
 * between "event delivered" and "step committed" silently strands the order in PENDING with nothing
 * left to redeliver — a fire-and-forget listener is what TO-3 does, and TO-3 can afford it because
 * its single transaction is the whole order.
 *
 * The interaction between that hook and `@Async`'s own future is nesting-order dependent
 * (`AsyncExecutionAspectSupport.doSubmit` hands back `submitCompletable`, which does not flatten),
 * so it is verified on a live stack rather than asserted here — see the branch's runbook. If it
 * turns out the advisor sees the async proxy's future instead of this one, the fallback is a
 * synchronous listener, at the cost of parking a worker thread through every conflict backoff.
 */
@Component
class OrderReservationSaga(
    private val inventoryService: InventoryService,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Start. The saga row itself was already written by the accept transaction — see
     * `CreateOrderCommandHandler` — so there is nothing to create here, only the first step to run.
     *
     * `first = true` marks the one delivery per order whose wait since admission is QUEUEING rather
     * than saga progress, which is what `order.queue.wait` measures.
     */
    @ApplicationModuleListener
    fun on(event: OrderCreatedEvent): CompletableFuture<Void> {
        log.info("[SAGA] start orderId={} items={}", event.orderId, event.items.size)
        return inventoryService.submitAdvance(event.orderId, event.correlationId, first = true)
    }

    /** A line is reserved: reserve the next one, or confirm the order if that was the last. */
    @ApplicationModuleListener
    fun on(event: InventoryReservedEvent): CompletableFuture<Void> =
        inventoryService.submitAdvance(event.orderId, event.correlationId)

    /**
     * A line could not be reserved. The saga is already `COMPENSATING` by the time this arrives —
     * `FailReservationCommandHandler` makes that transition in the same transaction that publishes
     * this event — so advancing from the row walks back through the lines already held, or rejects
     * the order immediately if there are none.
     */
    @ApplicationModuleListener
    fun on(event: InventoryReservationFailedEvent): CompletableFuture<Void> =
        inventoryService.submitAdvance(event.orderId, event.correlationId)

    /** A line has been given back: release the next one down, or reject the order at index 0. */
    @ApplicationModuleListener
    fun on(event: InventoryReservationReleasedEvent): CompletableFuture<Void> =
        inventoryService.submitAdvance(event.orderId, event.correlationId)
}
