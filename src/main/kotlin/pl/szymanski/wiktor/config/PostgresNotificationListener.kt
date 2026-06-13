package pl.szymanski.wiktor.config

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Primary delivery path: LISTENs on the channel fed by the V2 trigger and routes each notified
 * publication id to [EventPublicationDirectProcessor] on a small worker pool (safe to parallelize
 * because the processor's claim UPDATE makes delivery idempotent per row).
 *
 * Uses its own standalone JDBC connection rather than borrowing one from HikariCP, so the pool
 * keeps its full size and its maxLifetime/leak detection never touch the long-lived LISTEN
 * session. If the connection drops, the loop reconnects with backoff; after every (re)connect a
 * catch-up pass delivers anything inserted while no LISTEN was active — which also covers
 * publications left over from before startup.
 */
@Component
class PostgresNotificationListener(
    private val processor: EventPublicationDirectProcessor,
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val dbUser: String,
    @Value("\${spring.datasource.password}") private val dbPassword: String,
    @Value("\${app.event-delivery.threads:8}") private val deliveryThreads: Int,
) {
    private val channel = "event_publication_notify"

    @Volatile
    private var running = true
    private lateinit var listenerThread: Thread
    private lateinit var executor: ExecutorService

    @PostConstruct
    fun start() {
        executor = Executors.newFixedThreadPool(deliveryThreads) { r ->
            Thread(r, "event-delivery").apply { isDaemon = true }
        }
        listenerThread = Thread(::connectionLoop, "pg-notify-listener").apply { isDaemon = true }
        listenerThread.start()
    }

    private fun connectionLoop() {
        var backoffMillis = 1_000L
        while (running) {
            try {
                DriverManager.getConnection(jdbcUrl, dbUser, dbPassword).use { connection ->
                    connection.createStatement().use { it.execute("LISTEN $channel") }
                    log.info("PostgreSQL LISTEN started on channel '{}'", channel)
                    backoffMillis = 1_000L
                    catchUp()
                    val pgConnection = connection.unwrap(PGConnection::class.java)
                    while (running) {
                        // Parks until PostgreSQL sends a NOTIFY packet (sub-millisecond wake-up);
                        // the 5 s timeout only serves as a shutdown check.
                        pgConnection.getNotifications(5_000)?.forEach { notification ->
                            submit(notification.parameter)
                        }
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

    private fun submit(rawId: String) {
        executor.submit {
            runCatching { processor.process(UUID.fromString(rawId)) }
                .onFailure { e -> log.error("Failed to deliver publication {}", rawId, e) }
        }
    }

    // NOTIFYs sent while no LISTEN was active are gone; pick up everything still incomplete.
    private fun catchUp() {
        val pending = processor.findIncompleteIds(Duration.ZERO)
        if (pending.isEmpty()) return
        log.info("Catch-up: delivering {} incomplete publication(s)", pending.size)
        pending.forEach { submit(it.toString()) }
    }

    @PreDestroy
    fun stop() {
        running = false
        listenerThread.interrupt()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        log.info("PostgreSQL LISTEN stopped")
    }

    companion object {
        private val log = LoggerFactory.getLogger(PostgresNotificationListener::class.java)
    }
}
