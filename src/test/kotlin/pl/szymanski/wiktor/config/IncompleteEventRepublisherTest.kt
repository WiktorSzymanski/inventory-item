package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The second delivery process.
 *
 * In cursor mode this owns every publication the drain advanced past — routine, not rare, because a
 * publication's seq is assigned at INSERT while the reserve path's row locks are taken three
 * statements later, so commit order is not seq order. It therefore has to be bounded like a real
 * delivery path rather than iterating an unbounded list on the shared scheduler thread.
 */
class IncompleteEventRepublisherTest {

    private val processor = mockk<EventPublicationDirectProcessor>(relaxed = true)
    private val cursorStore = mockk<OutboxCursorStore>(relaxed = true)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val registry = SimpleMeterRegistry()

    private val minAge: Duration = Duration.ofMinutes(1)

    @AfterEach
    fun tearDown() = executor.shutdownNow().let { }

    private fun republisher(
        cursorEnabled: Boolean = true,
        watermarkEnabled: Boolean = false,
        batchSize: Int = 2,
        maxBatches: Int = 3,
    ) = IncompleteEventRepublisher(
        processor, cursorStore, executor, registry, minAge, cursorEnabled, watermarkEnabled, batchSize, maxBatches,
    )

    private fun rescued() = registry.counter("outbox.sweep.rescued").count()

    private fun ids(n: Int) = List(n) { UUID.randomUUID() }

    @Test
    fun `sweeps only below the cursor, and only rows old enough`() {
        every { cursorStore.load() } returns 900L
        every { processor.findIncompleteUpTo(any(), any(), any()) } returns emptyList()

        republisher().republishIncomplete()

        verify(exactly = 1) { processor.findIncompleteUpTo(900L, minAge, 2) }
        // The scan query is the OTHER mode's; issuing it here would reintroduce the unbounded read.
        verify(exactly = 0) { processor.findIncompleteIds(any<Duration>()) }
    }

    @Test
    fun `the watermark arm sweeps without a position predicate`() {
        every { processor.findIncompleteIds(any<Duration>(), any()) } returns emptyList()

        republisher(watermarkEnabled = true).republishIncomplete()

        // Below-the-cursor is the wrong place to look here: the watermark leaves nothing behind it,
        // but a transaction pinning xmin strands rows ABOVE it, which no cursor-bounded query
        // reaches. Position-free is the only sweep that can find them.
        verify(exactly = 1) { processor.findIncompleteIds(minAge, 2) }
        verify(exactly = 0) { processor.findIncompleteUpTo(any(), any(), any()) }
        verify(exactly = 0) { cursorStore.load() }
    }

    @Test
    fun `the watermark arm's sweep is bounded by batch size and max batches`() {
        // Always a full page: without the bound this never returns.
        every { processor.findIncompleteIds(any<Duration>(), any()) } answers { ids(2) }

        republisher(watermarkEnabled = true, batchSize = 2, maxBatches = 3).republishIncomplete()

        verify(exactly = 3) { processor.findIncompleteIds(minAge, 2) }
        // The unbounded overload belongs to the pre-V8 SCAN arm and must not be reached here: it
        // is what held a snapshot long enough to pin the xmin this arm's own drain waits on.
        verify(exactly = 0) { processor.findIncompleteIds(any<Duration>()) }
    }

    @Test
    fun `a scan page that delivers nothing ends the pass instead of refetching itself`() {
        every { processor.findIncompleteIds(any<Duration>(), any()) } answers { ids(2) }
        every { processor.process(any()) } throws RuntimeException("still broken")

        republisher(watermarkEnabled = true, batchSize = 2, maxBatches = 5).republishIncomplete()

        // Same reason as the below-cursor sweep: this query carries no position either, so a page
        // that delivered nothing comes back verbatim.
        verify(exactly = 1) { processor.findIncompleteIds(minAge, 2) }
        assertEquals(0.0, rescued())
    }

    @Test
    fun `a short scan page ends the pass`() {
        every { processor.findIncompleteIds(any<Duration>(), any()) } returns ids(1)

        republisher(watermarkEnabled = true, batchSize = 2, maxBatches = 5).republishIncomplete()

        verify(exactly = 1) { processor.findIncompleteIds(minAge, 2) }
        assertEquals(1.0, rescued())
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

    @Test
    fun `with the cursor disabled it reverts to the unbounded scan`() {
        val p = ids(2)
        every { processor.findIncompleteIds(minAge) } returns p

        republisher(cursorEnabled = false).republishIncomplete()

        verifyOrder { processor.process(p[0]); processor.process(p[1]) }
        verify(exactly = 0) { cursorStore.load() }
        verify(exactly = 0) { processor.findIncompleteUpTo(any(), any(), any()) }
        assertEquals(2.0, rescued())
    }
}
