package pl.szymanski.wiktor.subscription

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.domain.InventoryReservedEvent

/**
 * Per-replica counter of successful inventory reservations.
 *
 * Registered as a SUBSCRIBING event processor (see AxonCustomizerConfig), so it handles the
 * event synchronously on the replica that PUBLISHED it — i.e. the one whose command handler
 * appended it. Unlike the `inventory-projection` tracking processor (single owner, so
 * `inventory.append.success` only ever increments on one replica), this shows the true
 * per-replica append distribution. Subscribing processors don't replay history.
 *
 * A lost write race is never counted, but not for the reason it might appear: subscribers run at
 * PREPARE_COMMIT — after the INSERT, still BEFORE the COMMIT — and a 23505 is raised by
 * appendEvents, which runs earlier still. So the conflict aborts before this handler is reached.
 * A rollback occurring between the INSERT and the COMMIT would leave this incremented for an
 * event that does not exist: over-count only, and rare. Note also that it counts
 * InventoryReservedEvent alone, so on the saga's compensation path it overstates net reserved
 * stock by the number of released lines.
 */
@Component
@ProcessingGroup("reserve-metrics")
class ReserveMetricsCounter(meterRegistry: MeterRegistry) {

    private val appliedCounter: Counter =
        Counter.builder("inventory.reserve.applied").register(meterRegistry)

    @EventHandler
    fun on(event: InventoryReservedEvent) {
        appliedCounter.increment()
    }
}
