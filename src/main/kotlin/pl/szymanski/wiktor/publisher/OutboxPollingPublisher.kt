package pl.szymanski.wiktor.publisher

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.config.EventPublicationDirectProcessor
import java.time.Duration

/**
 * Primary delivery path of the polling variant: each tick sweeps every incomplete publication (in
 * publication order) and routes it through the claim-guarded [EventPublicationDirectProcessor] —
 * the same delivery core as TO-2, with the poll tick instead of NOTIFY as the trigger. Each row is
 * claimed, delivered to the @ApplicationModuleListener recorded in its listener_id, and marked
 * complete in one transaction per publication, so a delivered row is never re-delivered and a
 * failed one stays incomplete for the next tick (at-least-once).
 */
@Component
class OutboxPollingPublisher(
    private val processor: EventPublicationDirectProcessor,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(fixedDelayString = "\${spring.modulith.events.polling-interval:PT10S}")
    fun drain() {
        processor.findIncompleteIds(Duration.ZERO).forEach { id ->
            runCatching { processor.process(id) }
                .onFailure { e -> log.error("Failed to deliver publication {}", id, e) }
        }
    }
}
