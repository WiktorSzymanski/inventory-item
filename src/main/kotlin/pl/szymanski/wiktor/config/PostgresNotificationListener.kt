package pl.szymanski.wiktor.config

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.sql.DriverManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Primary delivery path: LISTENs on the channel fed by the V2 trigger and uses each
 * notification purely as a wake-up for [EventDrainLoop], which reads and delivers the
 * outbox in bounded pages.
 *
 * The notification's payload — the publication id — is deliberately ignored. This branch used
 * to deliver that one row immediately, one executor task per NOTIFY, which is an open loop: a
 * commit burst became a delivery burst with nothing throttling it. Here a burst collapses
 * into a single drain pass and deliveries in flight never exceed the pool width. The V2
 * trigger and the schema are unchanged, so the id is still sent; only this side stops reading
 * it.
 *
 * Uses its own standalone JDBC connection rather than borrowing one from HikariCP, so the
 * pool keeps its full size and its maxLifetime/leak detection never touch the long-lived
 * LISTEN session. If the connection drops, the loop reconnects with backoff and signals a
 * drain, which delivers anything inserted while no LISTEN was active — the same pass that
 * covers publications left over from before startup.
 */
@Component
class PostgresNotificationListener(
    processor: EventPublicationDirectProcessor,
    cursorStore: OutboxCursorStore,
    // The pool is a bean now, not built here: IncompleteEventRepublisher submits to the same one,
    // so the two delivery processes share one width instead of each having their own. See
    // PollingEventPublicationConfig.eventDeliveryExecutor.
    @Qualifier("eventDeliveryExecutor") executor: ExecutorService,
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val dbUser: String,
    @Value("\${spring.datasource.password}") private val dbPassword: String,
    @Value("\${app.event-delivery.batch-size:1000}") private val batchSize: Int,
    @Value("\${app.outbox-cursor.enabled:true}") private val cursorEnabled: Boolean,
) {
    private val channel = "event_publication_notify"

    private val drainLoop = EventDrainLoop(processor, executor, batchSize, cursorStore, cursorEnabled)

    @Volatile
    private var running = true
    private lateinit var listenerThread: Thread
    private lateinit var drainThread: Thread

    @PostConstruct
    fun start() {
        drainThread = Thread(drainLoop::runLoop, "pg-notify-drain").apply { isDaemon = true }
        drainThread.start()
        listenerThread = Thread(::connectionLoop, "pg-notify-listener").apply { isDaemon = true }
        listenerThread.start()
        log.info(
            "[OUTBOX] drain mode={} (app.outbox-cursor.enabled), batch-size={}",
            if (cursorEnabled) "CURSOR" else "SCAN", batchSize,
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
                    // NOTIFYs sent while no LISTEN was active are gone; a drain pass picks up
                    // everything still incomplete, so it doubles as the catch-up.
                    drainLoop.signal()
                    val pgConnection = connection.unwrap(PGConnection::class.java)
                    while (running) {
                        // Parks until PostgreSQL sends a NOTIFY packet (sub-millisecond wake-up);
                        // the 5 s timeout only serves as a shutdown check. However many
                        // notifications arrive, they mean one thing: there is work to drain.
                        val notifications = pgConnection.getNotifications(5_000)
                        if (!notifications.isNullOrEmpty()) drainLoop.signal()
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

    @PreDestroy
    fun stop() {
        running = false
        drainLoop.stop()
        listenerThread.interrupt()
        drainThread.join(TimeUnit.SECONDS.toMillis(5))
        // The delivery pool is shut down by Spring (eventDeliveryExecutor's destroyMethod). Doing
        // it here as well would close it under the sweep, which is still a live bean at this point.
        log.info("PostgreSQL LISTEN stopped")
    }

    companion object {
        private val log = LoggerFactory.getLogger(PostgresNotificationListener::class.java)
    }
}
