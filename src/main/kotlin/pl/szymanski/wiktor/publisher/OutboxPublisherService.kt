package pl.szymanski.wiktor.publisher

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import pl.szymanski.wiktor.config.OutboxProperties
import pl.szymanski.wiktor.service.OutboxService
import java.time.Duration
import java.time.Instant

@Service
class OutboxPublisherService(
    private val outboxService: OutboxService,
    private val outboxProperties: OutboxProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(fixedDelayString = "\${outbox.poll-interval-ms}")
    suspend fun publishBatch() {
        val pending = outboxService.pollPending(outboxProperties.batchSize)
        val now = Instant.now()
        pending.forEach { entry ->
            log.info(
                "[MOCK-KAFKA] topic=inventory-events key={} type={} payload={}",
                entry.aggregateId, entry.eventType, entry.payloadJson,
            )
            outboxService.markPublished(entry.id)
            Timer.builder("outbox.publish.lag")
                .tag("eventType", entry.eventType)
                .publishPercentileHistogram(true)
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(meterRegistry)
                .record(Duration.between(entry.createdAt.toInstant(), now))
        }
        if (pending.isNotEmpty()) {
            log.info("Published {} outbox entries", pending.size)
        }
    }
}
