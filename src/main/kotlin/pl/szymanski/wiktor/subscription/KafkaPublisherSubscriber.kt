package pl.szymanski.wiktor.subscription

import io.kurrent.dbclient.CreatePersistentSubscriptionToAllOptions
import io.kurrent.dbclient.KurrentDBPersistentSubscriptionsClient
import io.kurrent.dbclient.NackAction
import io.kurrent.dbclient.PersistentSubscription
import io.kurrent.dbclient.PersistentSubscriptionListener
import io.kurrent.dbclient.ResolvedEvent
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutionException

@Component
class KafkaPublisherSubscriber(
    private val persistentSubClient: KurrentDBPersistentSubscriptionsClient,
    private val meterRegistry: MeterRegistry,
) : ApplicationRunner {

    companion object {
        private const val SUBSCRIPTION_GROUP = "mock-kafka-publisher-group"
        private val log = LoggerFactory.getLogger(KafkaPublisherSubscriber::class.java)
    }

    override fun run(args: ApplicationArguments) {
        ensureSubscriptionGroupExists()
        subscribeToGroup()
        log.info("Subscribed to KurrentDB persistent subscription group: $SUBSCRIPTION_GROUP")
    }

    private fun ensureSubscriptionGroupExists() {
        try {
            persistentSubClient.createToAll(
                SUBSCRIPTION_GROUP,
                CreatePersistentSubscriptionToAllOptions.get().fromStart(),
            ).get()
            log.info("Created persistent subscription group: $SUBSCRIPTION_GROUP")
        } catch (e: ExecutionException) {
            val msg = e.cause?.message ?: e.message ?: ""
            if (msg.contains("ALREADY_EXISTS")) {
                log.info("Persistent subscription group already exists: $SUBSCRIPTION_GROUP")
            } else {
                throw e.cause ?: e
            }
        }
    }

    private fun subscribeToGroup() {
        persistentSubClient.subscribeToAll(
            SUBSCRIPTION_GROUP,
            object : PersistentSubscriptionListener() {
                override fun onEvent(subscription: PersistentSubscription, retryCount: Int, event: ResolvedEvent) {
                    val recorded = event.originalEvent
                    if (recorded.eventType.startsWith("$")) {
                        subscription.ack(event)
                        return
                    }
                    try {
                        val lag = Duration.between(recorded.created, Instant.now())
                        log.info(
                            "[MOCK-KAFKA] topic=inventory-events key={} type={} lag={}ms",
                            recorded.streamId, recorded.eventType, lag.toMillis(),
                        )
                        meterRegistry.timer("publish.lag", "eventType", recorded.eventType)
                            .record(lag)
                        subscription.ack(event)
                    } catch (ex: Exception) {
                        log.error("Failed to process event ${recorded.eventType}@${recorded.streamId}", ex)
                        subscription.nack(NackAction.Park, ex.message ?: "processing error", event)
                    }
                }

                override fun onCancelled(subscription: PersistentSubscription, exception: Throwable?) {
                    if (exception != null) {
                        log.error("Subscription '$SUBSCRIPTION_GROUP' dropped", exception)
                    }
                }
            },
        )
    }
}
