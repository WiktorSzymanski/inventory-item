package pl.szymanski.wiktor.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
 * The cursor path's contract, which is not the scan path's.
 *
 * The load-bearing property is the one that looks like a bug: the cursor advances past rows that
 * were NOT delivered. That is what keeps the fast path fast — a cursor one bad row can hold back
 * degrades into the scan it replaces — and it is safe only because a second process
 * (IncompleteEventRepublisher) owns everything left below the cursor.
 */
class OutboxCursorDrainTest {

    private val processor = mockk<EventPublicationDirectProcessor>(relaxed = true)
    private val cursorStore = mockk<OutboxCursorStore>(relaxed = true)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private var loopThread: Thread? = null

    @AfterEach
    fun tearDown() {
        loopThread?.interrupt()
        executor.shutdownNow()
    }

    private fun cursorLoop(batchSize: Int) =
        EventDrainLoop(processor, executor, batchSize, cursorStore, cursorEnabled = true)

    /** A page of [n] refs whose seq values run from [from] upwards. */
    private fun page(from: Long, n: Int) = List(n) { PublicationRef(UUID.randomUUID(), from + it) }

    @Test
    fun `first fetch starts from the persisted cursor, not from zero`() {
        every { cursorStore.load() } returns 4_200L
        every { processor.findAfterCursor(any(), any()) } returns emptyList()

        cursorLoop(batchSize = 10).drainAll()

        // The whole point of the change: the read starts past everything already delivered.
        verify(exactly = 1) { processor.findAfterCursor(4_200L, 10) }
    }

    @Test
    fun `pages forward from the previous page's max seq until a short page ends the pass`() {
        every { cursorStore.load() } returns 0L
        val p1 = page(from = 1, n = 2)
        val p2 = page(from = 3, n = 2)
        val p3 = page(from = 5, n = 1)
        every { processor.findAfterCursor(any(), any()) } returnsMany listOf(p1, p2, p3)

        cursorLoop(batchSize = 2).drainAll()

        verifyOrder {
            processor.findAfterCursor(0L, 2)
            processor.findAfterCursor(2L, 2)
            processor.findAfterCursor(4L, 2)
        }
        // Stops on the short page instead of issuing a fourth, certainly-empty query.
        verify(exactly = 3) { processor.findAfterCursor(any(), any()) }
        (p1 + p2 + p3).forEach { verify(exactly = 1) { processor.process(it.id) } }
    }

    @Test
    fun `the cursor is saved after every page`() {
        every { cursorStore.load() } returns 0L
        every { processor.findAfterCursor(any(), any()) } returnsMany
            listOf(page(from = 1, n = 2), page(from = 3, n = 1))

        cursorLoop(batchSize = 2).drainAll()

        verifyOrder {
            cursorStore.save(2L)
            cursorStore.save(3L)
        }
    }

    @Test
    fun `the cursor advances past a page whose deliveries all failed`() {
        every { cursorStore.load() } returns 0L
        val p = page(from = 1, n = 3)
        every { processor.findAfterCursor(any(), any()) } returnsMany listOf(p, emptyList())
        every { processor.process(any<UUID>()) } throws RuntimeException("listener blew up")

        cursorLoop(batchSize = 3).drainAll()

        // Advanced anyway — these three now belong to the sweep. Refusing to advance is what would
        // make the drain spin on them forever, which is exactly the scan path's failure mode: there,
        // a full page that delivers nothing has to bail out of the pass to avoid refetching itself.
        verify { cursorStore.save(3L) }
        verifyOrder {
            processor.findAfterCursor(0L, 3)
            processor.findAfterCursor(3L, 3)
        }
    }

    @Test
    fun `one failing delivery does not abort the rest of the page`() {
        every { cursorStore.load() } returns 0L
        val p = page(from = 1, n = 3)
        every { processor.findAfterCursor(any(), any()) } returnsMany listOf(p, emptyList())
        every { processor.process(p[1].id) } throws RuntimeException("listener blew up")

        cursorLoop(batchSize = 3).drainAll()

        p.forEach { verify(exactly = 1) { processor.process(it.id) } }
        verify { cursorStore.save(3L) }
    }

    @Test
    fun `the cursor is read once per pass, not once per page`() {
        every { cursorStore.load() } returns 0L
        every { processor.findAfterCursor(any(), any()) } returnsMany
            listOf(page(from = 1, n = 2), page(from = 3, n = 2), page(from = 5, n = 1))

        cursorLoop(batchSize = 2).drainAll()

        verify(exactly = 1) { cursorStore.load() }
    }

    @Test
    fun `cursor mode never issues the scan query`() {
        every { cursorStore.load() } returns 0L
        every { processor.findAfterCursor(any(), any()) } returns emptyList()

        cursorLoop(batchSize = 10).drainAll()

        verify(exactly = 0) { processor.findIncompleteIds(any<Duration>(), any<Int>()) }
    }

    @Test
    fun `a burst of signals during a drain still collapses into exactly one more pass`() {
        every { cursorStore.load() } returns 0L
        val calls = AtomicInteger(0)
        val firstCallEntered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val secondCallDone = CountDownLatch(1)

        every { processor.findAfterCursor(any(), any()) } answers {
            when (calls.incrementAndGet()) {
                1 -> { firstCallEntered.countDown(); gate.await() }
                2 -> secondCallDone.countDown()
            }
            emptyList()
        }

        val loop = cursorLoop(batchSize = 10)
        loopThread = Thread(loop::runLoop, "test-cursor-drain").apply { isDaemon = true; start() }

        loop.signal()
        assertTrue(firstCallEntered.await(5, TimeUnit.SECONDS), "first drain never started")

        repeat(1000) { loop.signal() }
        gate.countDown()

        assertTrue(secondCallDone.await(5, TimeUnit.SECONDS), "coalesced drain never ran")
        Thread.sleep(200) // let any surplus pass show up
        assertEquals(2, calls.get(), "1000 signals should cost one extra drain, not 1000")
    }
}
