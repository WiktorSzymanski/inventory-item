package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.commandhandling.CommandMessage
import org.axonframework.commandhandling.gateway.RetryScheduler
import org.axonframework.modelling.command.ConcurrencyException
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ConcurrencyRetryScheduler(
    private val retryExecutor: ScheduledExecutorService,
    private val maxRetries: Int = 5,
    private val initialDelayMs: Long = 25L,
    meterRegistry: MeterRegistry,
) : RetryScheduler {

    companion object {
        private val log = LoggerFactory.getLogger(ConcurrencyRetryScheduler::class.java)
    }

    private val retryCounter: Counter = Counter.builder("inventory.optimistic.retry").register(meterRegistry)
    private val exhaustedCounter: Counter = Counter.builder("inventory.optimistic.exhausted").register(meterRegistry)

    override fun scheduleRetry(
        commandMessage: CommandMessage<*>,
        lastFailure: RuntimeException,
        history: List<Array<Class<out Throwable>>>,
        commandDispatch: Runnable,
    ): Boolean {
        val isConcurrency = generateSequence(lastFailure as Throwable) { it.cause }
            .any { it is ConcurrencyException }
        if (!isConcurrency) return false
        if (history.size >= maxRetries) {
            log.warn("[RETRY] exhausted retries for {} after {} attempts", commandMessage.payloadType.simpleName, history.size)
            exhaustedCounter.increment()
            return false
        }
        val delay = (initialDelayMs * (1L shl history.size)).coerceAtMost(500L)
        log.debug("[RETRY] ConcurrencyException on {} attempt={} retrying in {}ms", commandMessage.payloadType.simpleName, history.size + 1, delay)
        retryCounter.increment()
        retryExecutor.schedule(commandDispatch, delay, TimeUnit.MILLISECONDS)
        return true
    }
}
