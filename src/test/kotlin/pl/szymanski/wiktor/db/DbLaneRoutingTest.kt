package pl.szymanski.wiktor.db

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * The split is worth nothing if work lands on the wrong pool, and every way that can happen is
 * silent: the run still succeeds, the numbers are just drawn from the wrong budget.
 *
 * These are unit tests over mocked DataSources rather than an integration test, because the TO
 * branches carry no Testcontainers dependency. What they do cover is the part that is easy to get
 * wrong — that the routing key is read when the TRANSACTION starts, not when the repository call
 * is made, and that a lane never leaks across tasks on a pooled thread.
 */
class DbLaneRoutingTest {

    private val appConnection: Connection = mockk(relaxed = true)
    private val writeConnection: Connection = mockk(relaxed = true)
    private val appPool: DataSource = mockk { every { connection } returns appConnection }
    private val writePool: DataSource = mockk { every { connection } returns writeConnection }
    private val routing = LaneRoutingDataSource(appPool, writePool)

    @Test
    fun `unmarked work goes to the app pool`() {
        assertEquals(DbLane.APP, DbLaneContext.current(), "APP must be the default lane")
        assertSame(appConnection, routing.connection)
    }

    @Test
    fun `the write lane goes to the write pool`() {
        DbLaneContext.on(DbLane.WRITE) {
            assertSame(writeConnection, routing.connection)
        }
    }

    @Test
    fun `a transaction opened on the write lane draws from the write pool`() {
        // The real mechanism: DataSourceTransactionManager resolves the DataSource once, at
        // transaction start, and binds that connection for the whole transaction. If the lane were
        // consulted any later — say inside a @Transactional handler — this would return the app
        // connection instead.
        val transactionManager = DataSourceTransactionManager(routing)
        val bound = AtomicReference<Connection>()

        DbLaneContext.on(DbLane.WRITE) {
            TransactionTemplate(transactionManager).execute {
                bound.set(
                    org.springframework.jdbc.datasource.DataSourceUtils.getConnection(routing)
                )
            }
        }

        assertSame(writeConnection, bound.get(), "the transaction did not bind a write-pool connection")
    }

    @Test
    fun `the lane is restored so it cannot leak to the next task on a pooled thread`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val seenAfterWriteTask = AtomicReference<DbLane>()

            pool.submit(DbLaneContext.wrap(DbLane.WRITE) { /* a write task */ }).get(5, TimeUnit.SECONDS)
            pool.submit { seenAfterWriteTask.set(DbLaneContext.current()) }.get(5, TimeUnit.SECONDS)

            assertEquals(
                DbLane.APP, seenAfterWriteTask.get(),
                "a later task on the same pooled thread inherited the WRITE lane — every read on " +
                    "that thread would silently draw from the write pool",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `nesting restores the outer lane rather than resetting to the default`() {
        DbLaneContext.on(DbLane.WRITE) {
            DbLaneContext.on(DbLane.APP) { assertEquals(DbLane.APP, DbLaneContext.current()) }
            assertEquals(DbLane.WRITE, DbLaneContext.current(), "inner block reset the lane to the default")
        }
    }
}
