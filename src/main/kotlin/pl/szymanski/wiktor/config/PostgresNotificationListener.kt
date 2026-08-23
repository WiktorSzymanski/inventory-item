package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.sql.DriverManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Primary delivery path: LISTENs on the channel fed by the V2 trigger and delivers each notified
 * publication straight from the notification's own payload.
 *
 * **This branch reads the payload; TO-2 throws it away.** That is the single difference between
 * them. Since V10 the trigger sends the publication's id, event type, listener id and serialized
 * event, so a notification is the event rather than a doorbell, and [EventPublicationDirectProcessor]
 * can claim and deliver without ever finding the row first. The outbox is still written and still
 * durable — it is simply not on the delivery path any more, which is what makes V8's cursor and its
 * partial index dead weight here rather than load-bearing. They are deliberately left in place so
 * the A/B against TO-2 varies one thing.
 *
 * **Backpressure is the price, and it is paid explicitly.** A payload-carrying notification cannot
 * be coalesced — each one carries different bytes — so this submits per row, which is the shape
 * commit 2185068 removed as an open loop. What makes it safe now is that `eventDeliveryExecutor` is
 * bounded and its rejection policy blocks (see [BlockingSubmitPolicy]): when deliveries fall behind,
 * this thread stops reading notifications, PostgreSQL's async-notify queue grows, and committing
 * writers slow down at pg_notify. The bound is enforced against the producers instead of being
 * absorbed by an unbounded queue. `outbox.notify.queue.depth` and `outbox.notify.queue.usage` are
 * the two sides of that, client and server.
 *
 * [EventDrainLoop] survives as a recovery path only. It is signalled on every (re)connect, where it
 * delivers whatever was inserted while no LISTEN was active, and when a notification cannot be
 * parsed — never per notification.
 *
 * Uses its own standalone JDBC connection rather than borrowing one from HikariCP, so the pool keeps
 * its full size and its maxLifetime/leak detection never touch the long-lived LISTEN session. If the
 * connection drops, the loop reconnects with backoff.
 */
@Component
class PostgresNotificationListener(
    private val processor: EventPublicationDirectProcessor,
    cursorStore: OutboxCursorStore,
    private val objectMapper: ObjectMapper,
    // The pool is a bean, not built here: IncompleteEventRepublisher submits to the same one, so the
    // two delivery processes share one width instead of each having their own. See
    // PollingEventPublicationConfig.eventDeliveryExecutor.
    @Qualifier("eventDeliveryExecutor") private val executor: ExecutorService,
    private val meterRegistry: MeterRegistry,
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val dbUser: String,
    @Value("\${spring.datasource.password}") private val dbPassword: String,
    @Value("\${app.event-delivery.batch-size:1000}") private val batchSize: Int,
    @Value("\${app.outbox-cursor.enabled:true}") private val cursorEnabled: Boolean,
) {
    private val channel = "event_publication_notify"

    private val drainLoop = EventDrainLoop(processor, executor, batchSize, cursorStore, cursorEnabled)

    /** Notifications parsed and submitted — the push path's throughput. */
    private val received: Counter = meterRegistry.counter("outbox.notify.received")

    /**
     * Notifications that could not be parsed and fell back to a drain pass.
     *
     * Expected to be zero. Anything else means the trigger and this consumer disagree about the wire
     * format — a version skew that would otherwise hide behind a merely slower path, since the
     * fallback still delivers the row.
     */
    private val unparsed: Counter = meterRegistry.counter("outbox.notify.unparsed")

    @Volatile
    private var running = true
    private lateinit var listenerThread: Thread
    private lateinit var drainThread: Thread

    @PostConstruct
    fun start() {
        // Deliveries queued but not started. Rising means the pool is the constraint; pinned at the
        // capacity means this listener is blocking on submit and the bound has reached PostgreSQL.
        (executor as? ThreadPoolExecutor)?.let { pool ->
            meterRegistry.gauge("outbox.notify.queue.depth", pool) { it.queue.size.toDouble() }
        }

        drainThread = Thread(drainLoop::runLoop, "pg-notify-drain").apply { isDaemon = true }
        drainThread.start()
        listenerThread = Thread(::connectionLoop, "pg-notify-listener").apply { isDaemon = true }
        listenerThread.start()
        log.info(
            "[OUTBOX] delivery mode=PUSH (payload in NOTIFY), pool={}, queue-capacity={}, recovery drain={}",
            (executor as? ThreadPoolExecutor)?.maximumPoolSize ?: -1,
            (executor as? ThreadPoolExecutor)?.let { it.queue.size + it.queue.remainingCapacity() } ?: -1,
            if (cursorEnabled) "CURSOR" else "SCAN",
        )
    }

    private fun connectionLoop() {
        var backoffMillis = 1_000L
        while (running) {
            try {
                DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { connection ->
                    connection.createStatement().use { it.execute("LISTEN $channel") }
                    log.info("PostgreSQL LISTEN started on channel '{}'", channel)
                    backoffMillis = 1_000L
                    // NOTIFYs sent while no LISTEN was active are gone, and oversize events never
                    // sent one at all; a drain pass picks up everything still incomplete, so it
                    // doubles as the catch-up.
                    drainLoop.signal()
                    val pgConnection = connection.unwrap(PGConnection::class.java)
                    while (running) {
                        // Parks until PostgreSQL sends a NOTIFY packet (sub-millisecond wake-up);
                        // the 5 s timeout only serves as a shutdown check.
                        pgConnection.getNotifications(5_000)?.forEach { deliver(it.parameter) }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                if (!running) return
                log.error("pg-notify connection failed, reconnecting in {} ms", backoffMillis, e)
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
     * Submit one notified publication for delivery.
     *
     * `executor.submit` is where this thread blocks when the queue is full — deliberately, and it is
     * the only backpressure in the design, so nothing here may swallow it. A failure to PARSE is a
     * different matter: the row is committed regardless, so falling back to a drain pass delivers it
     * anyway and the counter records that the fast path was missed.
     */
    private fun deliver(raw: String) {
        val pub = try {
            NotifiedPublication.parse(objectMapper, raw)
        } catch (e: Exception) {
            unparsed.increment()
            log.error("Unparseable NOTIFY payload, falling back to a drain pass", e)
            drainLoop.signal()
            return
        }

        received.increment()
        executor.submit {
            runCatching { processor.process(pub) }
                .onFailure { e -> log.error("Failed to deliver publication {}", pub.id, e) }
        }
    }

    @PreDestroy
    fun stop() {
        running = false
        drainLoop.stop()
        listenerThread.interrupt()
        drainThread.join(TimeUnit.SECONDS.toMillis(5))
        // The delivery pool is shut down by Spring (eventDeliveryExecutor's destroyMethod). Doing it
        // here as well would close it under the sweep, which is still a live bean at this point.
        log.info("PostgreSQL LISTEN stopped")
    }

    companion object {
        private val log = LoggerFactory.getLogger(PostgresNotificationListener::class.java)
    }
}
