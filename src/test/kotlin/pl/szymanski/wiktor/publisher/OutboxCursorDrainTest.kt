package pl.szymanski.wiktor.publisher

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import pl.szymanski.wiktor.config.EventPublicationDirectProcessor
import pl.szymanski.wiktor.config.EventPublicationDirectProcessor.PublicationRef
import pl.szymanski.wiktor.config.OutboxCursorStore
import java.time.Duration
import java.util.UUID

/**
 * The cursor path's contract, which is not the scan path's.
 *
 * The load-bearing property is the one that looks like a bug: the cursor advances past rows that
 * were NOT delivered. That is what keeps the fast path fast — a cursor one bad row can hold back
 * degrades into the scan it replaces — and it is safe only because a second process
 * ([pl.szymanski.wiktor.config.IncompleteEventRepublisher]) owns everything left below the cursor.
 */
class OutboxCursorDrainTest {

    private val processor = mockk<EventPublicationDirectProcessor>(relaxed = true)
    private val cursorStore = mockk<OutboxCursorStore>(relaxed = true)
    private val executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 4
        setThreadNamePrefix("test-outbox-poller-")
        initialize()
    }

    @AfterEach
    fun tearDown() = executor.shutdown()

    private fun publisher(batchSize: Int, cursorEnabled: Boolean = true) =
        OutboxPollingPublisher(processor, executor, cursorStore, cursorEnabled, batchSize)

    /** A page of [n] refs whose seq values run from [from] upwards. */
    private fun page(from: Long, n: Int) = List(n) { PublicationRef(UUID.randomUUID(), from + it) }

    @Test
    fun `first fetch starts from the persisted cursor, not from zero`() {
        every { cursorStore.load() } returns 4_200L
        every { processor.findAfterCursor(any(), any()) } returns emptyList()

        publisher(batchSize = 10).drain()

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

        publisher(batchSize = 2).drain()

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

        publisher(batchSize = 2).drain()

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
        every { processor.process(any()) } throws RuntimeException("listener blew up")

        publisher(batchSize = 3).drain()

        // Advanced anyway — these three now belong to the sweep. Refusing to advance is what would
        // make the drain spin on them forever, tick after tick, 0.1 s apart.
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

        publisher(batchSize = 3).drain()

        p.forEach { verify(exactly = 1) { processor.process(it.id) } }
        verify { cursorStore.save(3L) }
    }

    @Test
    fun `the cursor is read once per tick, not once per page`() {
        every { cursorStore.load() } returns 0L
        every { processor.findAfterCursor(any(), any()) } returnsMany
            listOf(page(from = 1, n = 2), page(from = 3, n = 2), page(from = 5, n = 1))

        publisher(batchSize = 2).drain()

        verify(exactly = 1) { cursorStore.load() }
    }

    @Test
    fun `cursor mode never issues the scan query`() {
        every { cursorStore.load() } returns 0L
        every { processor.findAfterCursor(any(), any()) } returns emptyList()

        publisher(batchSize = 10).drain()

        verify(exactly = 0) { processor.findIncompleteIds(any<Duration>()) }
    }

    /**
     * The A/B's other arm, and the reason it is worth a test of its own: with the cursor off, TO-1
     * must be EXACTLY the single-process branch every earlier run measured — one unbounded scan per
     * tick, no cursor read, no cursor write.
     */
    @Test
    fun `scan mode issues the unbounded scan and never touches the cursor`() {
        val ids = List(3) { UUID.randomUUID() }
        every { processor.findIncompleteIds(Duration.ZERO) } returns ids

        publisher(batchSize = 10, cursorEnabled = false).drain()

        ids.forEach { verify(exactly = 1) { processor.process(it) } }
        verify(exactly = 1) { processor.findIncompleteIds(Duration.ZERO) }
        verify(exactly = 0) { processor.findAfterCursor(any(), any()) }
        verify(exactly = 0) { cursorStore.load() }
        verify(exactly = 0) { cursorStore.save(any()) }
    }

    @Test
    fun `scan mode still delivers the rest of a batch when one row fails`() {
        val ids = List(3) { UUID.randomUUID() }
        every { processor.findIncompleteIds(Duration.ZERO) } returns ids
        every { processor.process(ids[1]) } throws RuntimeException("listener blew up")

        publisher(batchSize = 10, cursorEnabled = false).drain()

        ids.forEach { verify(exactly = 1) { processor.process(it) } }
    }
}
