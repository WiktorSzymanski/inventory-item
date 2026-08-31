package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * ES-4-bounded: the queue of INCOMING sagas has a size, and it is
 * `AXON_SAGA_INTAKE_CAPACITY`.
 *
 * ## What it is for
 *
 * A saga is strictly sequential — exactly one command is in flight per order, and the next reserve
 * is submitted only once the previous one's `InventoryReservedEvent` has come back through the
 * `order-saga` processor. So on ES-4 each of an order's K steps is re-queued at the TAIL of
 * [CommandGatewayConfig.sagaCommandExecutor]'s unbounded `LinkedBlockingQueue`, behind every
 * command that orders arriving in the meantime submitted. Nothing throttles those arrivals, so
 * under saturation the queue grows without bound and per-saga latency grows with it — Little's law,
 * latency = WIP / throughput. Half-finished sagas hold their reservations for that whole time,
 * feeding contention back into the reserve path they are waiting on.
 *
 * This gate is the closed loop ES-4 does not have: it caps how much INCOMING work may sit in front
 * of an in-flight saga's next step.
 *
 * ## Only the front door is gated
 *
 * [OrderReservationSaga][pl.szymanski.wiktor.domain.saga.OrderReservationSaga] routes the FIRST
 * reserve of a new order through here and everything else — continuations, completion, failure,
 * compensation — straight to the ungated `sagaCommandExecutor`. Retries bypass it too;
 * [ConcurrencyRetryScheduler] still hands off to the pool directly. That is the point of the
 * branch, not an omission: already-admitted work must never queue behind new arrivals.
 *
 * ## Why the permit comes back at DEQUEUE and not at saga end
 *
 * The obvious design — N sagas alive at once, permit taken at `@StartSaga` and returned at
 * `SagaLifecycle.end()` — DEADLOCKS on a TrackingEventProcessor, and silently. A permit would be
 * returned only by a saga processor thread handling `InventoryReservedEvent`, and those
 * continuations queue behind the very segment threads that are blocked here waiting for a permit.
 * With every segment thread blocked in `@StartSaga`, no saga can advance, so no permit is ever
 * returned, at any capacity.
 *
 * The releasing party therefore has to be one that never blocks on this gate: the command pool.
 * The permit is returned as the task LEAVES the queue, immediately before it runs, so `capacity`
 * is exactly the number of incoming saga starts allowed to be WAITING — not the number of sagas in
 * flight. The extra wait an in-flight saga pays per step is then bounded by
 * `capacity / COMMAND_POOL_SIZE` command-pool turns, which is what makes the knob mean something
 * arithmetic rather than merely "smaller is more throttled".
 *
 * ## What blocking costs
 *
 * A blocked caller is an `order-saga` segment thread inside its Unit of Work, so the
 * TrackingEventProcessor stops reading `OrderCreatedEvent`s and the backlog waits in the DURABLE
 * event store rather than on the heap. No order is shed. It holds the saga-store connection it
 * already held and takes no new one, so the connection budget in
 * [CommandGatewayConfig]'s `RETRY_POOL_SIZE` doc is unchanged — blocking here is the one form of
 * backpressure that does not spend the pool. It does hold that connection LONGER, which is the one
 * way this change could bite: watch `hikaricp_connections_timeout_total{pool="axon-jdbc-pool"}`.
 *
 * ## Liveness over isolation
 *
 * The wait has a timeout, and on expiry the task is submitted ANYWAY rather than dropped or
 * rejected. Same disposition [ConcurrencyRetryScheduler]'s rejected hand-off and the saga's
 * `abandon()` already take, for the same reason: a lost start appends no event, the gateway
 * callback never completes, and the order sits PENDING forever with no counter and no log. A run in
 * which that happened is not a run with a bound — `saga.intake.timeout` is what says so.
 */
