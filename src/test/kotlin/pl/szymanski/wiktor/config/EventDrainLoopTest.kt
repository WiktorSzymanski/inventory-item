package pl.szymanski.wiktor.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the contract the drain loop exists for: a NOTIFY burst must collapse into one drain, and a
 * drain must never leave committed work behind.
 *
 * These run in SCAN mode — `app.outbox-cursor.enabled=false`, the pre-V8 topology — because that
 * mode is still shipped as the A/B baseline and has to keep behaving exactly as it did. The cursor
 * mode's own contract is in [OutboxCursorDrainTest].
 */
class EventDrainLoopTest {

    private val processor = mockk<EventPublicationDirectProcessor>(relaxed = true)
    private val cursorStore = mockk<OutboxCursorStore>(relaxed = true)
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private var loopThread: Thread? = null

    private fun scanLoop(batchSize: Int) =
        EventDrainLoop(processor, executor, batchSize, cursorStore, cursorEnabled = false)

    @AfterEach
    fun tearDown() {
        loopThread?.interrupt()
        executor.shutdownNow()
    }

    private fun startLoop(loop: EventDrainLoop) {
        loopThread = Thread(loop::runLoop, "test-drain").apply { isDaemon = true; start() }
    }

    private fun ids(n: Int) = List(n) { UUID.randomUUID() }

    @Test
    fun `drains page after page until a short page ends the pass`() {
        val page1 = ids(2)
        val page2 = ids(2)
        val page3 = ids(1)
        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } returnsMany
            listOf(page1, page2, page3)

        scanLoop(batchSize = 2).drainAll()

        (page1 + page2 + page3).forEach { verify(exactly = 1) { processor.process(it) } }
        // Stops on the short page instead of issuing a fourth, certainly-empty query.
        verify(exactly = 3) { processor.findIncompleteIds(any<Duration>(), any<Int>()) }
    }

    @Test
    fun `one failing delivery does not abort the rest of the page`() {
        val page = ids(3)
        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } returnsMany
            listOf(page, emptyList())
        every { processor.process(page[1]) } throws RuntimeException("listener blew up")

        scanLoop(batchSize = 3).drainAll()

        page.forEach { verify(exactly = 1) { processor.process(it) } }
    }

    @Test
    fun `a full page that delivers nothing ends the pass instead of spinning`() {
        val page = ids(2)
        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } returns page
        every { processor.process(any<UUID>()) } throws RuntimeException("delivery is broken")

        scanLoop(batchSize = 2).drainAll()

        // Without the no-progress guard this refetches the same full page forever.
        verify(exactly = 1) { processor.findIncompleteIds(any<Duration>(), any<Int>()) }
    }

    @Test
    fun `a burst of signals during a drain collapses into exactly one more pass`() {
        val calls = AtomicInteger(0)
        val firstCallEntered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val secondCallDone = CountDownLatch(1)

        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } answers {
            when (calls.incrementAndGet()) {
                1 -> { firstCallEntered.countDown(); gate.await() }
                2 -> secondCallDone.countDown()
            }
            emptyList()
        }

        val loop = scanLoop(batchSize = 10)
        startLoop(loop)

        loop.signal()
        assertTrue(firstCallEntered.await(5, TimeUnit.SECONDS), "first drain never started")

        repeat(1000) { loop.signal() }
        gate.countDown()

        assertTrue(secondCallDone.await(5, TimeUnit.SECONDS), "coalesced drain never ran")
        Thread.sleep(200) // let any surplus pass show up
        assertEquals(2, calls.get(), "1000 signals should cost one extra drain, not 1000")
    }

    @Test
    fun `a signal raised mid-delivery is not swallowed`() {
        val id = UUID.randomUUID()
        val calls = AtomicInteger(0)
        val laterPass = CountDownLatch(1)

        every { processor.findIncompleteIds(any<Duration>(), any<Int>()) } answers {
            if (calls.incrementAndGet() == 1) listOf(id) else { laterPass.countDown(); emptyList() }
        }

        val loop = scanLoop(batchSize = 10)
        // Fires while the first pass is between its fetch and its completion — the window
        // where clearing `pending` after the drain instead of before would lose the wake-up.
        every { processor.process(id) } answers { loop.signal() }

        startLoop(loop)
        loop.signal()

        assertTrue(laterPass.await(5, TimeUnit.SECONDS), "signal raised mid-drain was lost")
    }
}
