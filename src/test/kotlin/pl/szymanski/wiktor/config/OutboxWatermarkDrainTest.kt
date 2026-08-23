package pl.szymanski.wiktor.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.szymanski.wiktor.config.EventPublicationDirectProcessor.PublicationRef
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The watermark path's contract, which is not the seq cursor's.
 *
 * The seq cursor advances to the highest seq it has SEEN, which is why it strands rows: `seq` is
 * assigned at INSERT and commit order is not insert order, so a page can contain a committed high
 * seq while a lower one is still in flight. This path advances to a boundary it has PROVEN closed —
 * `pg_snapshot_xmin`, below which every transaction is decided — so there is nothing left behind it
 * to strand. The tests below pin the three properties that make that true: the boundary is read
 * once per pass, the window is fully drained before the boundary moves, and a boundary that has not
 * moved is never saved.
 */
class OutboxWatermarkDrainTest {

    private val processor = mockk<EventPublicationDirectProcessor>(relaxed = true)
    private val cursorStore = mockk<OutboxCursorStore>(relaxed = true)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private var loopThread: Thread? = null

    @AfterEach
    fun tearDown() {
        loopThread?.interrupt()
        executor.shutdownNow()
    }

    private fun watermarkLoop(batchSize: Int) = EventDrainLoop(
        processor, executor, batchSize, cursorStore, cursorEnabled = true, watermarkEnabled = true,
    )

    /** A page of [n] refs inside transaction [xact], whose seq values run from [from] upwards. */
    private fun page(xact: Long, from: Long, n: Int) =
        List(n) { PublicationRef(UUID.randomUUID(), from + it, xact.toString()) }

    @Test
    fun `the window runs from the persisted watermark up to the current xmin`() {
        every { cursorStore.loadWatermark() } returns "100"
        every { processor.currentWatermark() } returns "140"
        every { processor.findInWindow(any(), any(), any(), any(), any()) } returns emptyList()

        watermarkLoop(batchSize = 10).drainAll()

        // Half-open [100, 140): the previous pass closed everything below 100, and 140 is the
        // lowest transaction still in progress, so 140 itself is not decided yet.
        verify(exactly = 1) { processor.findInWindow("100", "140", "100", 0L, 10) }
    }

    @Test
    fun `the watermark is read once per pass, not once per page`() {
        every { cursorStore.loadWatermark() } returns "100"
        every { processor.currentWatermark() } returns "140"
        every { processor.findInWindow(any(), any(), any(), any(), any()) } returnsMany listOf(
            page(xact = 110, from = 1, n = 2),
            page(xact = 120, from = 3, n = 2),
            page(xact = 130, from = 5, n = 1),
        )

        watermarkLoop(batchSize = 2).drainAll()

        // A boundary re-read mid-pass would widen the window under the pass's own feet, and the
        // rows it let in would be ordered below ones already delivered.
        verify(exactly = 1) { processor.currentWatermark() }
        verify(exactly = 1) { cursorStore.loadWatermark() }
    }

    @Test
    fun `pages forward by transaction id and seq until a short page ends the pass`() {
        every { cursorStore.loadWatermark() } returns "100"
        every { processor.currentWatermark() } returns "140"
        val p1 = page(xact = 110, from = 1, n = 2)
        val p2 = page(xact = 120, from = 3, n = 2)
        val p3 = page(xact = 130, from = 5, n = 1)
        every { processor.findInWindow(any(), any(), any(), any(), any()) } returnsMany listOf(p1, p2, p3)

        watermarkLoop(batchSize = 2).drainAll()

        verifyOrder {
            processor.findInWindow("100", "140", "100", 0L, 2)
            processor.findInWindow("100", "140", "110", 2L, 2)
            processor.findInWindow("100", "140", "120", 4L, 2)
        }
        // Stops on the short page instead of issuing a fourth, certainly-empty query.
        verify(exactly = 3) { processor.findInWindow(any(), any(), any(), any(), any()) }
        (p1 + p2 + p3).forEach { verify(exactly = 1) { processor.process(it.id) } }
    }