class SagaIntakeGate(
    /** Public so a run's wiring can be asserted deterministically, without racing live permits. */
    val capacity: Int,
    private val delegate: Executor,
    meterRegistry: MeterRegistry,
    private val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
) : Executor {

    companion object {
        private val log = LoggerFactory.getLogger(SagaIntakeGate::class.java)

        /**
         * Deliberately far longer than any legitimate wait: this is a stuck-system escape hatch,
         * not a policy knob. A gate that gave up in, say, 500 ms would quietly stop bounding
         * anything exactly when the bound started to bind, and the run would look like a success.
         */
        const val DEFAULT_ACQUIRE_TIMEOUT_MS = 60_000L
    }

    /**
     * Fair. Unfair would let a segment thread that just released be handed the slot back
     * immediately, starving another segment indefinitely — and a starved segment is not a slow
     * segment, it is a set of orders that never start. The cost is one hand-off per admission on a
     * path that already crosses a thread boundary.
     */
    private val permits = Semaphore(capacity, true)

    /**
     * THE headline series of this branch: backpressure made visible. Without it a run that got
     * faster and a run that merely moved its wait from the command queue into the event stream look
     * identical.
     *
     * The 1us floor is not cosmetic. Micrometer's default Timer minimum is 1 ms, which would make
     * `le=0.001` the first bucket; an uncontended acquire is sub-microsecond, so every such sample
     * would land in it and `histogram_quantile` — which interpolates the first bucket linearly from
     * 0 — would report a flat q x 1 ms no matter what the gate actually cost. The ceiling is the
     * acquire timeout because the wait cannot exceed it by construction.
     */
    private val waitTimer: Timer = Timer.builder("saga.intake.wait")
        .description("Time an order-saga segment thread spent waiting for an intake slot")
        .publishPercentileHistogram(true)
        .minimumExpectedValue(Duration.ofNanos(1_000))
        .maximumExpectedValue(Duration.ofMillis(acquireTimeoutMs))
        .register(meterRegistry)

    /** Non-zero means the bound was breached and the run's intake was NOT capacity. */
    private val timeoutCounter: Counter = Counter.builder("saga.intake.timeout")
        .description("Intake waits that expired and were admitted anyway")
        .register(meterRegistry)

    init {
        // Pinned at 0 = the gate is the binding constraint, which is the reading the whole branch
        // exists to produce. Published next to a gauge of the bound itself so a dashboard never has
        // to be told out of band what AXON_SAGA_INTAKE_CAPACITY was for the run.
        Gauge.builder("saga.intake.permits.available", permits) { it.availablePermits().toDouble() }
            .description("Free intake slots")
            .register(meterRegistry)
        Gauge.builder("saga.intake.blocked", permits) { it.queueLength.toDouble() }
            .description("order-saga segment threads currently waiting for an intake slot")
            .register(meterRegistry)
        Gauge.builder("saga.intake.capacity", this) { capacity.toDouble() }
            .description("Configured intake bound (AXON_SAGA_INTAKE_CAPACITY)")
            .register(meterRegistry)
    }

    /** Test seam; also what makes a leaked permit assertable rather than merely suspected. */
    fun availablePermits(): Int = permits.availablePermits()

    override fun execute(command: Runnable) {
        val start = System.nanoTime()
        val acquired = acquire()
        waitTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)

        var handedOff = false
        try {
            delegate.execute {
                // BEFORE the task, not after: the permit bounds the queue, not the work. See the
                // class doc for why the difference is a deadlock and not a preference.
                if (acquired) permits.release()
                command.run()
            }
            handedOff = true
        } finally {
            // Only reachable when delegate.execute threw — RejectedExecutionException, i.e. the
            // pool is shutting down. Propagated rather than swallowed, exactly as ES-4 behaves
            // today since the saga does not catch it either; what must not happen is the permit
            // leaking, which would shrink the bound by one for the life of the JVM.
            if (!handedOff && acquired) permits.release()
        }
    }

    private fun acquire(): Boolean = try {
        val acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)
        if (!acquired) {
            timeoutCounter.increment()
            log.warn(
                "[INTAKE] waited {}ms for one of {} slots and gave up — admitting anyway. The " +
                    "intake bound is NOT in force for this start; a run with a non-zero " +
                    "saga_intake_timeout did not measure capacity={}.",
                acquireTimeoutMs, capacity, capacity,
            )
        }
        acquired
    } catch (e: InterruptedException) {
        // Shutdown, or a processor stopping. Restore the flag so the caller's own shutdown checks
        // still see it, and admit: dropping the start here strands the order in PENDING.
        Thread.currentThread().interrupt()
        timeoutCounter.increment()
        log.warn("[INTAKE] interrupted while waiting for a slot — admitting anyway", e)
        false
    }
}
