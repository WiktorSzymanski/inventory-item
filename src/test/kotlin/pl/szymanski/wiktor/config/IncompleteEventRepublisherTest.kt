package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration
import java.util.UUID

/**
 * The second delivery process — the fallback poll.
 *
 * In cursor mode this owns every publication the drain advanced past — routine, not rare, because a
 * publication's seq is assigned at INSERT while the reserve path's row locks are taken three
 * statements later, so commit order is not seq order. It therefore has to be bounded like a real
 * delivery path rather than iterating an unbounded list on the shared scheduler thread.
 *
 * With the cursor OFF it does nothing at all, which is where this branch deliberately differs from
 * TO-2 — see the no-op test at the bottom.
 */
class IncompleteEventRepublisherTest {

    private val processor = mockk<EventPublicationDirectProcessor>(relaxed = true)
    private val cursorStore = mockk<OutboxCursorStore>(relaxed = true)
    private val executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 4
        setThreadNamePrefix("test-outbox-poller-")
        initialize()
    }
    private val registry = SimpleMeterRegistry()

    private val minAge: Duration = Duration.ofMinutes(1)

    @AfterEach
    fun tearDown() = executor.shutdown()

    private fun republisher(
        cursorEnabled: Boolean = true,
        batchSize: Int = 2,
        maxBatches: Int = 3,
    ) = IncompleteEventRepublisher(
        processor, cursorStore, executor, registry, minAge, cursorEnabled, batchSize, maxBatches,
    )

    private fun rescued() = registry.counter("outbox.sweep.rescued").count()

    private fun ids(n: Int) = List(n) { UUID.randomUUID() }

    @Test
    fun `sweeps only below the cursor, and only rows old enough`() {
        every { cursorStore.load() } returns 900L
        every { processor.findIncompleteUpTo(any(), any(), any()) } returns emptyList()

        republisher().republishIncomplete()

        verify(exactly = 1) { processor.findIncompleteUpTo(900L, minAge, 2) }
        // The scan query belongs to the drain's other mode; issuing it here would reintroduce the
        // unbounded read this whole change exists to remove.
        verify(exactly = 0) { processor.findIncompleteIds(any<Duration>()) }
    }

    @Test
    fun `pages until a short page, then stops`() {
        every { cursorStore.load() } returns 10L
        val p1 = ids(2)
        val p2 = ids(1)
        every { processor.findIncompleteUpTo(any(), any(), any()) } returnsMany listOf(p1, p2)

        republisher(batchSize = 2, maxBatches = 5).republishIncomplete()

        (p1 + p2).forEach { verify(exactly = 1) { processor.process(it) } }
        verify(exactly = 2) { processor.findIncompleteUpTo(any(), any(), any()) }
        assertEquals(3.0, rescued())
    }

    @Test
    fun `one pass cannot run unboundedly long`() {
        every { cursorStore.load() } returns 10L
        // Always a full page: without the bound this never returns.
        every { processor.findIncompleteUpTo(any(), any(), any()) } answers { ids(2) }

        republisher(batchSize = 2, maxBatches = 3).republishIncomplete()

        verify(exactly = 3) { processor.findIncompleteUpTo(any(), any(), any()) }
    }

    @Test
    fun `a page that delivers nothing ends the pass instead of refetching itself`() {
        every { cursorStore.load() } returns 10L
        every { processor.findIncompleteUpTo(any(), any(), any()) } answers { ids(2) }
        every { processor.process(any()) } throws RuntimeException("still broken")

        republisher(batchSize = 2, maxBatches = 5).republishIncomplete()

        // Unlike the drain, the sweep's query is not a cursor — a failed page comes back verbatim.
        verify(exactly = 1) { processor.findIncompleteUpTo(any(), any(), any()) }
        assertEquals(0.0, rescued())
    }

    @Test
    fun `one failing row does not abort the rest of the page`() {
        every { cursorStore.load() } returns 10L
        val p = ids(3)
        every { processor.findIncompleteUpTo(any(), any(), any()) } returnsMany listOf(p, emptyList())
        every { processor.process(p[1]) } throws RuntimeException("listener blew up")

        republisher(batchSize = 3, maxBatches = 5).republishIncomplete()

        p.forEach { verify(exactly = 1) { processor.process(it) } }
        assertEquals(2.0, rescued())
    }

    /**
     * TO-2 falls back to an unbounded scan here. This branch must not: with the cursor off, TO-1's
     * drain already selects every incomplete row every 0.1 s, so a second scan could only find rows
     * the tick just failed on — which the next tick retries anyway. `cursor=false` has to reproduce
     * the SINGLE-process TO-1 that every earlier run measured, or the A/B is comparing two things
     * at once.
     */
    @Test
    fun `with the cursor disabled the fallback poll does nothing at all`() {
        republisher(cursorEnabled = false).republishIncomplete()

        verify(exactly = 0) { cursorStore.load() }
        verify(exactly = 0) { processor.findIncompleteUpTo(any(), any(), any()) }
        verify(exactly = 0) { processor.findIncompleteIds(any<Duration>()) }
        verify(exactly = 0) { processor.process(any()) }
        assertEquals(0.0, rescued())
    }
}
