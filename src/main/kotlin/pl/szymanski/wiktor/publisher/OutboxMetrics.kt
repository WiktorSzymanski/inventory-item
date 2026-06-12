package pl.szymanski.wiktor.publisher

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class OutboxMetrics(
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry,
) {
    private val backlog = meterRegistry.gauge("outbox.backlog", AtomicLong(0))

    @Scheduled(fixedDelay = 5_000)
    fun updateBacklog() {
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
            Long::class.java,
        ) ?: 0L
        backlog.set(count)
    }
}
