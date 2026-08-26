package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement

/**
 * The contract that replaces the V2 `AFTER INSERT ... FOR EACH ROW` trigger: notifications are
 * raised on a fixed tick by a background thread, not from the writer's own transaction. Three
 * properties are load-bearing and each is asserted here.
 *
 * It must collapse by TICK, not by call: [OutboxNotifyCoalescer.signal] costs nothing (no query,
 * no lock), so however many orders call it between two flushes must still turn into exactly one
 * `pg_notify`. That is a different axis than TO-1-2's per-transaction collapse — it is what
 * actually caps the async-notify commit lock's acquisition rate, independent of order rate.
 *
 * It must not send on a tick with nothing pending — an idle system should not be issuing
 * `pg_notify` 50 times a second for no reason.
 *
 * And a failed send must not lose the signal: [OutboxNotifyCoalescer.flushIfPending] clears the
 * flag before sending, so it re-arms it on failure rather than silently dropping the wake-up for a
 * transient connection error.
 */
class OutboxNotifyCoalescerTest {

    private val registry = SimpleMeterRegistry()

    private fun coalescer() =
        OutboxNotifyCoalescer(
            jdbcUrl = "jdbc:postgresql://localhost/test",
            dbUser = "u",
            dbPassword = "p",
            coalesceIntervalMillis = 20,
            meterRegistry = registry,
        )

    private fun signalledCount() = registry.counter("outbox.notify.signalled").count().toInt()
    private fun flushedCount() = registry.counter("outbox.notify.flushed").count().toInt()

    private fun mockConnection(): Pair<Connection, Statement> {
        val statement = mockk<Statement>(relaxed = true)
        val connection = mockk<Connection>()
        every { connection.createStatement() } returns statement
        return connection to statement
    }

    @Test
    fun `a tick with nothing signalled sends nothing`() {
        val (connection, statement) = mockConnection()

        coalescer().flushIfPending(connection)

        verify(exactly = 0) { statement.execute(any<String>()) }
        assertEquals(0, flushedCount())
    }

    @Test
    fun `many signals between two ticks collapse into one notify`() {
        val (connection, statement) = mockConnection()
        val coalescer = coalescer()

        repeat(500) { coalescer.signal() }
        coalescer.flushIfPending(connection)

        verify(exactly = 1) { statement.execute("SELECT pg_notify('event_publication_notify', '')") }
        assertEquals(500, signalledCount())
        assertEquals(1, flushedCount())
    }

    @Test
    fun `a second tick with no new signal since the last flush sends nothing`() {
        val (connection, statement) = mockConnection()
        val coalescer = coalescer()

        coalescer.signal()
        coalescer.flushIfPending(connection)
        coalescer.flushIfPending(connection)

        verify(exactly = 1) { statement.execute(any<String>()) }
        assertEquals(1, flushedCount())
    }

    @Test
    fun `a signal arriving after the flag is cleared is served by the next tick`() {
        val (connection, statement) = mockConnection()
        val coalescer = coalescer()

        coalescer.signal()
        coalescer.flushIfPending(connection) // clears the flag, sends
        coalescer.signal() // arrives after the clear
        coalescer.flushIfPending(connection) // must send again

        verify(exactly = 2) { statement.execute(any<String>()) }
        assertEquals(2, flushedCount())
    }

    @Test
    fun `a failed send re-arms the flag instead of losing the signal`() {
        val failingStatement = mockk<Statement>()
        every { failingStatement.execute(any<String>()) } throws SQLException("boom")
        val failingConnection = mockk<Connection>()
        every { failingConnection.createStatement() } returns failingStatement

        val coalescer = coalescer()
        coalescer.signal()

        runCatching { coalescer.flushIfPending(failingConnection) }

        // Next tick, on a healthy connection, must still see the pending work — nothing was lost.
        val (goodConnection, goodStatement) = mockConnection()
        coalescer.flushIfPending(goodConnection)

        verify(exactly = 1) { goodStatement.execute(any<String>()) }
        assertEquals(1, flushedCount())
    }
}
