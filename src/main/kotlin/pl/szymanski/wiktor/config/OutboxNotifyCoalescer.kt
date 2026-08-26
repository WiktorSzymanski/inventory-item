package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raises `event_publication_notify` from a single background thread on a fixed tick, not from the
 * writer's own transaction. Replaces V2's `AFTER INSERT ... FOR EACH ROW` trigger.
 *
 * **Why the trigger had to go, and why V9-style "one call per transaction" would not have been
 * enough.** Postgres must deliver NOTIFYs in commit order, and it doesn't know a transaction's
 * commit order until that transaction is done committing — so it holds one process-wide exclusive
 * lock across the commit-and-fsync of *every* transaction that has a pending NOTIFY, serializing
 * those commits against each other and defeating group commit. That lock is taken once per
 * COMMIT, not once per `pg_notify()` call inside it, so collapsing N notifications per order down
 * to 1 (the shape TO-1-2's V9->V10 tried) does not shrink the thing that actually hurts: it is
 * still one notify-bearing transaction per order either way. TO-1-2's own capacity runs bear this
 * out — dropped-iteration ratio 8.28% -> 8.11% and backlog 40,523 -> 40,030 for a 5x cut in calls
 * per transaction, i.e. no real change. (github.com/dbos-inc, "Scaling Postgres LISTEN/NOTIFY",
 * describes the identical mechanism and fix shape for DBOS's streams: 2.9k writes/s at the lock,
 * 60k/s once emission moved off the write path.)
 *
 * **The fix collapses by wall-clock tick instead of by transaction.** [signal] is a plain
 * [AtomicBoolean] write — no query, no connection, no lock — so it costs the write path nothing
 * regardless of how many orders call it between two flushes. [flushLoop] wakes on its own schedule,
 * and if anything is pending, clears the flag and raises exactly one `pg_notify` in its OWN
 * transaction. However many orders committed in that window, the async-notify lock is acquired
 * once. Throughput of notify-bearing transactions is capped at `1000 / coalesceIntervalMillis`,
 * flat, independent of order rate — which is the entire point.
 *
 * **This is a latency/lock-contention trade, made explicitly.** A signal can wait up to
 * [coalesceIntervalMillis] before it turns into a NOTIFY, and a burst of drains does not learn
 * about a burst of orders as N wake-ups, only as one. [PostgresNotificationListener] already turns
 * an unbounded number of notifications into one drain pass, and [EventDrainLoop]'s drain is a page
 * scan keyed on `seq`/`xact_id`, not on the notify payload — so a coarser, coalesced wake-up costs
 * this branch nothing beyond that latency. `IncompleteEventRepublisher`'s sweep and the
 * reconnect-triggers-a-drain path in [PostgresNotificationListener] are the same backstops they
 * always were: a flush this loop drops (a failed `pg_notify`, a restart mid-window) is not a
 * correctness gap, only a slower one.
 *
 * **Correctness under commit order stays exactly what the trigger gave it.** [signalAfterCommit]
 * — see [PollingOnlyEventMulticaster] — only sets the flag from `afterCommit`, so a rolled-back
 * order can never raise a wake-up for a row that does not exist. That is stronger than the trigger
 * ever was: `pg_notify` inside a transaction is atomic with COMMIT regardless of where it is
 * called, but the trigger fired unconditionally at INSERT time and only avoided notifying a rolled
 * back write because Postgres discards the queued message on ROLLBACK — an outcome, not a
 * guarantee this class needs to lean on, since it never queues anything until the row is durable.
 *
 * Own standalone JDBC connection, matching [PostgresNotificationListener]'s reasoning: Hikari's
 * pool keeps its configured size and its maxLifetime/leak detection never see a session this loop
 * holds open indefinitely.
 */
@Component
class OutboxNotifyCoalescer(
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val dbUser: String,
    @Value("\${spring.datasource.password}") private val dbPassword: String,
    @Value("\${app.outbox-notify.coalesce-interval-ms:20}") private val coalesceIntervalMillis: Long,
    meterRegistry: MeterRegistry,
) {
    /** Writes calling [signal] between two flushes; a ratio against [flushed] is the collapse rate. */
    private val signalled: Counter = meterRegistry.counter("outbox.notify.signalled")

    /** Notifications actually sent — the number that pays the async-notify commit lock. */
    private val flushed: Counter = meterRegistry.counter("outbox.notify.flushed")

    private val pending = AtomicBoolean(false)

    @Volatile
    private var running = true
    private lateinit var flushThread: Thread

    /** Pure in-memory: safe to call from any thread, any number of times, inside or outside a transaction. */
    fun signal() {
        signalled.increment()
        pending.set(true)
    }

    @PostConstruct
    fun start() {
        flushThread = Thread(::flushLoop, "outbox-notify-flush").apply { isDaemon = true }
        flushThread.start()
    }

    private fun flushLoop() {
        var backoffMillis = 1_000L
        while (running) {
            try {
                DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { connection ->
                    backoffMillis = 1_000L
                    while (running) {
                        Thread.sleep(coalesceIntervalMillis)
                        flushIfPending(connection)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                if (!running) return
                log.error("[NOTIFY] flush connection failed, reconnecting in {} ms", backoffMillis, e)
                try {
                    Thread.sleep(backoffMillis)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                backoffMillis = (backoffMillis * 2).coerceAtMost(30_000L)
            }
        }
    }

    /**
     * Clears the flag BEFORE sending, mirroring [EventDrainLoop.runLoop]'s `pending.set(false)`
     * ordering: a [signal] landing between the clear and the `pg_notify` call re-arms the flag and
     * is served by the NEXT tick rather than lost. If the send itself fails, the flag is put back
     * — a transient failure here costs one extra tick of latency, not a dropped wake-up, and the
     * outer loop's reconnect still runs.
     */
    internal fun flushIfPending(connection: Connection) {
        if (!pending.compareAndSet(true, false)) return
        try {
            connection.createStatement().use { it.execute(NOTIFY_SQL) }
            flushed.increment()
        } catch (e: Exception) {
            pending.set(true)
            throw e
        }
    }

    @PreDestroy
    fun stop() {
        running = false
        flushThread.interrupt()
        flushThread.join(TimeUnit.SECONDS.toMillis(5))
        log.info("[NOTIFY] flush loop stopped")
    }

    companion object {
        private val log = LoggerFactory.getLogger(OutboxNotifyCoalescer::class.java)

        /** Shared with [PostgresNotificationListener]; the two ends must never drift apart. */
        const val CHANNEL = "event_publication_notify"

        /** No payload — same reasoning as TO-1-2's V10: nothing reads it, the drain reads `seq`/`xact_id`. */
        private const val NOTIFY_SQL = "SELECT pg_notify('$CHANNEL', '')"
    }
}
