package pl.szymanski.wiktor.config

import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Everything [EventPublicationDirectProcessor] needs to deliver a publication, carried in the
 * NOTIFY message itself.
 *
 * These are exactly the three columns V2's consumer used to re-`SELECT` per row, plus the id it
 * claims by. Carrying them means the delivery path touches `event_publication` once — the claim
 * UPDATE — instead of twice, and never has to find a row before it can deliver one.
 *
 * The wire format is the V10 trigger's `json_build_object`, whose keys this mirrors.
 */
data class NotifiedPublication(
    val id: UUID,
    val eventType: String,
    val listenerId: String,
    val serializedEvent: String,
) {
    companion object {
        /**
         * Parse one NOTIFY payload, or throw.
         *
         * Deliberately strict: a message that does not carry all four fields cannot be delivered
         * from, and silently treating it as a wake-up would hide a trigger/consumer version skew
         * behind a merely slower path. The caller catches, counts it, and falls back to the drain —
         * so the row is still delivered, but the mismatch is visible in `outbox.notify.unparsed`.
         */
        fun parse(mapper: ObjectMapper, raw: String): NotifiedPublication {
            val node = mapper.readTree(raw)
            fun field(name: String): String =
                node.get(name)?.takeUnless { it.isNull }?.asString()
                    ?: throw IllegalArgumentException("NOTIFY payload has no '$name' field")

            return NotifiedPublication(
                id = UUID.fromString(field("id")),
                eventType = field("eventType"),
                listenerId = field("listenerId"),
                serializedEvent = field("event"),
            )
        }
    }
}
