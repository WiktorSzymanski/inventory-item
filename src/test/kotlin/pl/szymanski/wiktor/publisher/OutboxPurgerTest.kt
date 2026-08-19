package pl.szymanski.wiktor.publisher

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers the two contracts the purge exists to honour.
 *
 * It must bound itself: the bug this change fixes was caused by long-lived transactions pinning the
 * xmin horizon, so a sweep that deleted a large backlog in one unbounded transaction would BE the
 * bug.
 *
 * And it must run on its own thread. Spring's default scheduler pool is one thread, and on TO-1
 * that thread runs OutboxPollingPublisher.drain() — a @Scheduled sweep would serialize against
 * outbox delivery on that branch and not on this one.
 */
class OutboxPurgerTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val registry = SimpleMeterRegistry()
    private var started: OutboxPurger? = null

    @AfterEach
    fun tearDown() {
        started?.stop()
    }

    private fun purger(
        enabled: Boolean = true,
        batchSize: Int = 100,
        maxBatches: Int = 10,
        minAge: Duration = Duration.ofSeconds(5),
        interval: Duration = Duration.ofSeconds(5),
    ) = OutboxPurger(jdbc, registry, enabled, interval, minAge, batchSize, maxBatches)

    private fun purgedCount() = registry.counter("outbox.purged").count().toInt()

    private fun deletesReturning(vararg counts: Int) {
        every { jdbc.update(any<String>(), *anyVararg()) } returnsMany counts.toList()
    }

    @Test
    fun `a short batch ends the sweep`() {
        deletesReturning(100, 100, 42)

        purger(batchSize = 100).purgeCompleted()

        verify(exactly = 3) { jdbc.update(any<String>(), *anyVararg()) }
        assertEquals(242, purgedCount())
    }

    @Test
    fun `an empty first batch ends the sweep immediately`() {
        deletesReturning(0)

        purger(batchSize = 100).purgeCompleted()

        verify(exactly = 1) { jdbc.update(any<String>(), *anyVararg()) }
        assertEquals(0, purgedCount())
    }

    @Test
    fun `a backlog of full batches stops at maxBatches rather than running to exhaustion`() {
        deletesReturning(*IntArray(50) { 100 })

        purger(batchSize = 100, maxBatches = 3).purgeCompleted()

        verify(exactly = 3) { jdbc.update(any<String>(), *anyVararg()) }
        assertEquals(300, purgedCount())
    }

    @Test
    fun `disabled purge touches the database not at all`() {
        purger(enabled = false).purgeCompleted()

        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
        assertEquals(0, purgedCount())
    }

    @Test
    fun `only rows completed before the cutoff are eligible`() {
        val sql = slot<String>()
        every { jdbc.update(capture(sql), *anyVararg()) } returns 0

        purger(minAge = Duration.ofSeconds(90)).purgeCompleted()

        // The guard that makes the sweep safe: an in-flight publication has completion_date NULL,
        // and NULL is never < anything, so it can never be selected.
        assertTrue(sql.captured.contains("completion_date <"))
        assertTrue(sql.captured.contains("LIMIT"))
    }

    @Test
    fun `sweeps run on a dedicated thread, not the shared scheduler pool`() {
        val ran = CountDownLatch(1)
        val threadName = mutableListOf<String>()
        every { jdbc.update(any<String>(), *anyVararg()) } answers {
            threadName += Thread.currentThread().name
            ran.countDown()
            0
        }

        val p = purger(interval = Duration.ofMillis(50)).also { started = it }
        p.start()

        assertTrue(ran.await(5, TimeUnit.SECONDS), "purge sweep never ran")
        assertTrue(
            threadName.first().startsWith("outbox-purge"),
            "sweep ran on '${threadName.first()}', expected its own outbox-purge thread",
        )
    }

    @Test
    fun `a disabled purge schedules nothing at all`() {
        every { jdbc.update(any<String>(), *anyVararg()) } returns 0

        val p = purger(enabled = false, interval = Duration.ofMillis(50)).also { started = it }
        p.start()
        Thread.sleep(300)

        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }
}
