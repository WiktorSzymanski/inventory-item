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
 * per-replica append distribution. Subscribing processors don't replay history and only run
 * when the append actually commits, so conflict-rollbacks are never counted.
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
