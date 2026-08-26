package pl.szymanski.wiktor.config

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * LISTENs on the channel [OutboxNotifier] writes to and **discards every notification**.
 *
 * That is the whole point of this branch, not an unfinished port. Delivery stays entirely with
 * [pl.szymanski.wiktor.publisher.OutboxPollingPublisher]'s tick; the notification is received and
 * dropped, so a run measures what the channel COSTS — the `pg_notify` on the writer's commit path,
 * the queue in PostgreSQL, the wake-up here — with none of what it buys. Against a plain TO-1 run
 * that is a clean single-variable difference. TO-2 is where the notification actually drives a
 * drain.
 *
 * **What is on the channel changed with V10 and the comparison to TO-2 changed with it.** The V9
 * trigger raised one message per `event_publication` row, carrying the publication id — byte for
 * byte what TO-2's V2 trigger still raises. [OutboxNotifier] raises one empty message per
 * transaction instead, so a DISTINCT_ITEMS-line order puts 1 message on the channel here against
 * TO-2's N+1. TO-1-2 and TO-2 therefore no longer carry the same traffic, and a reading across the
 * two is no longer the single-variable one V9's comment claimed it was. TO-1-2 against plain TO-1
 * is untouched: that pair still differs only by the channel, which is the comparison this branch
 * exists for. `outbox.notify.sent` counts what the writers raised; [received] counts what arrived
 * here and is logged on shutdown, so the two ends of the channel can be checked against each
 * other after a run.
 *
 * Because nothing acts on it, a notification is read as far as counting it and no further (there
 * is no payload left to read), and there is no reconnect catch-up to perform: notifications missed
 * while the connection was down are simply more notifications this branch would have thrown away.
 * Reconnection with backoff exists only so the channel keeps being consumed for the length of a
 * run — an un-listened NOTIFY is discarded by PostgreSQL itself and would silently turn the cost
 * being measured into a cheaper one.
 *
 * Uses its own standalone JDBC connection rather than borrowing one from HikariCP, so the pool
 * keeps its full size and its maxLifetime/leak detection never touch the long-lived LISTEN
 * session.
 */
@Component
class PostgresNotificationListener(
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val dbUser: String,
    @Value("\${spring.datasource.password}") private val dbPassword: String,
) {
    private val channel = OutboxNotifier.CHANNEL
    private val received = AtomicLong()

    @Volatile
    private var running = true
    private lateinit var listenerThread: Thread

    @PostConstruct
    fun start() {
        listenerThread = Thread(::connectionLoop, "pg-notify-listener").apply { isDaemon = true }
        listenerThread.start()
    }

    private fun connectionLoop() {
        var backoffMillis = 1_000L
        while (running) {
            try {
                DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { connection ->
                    connection.createStatement().use { it.execute("LISTEN $channel") }
                    log.info("[NOTIFY] LISTEN started on channel '{}' — notifications are discarded", channel)
                    backoffMillis = 1_000L
                    val pgConnection = connection.unwrap(PGConnection::class.java)
                    while (running) {
                        // Parks until PostgreSQL sends a NOTIFY packet; the 5 s timeout only
                        // serves as a shutdown check. Reading them is what drains the backend's
                        // queue — the count is the only thing kept.
                        val notifications = pgConnection.getNotifications(5_000)
                        if (!notifications.isNullOrEmpty()) {
                            val total = received.addAndGet(notifications.size.toLong())
                            log.trace("[NOTIFY] discarded {} notification(s), {} total", notifications.size, total)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                if (!running) return
                log.error("[NOTIFY] connection failed, reconnecting in {} ms", backoffMillis, e)
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
        listenerThread.interrupt()
        listenerThread.join(TimeUnit.SECONDS.toMillis(5))
        log.info("[NOTIFY] LISTEN stopped after discarding {} notification(s)", received.get())
    }

    companion object {
        private val log = LoggerFactory.getLogger(PostgresNotificationListener::class.java)
    }
}
