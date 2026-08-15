package pl.szymanski.wiktor.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import pl.szymanski.wiktor.db.DbLane
import pl.szymanski.wiktor.db.DbLaneContext
import pl.szymanski.wiktor.service.OrderRetryScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Proves the lane actually reaches the two executors that run write work. A missing decorator is
 * invisible: the orders still process, they just draw from `app-pool`, and the split that the
 * branch exists to test quietly does not happen.
 */
@SpringJUnitConfig(classes = [OrderWorkerConfig::class, DbLaneWiringTest.TestBeans::class])
class DbLaneWiringTest {

    @Autowired
    private lateinit var orderWorkerExecutor: TaskExecutor

    @Autowired
    private lateinit var orderRetryScheduler: OrderRetryScheduler

    @Configuration
    class TestBeans {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Test
    fun `order worker tasks run on the write lane`() {
        val lane = AtomicReference<DbLane>()
        val done = CountDownLatch(1)

        orderWorkerExecutor.execute {
            lane.set(DbLaneContext.current())
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "worker task never ran")
        assertEquals(DbLane.WRITE, lane.get(), "order-worker tasks would draw from app-pool")
    }

    @Test
    fun `retried attempts run on the write lane`() {
        val lane = AtomicReference<DbLane>()
        val done = CountDownLatch(1)

        orderRetryScheduler.schedule(1L) {
            lane.set(DbLaneContext.current())
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "retry task never ran")
        assertEquals(
            DbLane.WRITE, lane.get(),
            "a retried attempt is the same reserve transaction as a first attempt and must share " +
                "its pool",
        )
    }

    @Test
    fun `the submitting thread's lane is untouched by handing work to the worker pool`() {
        val done = CountDownLatch(1)
        orderWorkerExecutor.execute { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(
            DbLane.APP, DbLaneContext.current(),
            "submitting work must not move the CALLING thread's lane — on a Tomcat thread that " +
                "would put the next GET on the write pool",
        )
    }
}
