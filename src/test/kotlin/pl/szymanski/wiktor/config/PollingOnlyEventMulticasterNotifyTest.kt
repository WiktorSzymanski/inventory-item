package pl.szymanski.wiktor.config

import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.core.env.Environment
import org.springframework.modulith.events.core.EventPublicationRegistry
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.TargetEventPublication
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalApplicationListener

/**
 * [PollingOnlyEventMulticaster] is the only place in the codebase that writes `event_publication`
 * rows, which makes it the only place that can raise the outbox notification from application code.
 *
 * Two halves to the wiring, and the second one is the trap. It must notify when it persists rows —
 * and it must NOT touch the notifier when it does not, because framework events (every
 * `ContextRefreshedEvent` and friend) go through this same method with no AFTER_COMMIT listener
 * attached. The notifier arrives as a lazy `Supplier`, resolved out of the bean factory on first
 * use; calling it for a framework event would force `OutboxNotifier` — and its `JdbcTemplate` —
 * into existence part-way through context startup.
 */
class PollingOnlyEventMulticasterNotifyTest {

    private val repository = mockk<EventPublicationRepository>(relaxed = true)
    private val registry = mockk<EventPublicationRegistry>(relaxed = true)
    private val environment = mockk<Environment>(relaxed = true)
    private val notifier = mockk<OutboxNotifier>(relaxed = true)

    private data class TestEvent(val id: String = "e1")

    private fun multicaster(vararg listeners: ApplicationListener<*>) =
        PollingOnlyEventMulticaster({ registry }, { repository }, { environment }, { notifier }).apply {
            setBeanFactory(DefaultListableBeanFactory())
            listeners.forEach { addApplicationListener(it) }
        }

    private fun publish(multicaster: PollingOnlyEventMulticaster, event: TestEvent = TestEvent()) =
        multicaster.multicastEvent(PayloadApplicationEvent(this, event), null)

    @Test
    fun `persisting a publication raises exactly one notification`() {
        val multicaster = multicaster(AfterCommitListener("listener-1"))

        publish(multicaster)

        verify(exactly = 1) { repository.create(any<TargetEventPublication>()) }
        verify(exactly = 1) { notifier.notifyOnCommit() }
    }

    @Test
    fun `several listeners on one event still raise one notification`() {
        val multicaster = multicaster(
            AfterCommitListener("listener-1"),
            AfterCommitListener("listener-2"),
            AfterCommitListener("listener-3"),
        )

        publish(multicaster)

        // Three outbox rows, one notification. The per-transaction collapse across several
        // publishEvent calls is OutboxNotifier's job; collapsing across listeners is this one's.
        verify(exactly = 3) { repository.create(any<TargetEventPublication>()) }
        verify(exactly = 1) { notifier.notifyOnCommit() }
    }

    @Test
    fun `an event with no after-commit listener never touches the notifier`() {
        val multicaster = multicaster(ApplicationListener<PayloadApplicationEvent<TestEvent>> { })

        publish(multicaster)

        verify(exactly = 0) { repository.create(any<TargetEventPublication>()) }
        verify { notifier wasNot Called }
    }

    /** The shape `@ApplicationModuleListener` produces: transactional, AFTER_COMMIT, identified. */
    private class AfterCommitListener(private val id: String) :
        TransactionalApplicationListener<PayloadApplicationEvent<TestEvent>> {

        override fun getTransactionPhase() = TransactionPhase.AFTER_COMMIT

        override fun getListenerId() = id

        override fun addCallback(callback: TransactionalApplicationListener.SynchronizationCallback) = Unit

        override fun processEvent(event: PayloadApplicationEvent<TestEvent>) = Unit

        override fun onApplicationEvent(event: PayloadApplicationEvent<TestEvent>) = Unit
    }
}
