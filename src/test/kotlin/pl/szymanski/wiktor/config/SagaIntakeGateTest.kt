package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The whole ES-4-bounded branch is this gate, so these tests pin its exact semantics rather than
 * merely that it exists.
 *
 * Two of them are about WHEN the permit comes back, which is the difference between a bound on the
 * QUEUE of incoming sagas (what this branch is) and a bound on the NUMBER OF SAGAS IN FLIGHT (what
 * would deadlock the order-saga TrackingEventProcessor — see [SagaIntakeGate]'s class doc).
 */
class SagaIntakeGateTest {

    /** Collects tasks without ever running them, so no permit is returned unless a test says so. */
    private class ManualExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()

        /** Counted separately from [pending], which [runNext] drains. */
        val submitted = AtomicInteger(0)

        override fun execute(command: Runnable) {
            synchronized(pending) { pending.add(command) }
            submitted.incrementAndGet()
        }

        fun runNext() = synchronized(pending) { pending.removeFirst() }.run()
    }

    @Test
    fun `admits up to capacity and then blocks the caller`() {
        val delegate = ManualExecutor()
        val gate = SagaIntakeGate(capacity = 2, delegate = delegate, meterRegistry = SimpleMeterRegistry())

        gate.execute { }
        gate.execute { }
        assertEquals(2, delegate.submitted.get(), "both submissions must reach the delegate")

        val returned = AtomicBoolean(false)
        val thirdCalled = CountDownLatch(1)
        val third = Thread {
            thirdCalled.countDown()
            gate.execute { }
            returned.set(true)
        }
        third.start()

        assertTrue(thirdCalled.await(5, TimeUnit.SECONDS))
        // Not a race with the assertion below: the permits are already gone, so the only way this
        // thread can proceed is a release, and nothing has released.
        Thread.sleep(200)
        assertFalse(returned.get(), "the third start must wait — the queue of incoming sagas is bounded at 2")
        assertEquals(2, delegate.submitted.get(), "a blocked start must not reach the delegate")

        delegate.runNext()

        third.join(5_000)
        assertTrue(returned.get(), "the third start must proceed once a slot frees")
        assertEquals(3, delegate.submitted.get())
    }

    @Test
    fun `returns the permit when the task leaves the queue, not when it finishes`() {
        val pool = Executors.newSingleThreadExecutor()
        val gate = SagaIntakeGate(capacity = 1, delegate = pool, meterRegistry = SimpleMeterRegistry())
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)

        gate.execute {
            running.countDown()
            release.await()
        }
        assertTrue(running.await(5, TimeUnit.SECONDS))

        // The task is executing and will not finish until this test says so. If the permit were
        // held for the task's LIFETIME, the gate would bound in-flight work rather than the intake
        // queue, and on the real TrackingEventProcessor that is the deadlock this design avoids.
        val admitted = AtomicBoolean(false)
        val second = Thread { gate.execute { }; admitted.set(true) }
        second.start()
        second.join(5_000)
        assertTrue(admitted.get(), "a RUNNING task must already have returned its permit")

        release.countDown()
        pool.shutdownNow()
    }

    @Test
    fun `admits anyway when the wait times out, and counts it`() {
        val registry = SimpleMeterRegistry()
        val delegate = ManualExecutor()
        val gate = SagaIntakeGate(
            capacity = 1,
            delegate = delegate,
            meterRegistry = registry,
            acquireTimeoutMs = 50L,
        )

        gate.execute { }
        gate.execute { }

        assertEquals(2, delegate.submitted.get(), "liveness over isolation: a start is never dropped")
        assertEquals(
            1.0,
            registry.counter("saga.intake.timeout").count(),
            "a run whose bound was breached must be able to say so",
        )
    }

    @Test
    fun `a rejected hand-off gives the permit back`() {
        val pool = Executors.newSingleThreadExecutor()
        pool.shutdownNow()
        val gate = SagaIntakeGate(capacity = 1, delegate = pool, meterRegistry = SimpleMeterRegistry())

        // Propagated, not swallowed — exactly what ES-4 does today, since the saga does not catch
        // it either. What must NOT happen is the permit leaking, which would shrink the bound by
        // one for the rest of the JVM's life.
        assertThrows(RejectedExecutionException::class.java) { gate.execute { } }
        assertEquals(1, gate.availablePermits(), "a rejected hand-off must not consume a slot")
    }

    @Test
    fun `the bean takes its bound from the configured property`() {
        // The failure this guards is the one ORDER_WORKER_QUEUE_CAPACITY actually shipped with on
        // the TO side: a knob that binds, logs, and appears in meta.json while the code it is meant
        // to size ignores it. Every run would then be the default bound under another name.
        val registry = SimpleMeterRegistry()
        val gate = CommandGatewayConfig().sagaIntakeExecutor(
            sagaCommandExecutor = ManualExecutor(),
            sagaProps = SagaProcessorProperties(intakeCapacity = 7),
            meterRegistry = registry,
        ) as SagaIntakeGate

        assertEquals(7, gate.capacity)
        assertEquals(7.0, registry.find("saga.intake.capacity").gauge()!!.value())
    }

    @Test
    fun `publishes the wait, the free slots, the blocked threads and the bound`() {
        val registry = SimpleMeterRegistry()
        val delegate = ManualExecutor()
        val gate = SagaIntakeGate(capacity = 2, delegate = delegate, meterRegistry = registry)

        for (metric in listOf("saga.intake.permits.available", "saga.intake.blocked", "saga.intake.capacity")) {
            assertNotNull(registry.find(metric).gauge(), "$metric is missing — the gate is invisible in a run")
        }
        assertEquals(2.0, registry.find("saga.intake.capacity").gauge()!!.value())
        assertEquals(2.0, registry.find("saga.intake.permits.available").gauge()!!.value())

        gate.execute { }

        assertEquals(1.0, registry.find("saga.intake.permits.available").gauge()!!.value())
        assertEquals(
            1L,
            registry.find("saga.intake.wait").timer()!!.count(),
            "every admission is timed, including the ones that did not wait",
        )
        assertEquals(0.0, registry.find("saga.intake.blocked").gauge()!!.value())
    }
}
