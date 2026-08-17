package pl.szymanski.wiktor.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shipped defaults must satisfy the invariant the branch rests on: the write pool covers every
 * thread that can demand a write connection, so no lane can starve another.
 *
 * This is a test rather than a comment because the numbers are spread across three files —
 * `server.tomcat.threads.max`, `app.order-worker.threads`, `app.order-retry.threads` and
 * `app.db.write-pool-size` — and a sweep that raises one of them re-opens the starvation window
 * without touching anything that looks like a pool setting.
 */
class WriteLaneCoverageTest {

    // The shipped defaults. tomcat and worker come from application.yaml, which overrides the
    // lower in-code defaults on OrderWorkerProperties; retry and the pool size are read from the
    // property classes so those two cannot drift from the assertion silently.
    private val tomcatThreads = 99
    private val workerThreads = 150
    private val retryThreads = OrderRetryProperties().threads
    private val writePoolSize = DbPoolProperties().writePoolSize

    @Test
    fun `the default write pool covers every thread that can demand it`() {
        val demand = DataSourceConfig.writeLaneDemand(tomcatThreads, workerThreads, retryThreads)

        assertEquals(300, demand, "write-lane demand changed; application.yaml's derivation comment is now stale")
        assertTrue(
            writePoolSize >= demand,
            "write-pool-size=$writePoolSize cannot cover $demand demanding threads — the retry " +
                "lane is the one that starves, because HikariCP's borrow() favours " +
                "continuously-active threads over intermittently-scheduled ones",
        )
    }

    @Test
    fun `the write lane consumes the pool exactly, leaving no unusable connections`() {
        val demand = DataSourceConfig.writeLaneDemand(tomcatThreads, workerThreads, retryThreads)

        assertEquals(
            writePoolSize, demand,
            "the write pool is meant to be SPENT, not merely respected: connections the write lane " +
                "cannot demand are connections that inflate this branch's footprint against " +
                "ES-4-NullLock-A while buying it nothing",
        )
    }

    /**
     * The lane shape, pinned so a change to it cannot pass unnoticed — NOT a cross-family parity
     * assertion. `99 : 150 : 50` is two thirds admission and a third retry, sized to consume the
     * write pool; ES-4-NullLock-A's lanes are sized from its own measured service times (see
     * variants.env) and its envelope is 400 against this branch's 350. A run comparing the two is
     * therefore reading lane sizing alongside the persistence model — a caveat to carry into the
     * table, not a defect. Rescaling ES was the alternative and was not taken.
     */
    @Test
    fun `the lane ratio is the shipped one`() {
        assertEquals(
            0.333, retryThreads.toDouble() / workerThreads, 0.01,
            "retry lane is $retryThreads of $workerThreads first-attempt threads — if this " +
                "assertion fails, update the cross-family caveat in application.yaml with it",
        )
        assertEquals(
            0.66, tomcatThreads.toDouble() / workerThreads, 0.01,
            "admission lane is $tomcatThreads of $workerThreads first-attempt threads",
        )
    }

    @Test
    fun `the envelope stays within the per-node budget`() {
        val total = DbPoolProperties().appPoolSize + writePoolSize
        assertTrue(total <= 600, "PG_MAX_CONNECTIONS is 600 and REPLICAS=1 must fit in it; got $total")
    }

    @Test
    fun `raising an unrelated-looking thread knob is what the warning exists to catch`() {
        // The regression this guards: HTTP_THREADS back to 200 reads like an admission-control
        // change and is in fact a pool change.
        assertTrue(
            DataSourceConfig.writeLaneDemand(200, workerThreads, retryThreads) > writePoolSize,
            "expected the default pool to be exceeded at HTTP_THREADS=200",
        )
    }
}
