package pl.szymanski.wiktor.db

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import javax.sql.DataSource

/**
 * Which connection pool the current thread's next transaction should draw from.
 *
 * TO-3-mod-A exists to give TO the pool topology ES already has. On the ES branches the split is
 * free: `AxonConfig` wires Axon's storage engine, token store and saga store to a dedicated
 * `axonDataSource`, while Spring Data JDBC repositories keep the primary pool — so a saturated
 * write path cannot starve HTTP reads. TO has one flat pool, which is the mechanism behind the
 * TO-2 collapse: Hikari pegged at 300 active / ~119 pending, and the accept path, sharing the
 * pool, backed up and halved throughput.
 *
 * TO cannot copy ES's wiring directly, because both lanes go through the SAME Spring Data JDBC
 * repositories — there is no second set of DAOs to point at a second `DataSource`. Hence routing:
 * one `DataSource` bean as far as Spring, Flyway and the transaction manager are concerned,
 * delegating per-thread to one of two Hikari pools.
 */
enum class DbLane {
    /** HTTP reads. Mirrors ES's primary pool, which serves only the projection GETs. */
    APP,

    /**
     * Everything that writes: the accept transaction, the order workers, the retry pool and the
     * Modulith republisher. Mirrors ES's `axonDataSource` — note that on ES the accept path is a
     * WRITE-lane cost too, because `InventoryService.createOrderReservation` runs `sendAndWait`
     * on the Tomcat thread and `SimpleCommandBus` handles it there, drawing from the Axon pool.
     */
    WRITE,
}

/**
 * The routing key, carried on the thread.
 *
 * It MUST be set before the transaction begins: `DataSourceTransactionManager` resolves the
 * `DataSource` once, at transaction start, and binds that connection for the whole transaction.
 * Setting it inside an already-transactional method is a no-op that silently leaves the work on
 * the default lane — which is why the lane is applied by executor decorators and at the service
 * entry points, never inside a `@Transactional` handler.
 */
object DbLaneContext {

    private val current = ThreadLocal.withInitial { DbLane.APP }

    fun current(): DbLane = current.get()

    /**
     * Runs [block] on [lane], restoring the previous value afterwards. Restoring rather than
     * clearing matters on pooled threads: a task that left the lane set would silently move every
     * later task on that thread onto the wrong pool.
     */
    fun <T> on(lane: DbLane, block: () -> T): T {
        val previous = current.get()
        current.set(lane)
        return try {
            block()
        } finally {
            current.set(previous)
        }
    }

    /** [on] as a decorator, for handing whole tasks to an executor. */
    fun wrap(lane: DbLane, task: Runnable): Runnable = Runnable { on(lane) { task.run() } }
}

/**
 * Presents two Hikari pools as the single `DataSource` the rest of the application expects.
 *
 * `determineCurrentLookupKey` is consulted on every `getConnection()`, i.e. once per transaction
 * under `DataSourceTransactionManager`. A connection is returned to the pool that issued it, so
 * routing cannot cross-contaminate the pools; the only failure mode is a lane set too late, which
 * sends work to [DbLane.APP].
 */
class LaneRoutingDataSource(app: DataSource, write: DataSource) : AbstractRoutingDataSource() {

    init {
        setTargetDataSources(mapOf<Any, Any>(DbLane.APP to app, DbLane.WRITE to write))
        // Anything that never declares a lane — actuator health, Flyway if it ever shared this
        // bean, a stray @Scheduled — lands on APP rather than failing.
        setDefaultTargetDataSource(app)
        afterPropertiesSet()
    }

    override fun determineCurrentLookupKey(): Any = DbLaneContext.current()
}
