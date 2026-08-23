package pl.szymanski.wiktor.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The wire contract between V10's trigger and the listener.
 *
 * These two sides are edited in different languages in different files, and a mismatch does not
 * break anything loudly — delivery just falls back to a drain pass and the branch quietly becomes
 * TO-2 with extra steps. This test pins the shape; `outbox.notify.unparsed` catches it at runtime.
 */
class NotifiedPublicationTest {

    private val mapper = JsonMapper.builder().build()

    /** Exactly what `json_build_object('id',…,'eventType',…,'listenerId',…,'event',…)` emits. */
    private fun triggerMessage(
        id: String = "0f8fad5b-d9cb-469f-a165-70867728950e",
        eventType: String = "pl.szymanski.wiktor.domain.OrderCreatedEvent",
        listenerId: String = "pl.szymanski.wiktor.service.InventoryService.onOrderCreated(pl.szymanski.wiktor.domain.OrderCreatedEvent)",
        event: String = """{"orderId":"o-1","userId":"u-1","items":[{"itemId":"i-1","quantity":2}]}""",
    ): String = mapper.writeValueAsString(
        mapOf("id" to id, "eventType" to eventType, "listenerId" to listenerId, "event" to event),
    )

    @Test
    fun `parses the trigger's message and hands back the event JSON unchanged`() {
        val raw = triggerMessage()

        val pub = NotifiedPublication.parse(mapper, raw)

        assertEquals(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"), pub.id)
        assertEquals("pl.szymanski.wiktor.domain.OrderCreatedEvent", pub.eventType)
        // The event must survive as the byte-identical string the EventSerializer was given, since
        // that is what gets deserialized. The trigger embeds it as an escaped JSON string precisely
        // so this round-trip cannot alter it.
        assertEquals(
            """{"orderId":"o-1","userId":"u-1","items":[{"itemId":"i-1","quantity":2}]}""",
            pub.serializedEvent,
        )
    }

    @Test
    fun `an event payload containing quotes and backslashes survives the round trip`() {
        // A failure reason is free text and reaches the outbox verbatim; escaping is the one part of
        // the wire format that can silently corrupt a payload rather than fail to parse it.
        val nasty = """{"orderId":"o-1","reason":"he said \"no\" \\ then left"}"""

        val pub = NotifiedPublication.parse(mapper, triggerMessage(event = nasty))

        assertEquals(nasty, pub.serializedEvent)
    }

    @Test
    fun `a message missing a field is rejected rather than delivered half-built`() {
        val raw = mapper.writeValueAsString(
            mapOf("id" to UUID.randomUUID().toString(), "eventType" to "X", "listenerId" to "Y"),
        )

        // Strict on purpose: the caller counts this and falls back to a drain pass, so the row is
        // still delivered. Defaulting the missing field would deliver the wrong thing instead.
        assertThrows(IllegalArgumentException::class.java) { NotifiedPublication.parse(mapper, raw) }
    }

    @Test
    fun `a message that is not JSON at all is rejected`() {
        // What a pre-V10 trigger sends: the bare publication id. A database migrated halfway must
        // fall back, not crash the listener thread and not deliver garbage.
        assertThrows(Exception::class.java) {
            NotifiedPublication.parse(mapper, "0f8fad5b-d9cb-469f-a165-70867728950e")
        }
    }
}