    @Test
    fun `the watermark is saved once, after the window is exhausted`() {
        every { cursorStore.loadWatermark() } returns "100"
        every { processor.currentWatermark() } returns "140"
        every { processor.findInWindow(any(), any(), any(), any(), any()) } returnsMany listOf(
            page(xact = 110, from = 1, n = 2),
            page(xact = 120, from = 3, n = 1),
        )

        watermarkLoop(batchSize = 2).drainAll()

        // Per-page saving is what would reintroduce the bug: a crash between two pages would leave
        // the boundary above rows the pass had not delivered, and nothing sweeps above the cursor.
        verify(exactly = 1) { cursorStore.saveWatermark("140") }
    }

    @Test
    fun `a watermark that has not advanced is never saved and never widens the window`() {
        every { cursorStore.loadWatermark() } returns "140"
        every { processor.currentWatermark() } returns "140"

        watermarkLoop(batchSize = 10).drainAll()

        // An empty window is not a reason to write: the boundary is already there. Reading rows in
        // [140, 140) would return nothing anyway, so the query is skipped entirely.
        verify(exactly = 0) { cursorStore.saveWatermark(any()) }
        verify(exactly = 0) { processor.findInWindow(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an empty window still closes the boundary when xmin has advanced`() {
        every { cursorStore.loadWatermark() } returns "100"
        every { processor.currentWatermark() } returns "140"
        every { processor.findInWindow(any(), any(), any(), any(), any()) } returns emptyList()

        watermarkLoop(batchSize = 10).drainAll()

        // Nothing to deliver, but [100, 140) is now proven empty and must never be scanned again.
        verify(exactly = 1) { cursorStore.saveWatermark("140") }
    }

    @Test
    fun `the watermark advances past a page whose deliveries all failed`() {
        every { cursorStore.loadWatermark() } returns "100"
        every { processor.currentWatermark() } returns "140"
        val p = page(xact = 110, from = 1, n = 3)
        every { processor.findInWindow(any(), any(), any(), any(), any()) } returnsMany listOf(p, emptyList())
        every { processor.process(any()) } throws RuntimeException("listener blew up")

        watermarkLoop(batchSize = 3).drainAll()

        // Same contract as the seq cursor: a boundary one bad row can hold back degrades into the
        // scan it replaces. These three belong to the sweep, which in this mode scans without a
        // cursor predicate and so reaches them wherever they are.
        verify(exactly = 1) { cursorStore.saveWatermark("140") }
    }

    @Test
    fun `one failing delivery does not abort the rest of the page`() {
        every { cursorStore.loadWatermark() } returns "100"
        every { processor.currentWatermark() } returns "140"
        val p = page(xact = 110, from = 1, n = 3)
        every { processor.findInWindow(any(), any(), any(), any(), any()) } returnsMany listOf(p, emptyList())
        every { processor.process(p[1].id) } throws RuntimeException("listener blew up")

        watermarkLoop(batchSize = 3).drainAll()

        p.forEach { verify(exactly = 1) { processor.process(it.id) } }
    }

    @Test
    fun `watermark mode issues neither the seq cursor query nor the scan query`() {
        every { cursorStore.loadWatermark() } returns "100"
        every { processor.currentWatermark() } returns "140"
        every { processor.findInWindow(any(), any(), any(), any(), any()) } returns emptyList()

        watermarkLoop(batchSize = 10).drainAll()

        verify(exactly = 0) { processor.findAfterCursor(any(), any()) }
        verify(exactly = 0) { processor.findIncompleteIds(any<Duration>(), any<Int>()) }
        verify(exactly = 0) { cursorStore.load() }
    }

    @Test
    fun `a pinned xmin re-arms the loop, so a stalled window is retried without a new signal`() {
        every { cursorStore.loadWatermark() } returns "140"
        val calls = AtomicInteger(0)
        val twoPasses = CountDownLatch(2)
        every { processor.currentWatermark() } answers {
            calls.incrementAndGet()
            twoPasses.countDown()
            "140" // xmin pinned by a long-running transaction: the window never opens
        }

        val loop = watermarkLoop(batchSize = 10)
        loopThread = Thread(loop::runLoop, "test-watermark-drain").apply { isDaemon = true; start() }

        loop.signal()

        // NOTIFY is delivered at COMMIT, so the row that woke us is committed — it is an OLDER
        // transaction pinning xmin that keeps it invisible. Nothing will signal again on that
        // transaction's behalf if it writes no publication of its own (the purge's DELETE is
        // exactly that), so the pass has to come back on its own or the row waits for the sweep.
        assertTrue(twoPasses.await(5, TimeUnit.SECONDS), "a stalled window never retried itself")
    }
}
