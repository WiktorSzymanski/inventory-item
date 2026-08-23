package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * On this branch it is not the rare-leftover backstop its name suggests. NOTIFY carries the event,
 * but V10's trigger sends nothing for an event above ~8 kB, so for those publications this pass is
 * the ONLY delivery path — which makes "does the sweep actually see the row" a correctness
 * property, not a tuning one.
 */
class IncompleteEventRepublisherTest {

    private val processor = mockk<EventPublicationDirectProcessor>(relaxed = true)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val registry = SimpleMeterRegistry()

    private val minAge: Duration = Duration.ofMinutes(1)

    @AfterEach
    fun tearDown() = executor.shutdownNow().let { }

    private fun republisher(batchSize: Int = 2, maxBatches: Int = 3) =
        IncompleteEventRepublisher(processor, executor, registry, minAge, batchSize, maxBatches)

    private fun rescued() = registry.counter("outbox.sweep.rescued").count()

    private fun ids(n: Int) = List(n) { UUID.randomUUID() }

    @Test
    fun `sweeps by scan, never bounded by the drain's cursor`() {
        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } returns emptyList()

        republisher().republishIncomplete()

        verify(exactly = 1) { processor.findIncompleteIds(minAge, 2) }
        // THE regression this branch was built with. TO-2 sweeps `seq <= cursor`, which works there
        // because its drain runs continuously and keeps the cursor moving. Here the drain runs only
        // on (re)connect, so the cursor sits at whatever startup left it — 0 on an empty table — and
        // a cursor-bounded sweep matches NOTHING. Observed for real: a 20 kB event stranded at
        // seq 200 under a cursor of 0, still undelivered after five minutes.
        verify(exactly = 0) { processor.findIncompleteUpTo(any(), any(), any()) }
    }

    @Test
    fun `pages until a short page, then stops`() {
        val p1 = ids(2)
        val p2 = ids(1)
        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } returnsMany listOf(p1, p2)

        republisher(batchSize = 2, maxBatches = 5).republishIncomplete()

        (p1 + p2).forEach { verify(exactly = 1) { processor.process(it) } }
        verify(exactly = 2) { processor.findIncompleteIds(any<Duration>(), any<Int>()) }
        assertEquals(3.0, rescued())
    }

    @Test
    fun `one pass cannot run unboundedly long`() {
        // Always a full page: without the bound this never returns.
        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } answers { ids(2) }

        republisher(batchSize = 2, maxBatches = 3).republishIncomplete()

        verify(exactly = 3) { processor.findIncompleteIds(any<Duration>(), any<Int>()) }
    }

    @Test
    fun `a page that delivers nothing ends the pass instead of refetching itself`() {
        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } answers { ids(2) }
        every { processor.process(any<UUID>()) } throws RuntimeException("still broken")

        republisher(batchSize = 2, maxBatches = 5).republishIncomplete()

        // The sweep's query is not a cursor, so a failed page comes back verbatim and would spin.
        verify(exactly = 1) { processor.findIncompleteIds(any<Duration>(), any<Int>()) }
        assertEquals(0.0, rescued())
    }

    @Test
    fun `one failing row does not abort the rest of the page`() {
        val p = ids(3)
        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } returnsMany listOf(p, emptyList())
        every { processor.process(p[1]) } throws RuntimeException("listener blew up")

        republisher(batchSize = 3, maxBatches = 5).republishIncomplete()

        p.forEach { verify(exactly = 1) { processor.process(it) } }
        assertEquals(2.0, rescued())
    }

    @Test
    fun `never issues the unbounded scan the old shape used`() {
        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } returns emptyList()

        republisher().republishIncomplete()

        // findIncompleteIds(Duration) with no limit materialises the whole backlog — 439k rows in
        // the archived open-loop run — and delivers it serially on the shared scheduler thread.
        verify(exactly = 0) { processor.findIncompleteIds(any<Duration>()) }
    }
}
