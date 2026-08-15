package pl.szymanski.wiktor.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.szymanski.wiktor.db.DbLane
import pl.szymanski.wiktor.db.DbLaneContext
import java.time.Duration

@Configuration
@EnableScheduling
class PollingEventPublicationConfig

/**
 * Backup republication for events left incomplete after an application crash.
 *
 * In the normal flow, @ApplicationModuleListener methods are invoked immediately after the
 * business transaction commits (the standard Spring Modulith after-commit path). If the app
 * crashes between the DB commit and the listener invocation, the publication stays PUBLISHED
 * in event_publication indefinitely. This scheduler rescues those orphaned publications.
 *
 * republication-min-age must exceed the expected after-commit delivery time so that healthy
 * in-progress deliveries finish and reach COMPLETED before this poller can see them.
 * After-commit delivery is sub-second in normal operation, so PT1M is very conservative.
 */
@Component
class IncompleteEventRepublisher(
    private val incompleteEventPublications: IncompleteEventPublications,
    @Value("\${spring.modulith.events.republication-min-age:PT1M}")
    private val minAge: Duration,
) {
    /**
     * TO-3-mod-A: WRITE lane. This runs on Boot's task *scheduler* (a separate, single-threaded
     * pool from the executors), it scans and rewrites `event_publication`, and Modulith opens the
     * transaction inside `resubmitIncompletePublicationsOlderThan` — i.e. below this frame, so
     * setting the lane here is early enough.
     */
    @Scheduled(fixedDelayString = "\${spring.modulith.events.republication-interval:PT30S}")
    fun republishIncomplete() {
        DbLaneContext.on(DbLane.WRITE) {
            incompleteEventPublications.resubmitIncompletePublicationsOlderThan(minAge)
        }
    }
}
