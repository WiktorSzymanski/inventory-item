package pl.szymanski.wiktor.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.core.EventSerializer
import pl.szymanski.wiktor.domain.OrderCompletedEvent
import pl.szymanski.wiktor.publisher.InventoryEventListener
import java.time.Instant
import java.util.UUID

/**
 * Delivery straight from the notification: what the push path must still do, and what it must stop
 * doing.
 *
 * The "stop doing" half is the point of the branch — no SELECT. It is asserted here because it is
 * invisible everywhere else: re-reading the row produces an identical result, just slower, so a
 * regression would show up only as a lost benchmark difference.
 */
class EventPublicationPushDeliveryTest {

    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val eventSerializer = mockk<EventSerializer>()
    private val applicationContext = mockk<ApplicationContext>()
    private val listener = mockk<InventoryEventListener>(relaxed = true)

    private val processor = EventPublicationDirectProcessor(jdbcTemplate, eventSerializer, applicationContext)

    private val event = OrderCompletedEvent(orderId = "o-1", createdAt = Instant.parse("2026-08-23T10:00:00Z"))

    private fun publication(id: UUID = UUID.randomUUID()) = NotifiedPublication(
        id = id,
        eventType = OrderCompletedEvent::class.java.name,
        listenerId = "${InventoryEventListener::class.java.name}.on(${OrderCompletedEvent::class.java.name})",
        serializedEvent = """{"orderId":"o-1","createdAt":"2026-08-23T10:00:00Z"}""",
    )

    private fun claimReturns(rows: Int) {
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns rows
    }

    private fun listenerResolves() {
        every { applicationContext.getBean(InventoryEventListener::class.java) } returns listener
        every { eventSerializer.deserialize(any(), OrderCompletedEvent::class.java) } returns event
    }

    @Test
    fun `claims the row and invokes the listener without reading the outbox back`() {
        val pub = publication()
        claimReturns(1)
        listenerResolves()

        processor.process(pub)

        verify(exactly = 1) { listener.on(event) }
        // THE assertion of this branch. TO-2 issues exactly this query per delivery; here the three
        // columns arrived in the notification, so the delivery path touches event_publication once.
        verify(exactly = 0) { jdbcTemplate.queryForMap(any<String>(), *anyVararg()) }
    }

    @Test
    fun `the claim is scoped to an undelivered row so a duplicate notification is harmless`() {
        val pub = publication()
        val sql = slot<String>()
        every { jdbcTemplate.update(capture(sql), *anyVararg()) } returns 1
        listenerResolves()

        processor.process(pub)

        // The payload cannot replace the claim: it is the completion write AND the idempotency guard
        // against the sweep, a redundant NOTIFY, and another replica.
        assertEquals(true, sql.captured.contains("completion_date IS NULL"))
        assertEquals(true, sql.captured.contains("status = 'COMPLETED'"))
    }

    @Test
    fun `a row already delivered by the sweep is skipped, not delivered twice`() {
        claimReturns(0)

        processor.process(publication())

        verify(exactly = 0) { listener.on(any<OrderCompletedEvent>()) }
        verify(exactly = 0) { jdbcTemplate.queryForMap(any<String>(), *anyVararg()) }
    }

    @Test
    fun `a throwing listener propagates so the claim rolls back and the sweep retries`() {
        claimReturns(1)
        listenerResolves()
        every { listener.on(event) } throws RuntimeException("mock-kafka is down")

        // At-least-once depends on this: @Transactional rolls the claim back with the exception, so
        // completion_date stays NULL and IncompleteEventRepublisher picks the row up. Swallowing it
        // here would silently make delivery at-most-once.
        val thrown = assertThrows(RuntimeException::class.java) { processor.process(publication()) }
        assertEquals("mock-kafka is down", thrown.message)
    }

    @Test
    fun `deserializes the event from the notification's own bytes`() {
        val pub = publication()
        claimReturns(1)
        listenerResolves()

        processor.process(pub)

        verify(exactly = 1) { eventSerializer.deserialize(pub.serializedEvent, OrderCompletedEvent::class.java) }
    }
}
