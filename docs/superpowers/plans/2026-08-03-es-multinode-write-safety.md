# ES Multi-Node Write Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a failed Axon command in `OrderReservationSaga` end as a `FAILED` order instead of parking the order in `PENDING` forever, on all four ES branches.

**Architecture:** Two changes. (1) `SQLStateResolver` on the JDBC event storage engine so a Postgres `23505` surfaces as `ConcurrencyException` and becomes retryable by the existing `ConcurrencyRetryScheduler` — needed on ES-1/2/3, already present on ES-4. (2) Every `commandGateway.send` in the saga gets a `whenComplete` failure disposition; a failed reserve or complete triggers compensation plus `FailOrderCommand`, and the resulting `OrderFailedEvent` comes back to a new `@EndSaga` handler which is the only place `SagaLifecycle` may legally be touched.

**Tech Stack:** Kotlin 2.3, Spring Boot 4.0.6 (Spring MVC / Tomcat), Axon Framework 4.11.2, JDBC event store on PostgreSQL, JUnit 5, MockK 1.13.13, `axon-test` 4.11.2, Testcontainers 1.20.4, Micrometer/Prometheus.

**Spec:** `docs/superpowers/specs/2026-08-03-es-multinode-write-safety-design.md`

## Global Constraints

- **Branches are the deliverable.** Work directly on `ES-2`, `ES-1`, `ES-3`, `ES-4` — no feature branch, no worktree. Commit to each variant branch.
- **Order of work is fixed:** ES-2 fully (code + tests + reproduction run) before porting to ES-1, ES-3, ES-4. Do not port an unverified fix.
- **`JAVA_HOME` in this environment points at a missing JDK.** Every gradle command must be run as `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew ...`.
- **Never touch `k6/` or `docker-compose.bench.yml`.** They are byte-identical across ES-2 and ES-4 and must stay so. Acceptance: `git diff --stat ES-2 ES-4 -- k6 docker-compose.bench.yml` stays empty.
- **`axon.saga.total-segments` stays at 60** on every ES branch. Do not change it.
- **`REPLICAS` is set in `.env` only.** Never pass `--scale` to docker compose; mixing it with `deploy.replicas` makes Compose remove middle replicas.
- **After every branch switch, tear the stack down completely** before running anything. `git checkout` changes files but nothing in the running stack: the old branch's API container keeps appending events into the shared database, and Prometheus keeps serving the previous branch's scrape config because the bind-mounted file gets a new inode. See Task 5 Step 1 for the exact teardown.
- **Metric names and histogram bounds must stay identical across branches.** Any new timer needs explicit `minimum-expected-value` / `maximum-expected-value` in `application.yaml`; Micrometer's default Timer max is 30 s, which silently collapses every larger sample into `+Inf`.
- **Commit after every task.** Frequent, small commits.

---

### Task 1: Classify `23505` as a retryable conflict on ES-2

Adds the `persistenceExceptionResolver` that `ES-4:AxonConfig.kt:152` already has. Without it `AbstractEventStorageEngine.handlePersistenceException` raises a generic `EventStoreException`, which `ConcurrencyRetryScheduler.kt:32-34` does not match, so a lost write race is never retried.

There is no meaningful unit test for this — it is a builder configuration whose only observable effect requires two JVMs racing on one aggregate. It is verified for real in Task 5, where `inventory_optimistic_retry_total` must become non-zero at `REPLICAS=2`. Do not fabricate a test that just asserts Axon's own `SQLStateResolver` behaviour.

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/config/AxonConfig.kt:119-135`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: nothing consumed by later tasks. Behavioural precondition for Task 5.

- [ ] **Step 1: Confirm you are on ES-2**

Run: `git rev-parse --abbrev-ref HEAD`
Expected: `ES-2`

- [ ] **Step 2: Add the import**

In `src/main/kotlin/pl/szymanski/wiktor/config/AxonConfig.kt`, add this import alongside the other `org.axonframework.eventsourcing.eventstore.jdbc` imports. Note the package is `...eventstore.jpa`, not `.jdbc` — that is where Axon 4 puts `SQLStateResolver`, and it is the same import `ES-4` uses.

```kotlin
import org.axonframework.eventsourcing.eventstore.jpa.SQLStateResolver
```

- [ ] **Step 3: Add the builder call**

Replace the `JdbcEventStorageEngine.builder()` chain in `eventStorageEngine` with:

```kotlin
        val jdbc = JdbcEventStorageEngine.builder()
            .connectionProvider(DataSourceConnectionProvider(axonDataSource))
            .transactionManager(axonTransactionManager)
            .schema(eventSchema)
            .eventSerializer(eventSerializer)
            .snapshotSerializer(eventSerializer)
            // Without this, a Postgres 23505 on (aggregate_identifier, sequence_number) surfaces as a
            // generic EventStoreException and ConcurrencyRetryScheduler — which matches only
            // ConcurrencyException — never retries it. Only reachable at REPLICAS>1, where the
            // JVM-local LockFactory no longer serialises writers to the same aggregate.
            .persistenceExceptionResolver(SQLStateResolver())
            .build()
        return TimedEventStorageEngine(jdbc, meterRegistry)
```

The resolver is consulted inside `JdbcEventStorageEngine.appendEvents`, beneath the `TimedEventStorageEngine` decorator, so the decorator sees an already-translated `ConcurrencyException`. `TimedEventStorageEngine.kt:44-46` wraps the call in `Timer.recordCallable`, which propagates exceptions unchanged.

- [ ] **Step 4: Verify it compiles and the existing suite still passes**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pl/szymanski/wiktor/config/AxonConfig.kt
git commit -m "Classify duplicate-key conflicts as retryable ConcurrencyException"
```

---

### Task 2: Fail the order when a reservation command exhausts its retries

The core fix. `sendNextReservation` currently discards the future from `commandGateway.send`, so an exhausted retry appends no event, the `correlationId` association never fires again, and the order stays `PENDING` forever.

The failure cannot end the saga directly: `SagaLifecycle` resolves the current saga from a ThreadLocal bound to the saga processor's unit of work, and the `whenComplete` callback runs on a `sagaCommandExecutor` pool thread (`CommandGatewayConfig.kt:33`) where that ThreadLocal is empty — calling `SagaLifecycle.end()` there throws `IllegalStateException: No current Saga`. So the callback sends `FailOrderCommand`, and the resulting `OrderFailedEvent` re-enters saga scope through a new `@EndSaga` handler. That handler matches because `@StartSaga` on `OrderCreatedEvent(associationProperty = "orderId")` already registered the `orderId` association.

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`
- Create: `src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, all in `OrderReservationSaga`, used by Task 3:
  - `private fun abandon(orderId: String, toRelease: List<OrderItem>, stage: String, cause: Throwable)`
  - `private fun releaseAll(orderId: String, toRelease: List<OrderItem>)`
  - `private fun sendFailOrder(orderId: String, reason: String)`
  - `fun on(event: OrderFailedEvent)` — annotated `@EndSaga @SagaEventHandler(associationProperty = "orderId")`
  - Counter `saga.command.failed` with tag `stage`, values `reserve` / `complete` / `release` / `fail-order`.
  - `recordSagaEnd(outcome: String)` gains a third outcome value, `"command_failed"`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt`.

Note on the resource injector: `SagaTestFixture.registerResource` uses Axon's `SimpleResourceInjector`, which only injects members annotated with `javax.inject.Inject`. This saga uses Spring's `@Autowired`, so `registerResource` would silently leave the fields null. A reflective `registerResourceInjector` is used instead — it is deterministic and does not depend on annotation scanning.

The executor is same-thread (`Executor { it.run() }`) so the `whenComplete` callback runs inline and the fixture's assertions are not racy.

```kotlin
package pl.szymanski.wiktor.domain.saga

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.modelling.saga.ResourceInjector
import org.axonframework.test.saga.SagaTestFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.szymanski.wiktor.domain.InventoryReservedEvent
import pl.szymanski.wiktor.domain.OrderCreatedEvent
import pl.szymanski.wiktor.domain.OrderFailedEvent
import pl.szymanski.wiktor.domain.OrderItem
import pl.szymanski.wiktor.service.command.FailOrderCommand
import pl.szymanski.wiktor.service.command.ReleaseReservationCommand
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class OrderReservationSagaTest {

    private lateinit var fixture: SagaTestFixture<OrderReservationSaga>
    private val gateway: CommandGateway = mockk()

    /** Every command the saga dispatched, in order. */
    private val sent = mutableListOf<Any>()

    /** Reserve commands for this item id complete exceptionally; everything else succeeds. */
    private var failingItemId: String? = null

    private val orderId = "ORDER-1"
    private val correlationId = UUID.randomUUID()
    private val items = listOf(OrderItem("ITEM-1", 2), OrderItem("ITEM-2", 3))

    @BeforeEach
    fun setUp() {
        sent.clear()
        failingItemId = null

        val captured = slot<Any>()
        every { gateway.send<Any?>(capture(captured)) } answers {
            val command = captured.captured
            sent.add(command)
            // NOTE: SagaReserveItemCommand's aggregate id property is `id`, not `itemId`.
            // Only OrderItem uses `itemId`.
            val shouldFail = command is SagaReserveItemCommand && command.id == failingItemId
            if (shouldFail) CompletableFuture.failedFuture<Any?>(RuntimeException("injected append failure"))
            else CompletableFuture.completedFuture<Any?>(null)
        }

        fixture = SagaTestFixture(OrderReservationSaga::class.java)
        fixture.registerResourceInjector(ResourceInjector { saga -> inject(saga) })
    }

    private fun inject(saga: Any) {
        setField(saga, "commandGateway", gateway)
        setField(saga, "commandExecutor", Executor { it.run() })
        setField(saga, "meterRegistry", SimpleMeterRegistry())
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = OrderReservationSaga::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    @Test
    fun `fails the order when the first reservation command exhausts its retries`() {
        failingItemId = "ITEM-1"

        fixture.givenNoPriorActivity()
            .whenPublishingA(OrderCreatedEvent(orderId, "user-1", items, correlationId))

        val failCommands = sent.filterIsInstance<FailOrderCommand>()
        assertEquals(1, failCommands.size, "expected exactly one FailOrderCommand, got: $sent")
        assertEquals(orderId, failCommands.single().orderId)
        assertTrue(
            sent.filterIsInstance<ReleaseReservationCommand>().isEmpty(),
            "nothing was reserved yet, so nothing should be released",
        )
    }

    @Test
    fun `releases only the already-reserved lines when a later reservation fails`() {
        failingItemId = "ITEM-2"

        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .whenPublishingA(InventoryReservedEvent("ITEM-1", correlationId, 2))

        val releases = sent.filterIsInstance<ReleaseReservationCommand>()
        assertEquals(1, releases.size, "expected one release for ITEM-1 only, got: $sent")
        assertEquals("ITEM-1", releases.single().id)
        assertEquals(2, releases.single().quantity)
        assertEquals(1, sent.filterIsInstance<FailOrderCommand>().size)
    }

    @Test
    fun `ends the saga when the order is failed externally`() {
        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", items, correlationId))
            .whenPublishingA(OrderFailedEvent(orderId, "reserve command failed"))
            .expectActiveSagas(0)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.domain.saga.OrderReservationSagaTest"`
Expected: FAIL. The first two tests fail on `expected exactly one FailOrderCommand, got: [SagaReserveItemCommand(...)]` because nothing handles the failed future. The third fails on `expectActiveSagas` finding 1 active saga because no handler for `OrderFailedEvent` exists.

If instead you see `NoSuchFieldException: meterRegistry`, you are not on ES-2 — ES-1/ES-3/ES-4 have no such field yet, and they are handled in Tasks 6-8.

- [ ] **Step 3: Add the `OrderFailedEvent` import and the `EndSaga` import**

In `src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`:

```kotlin
import org.axonframework.modelling.saga.EndSaga
import pl.szymanski.wiktor.domain.OrderFailedEvent
```

- [ ] **Step 4: Add the failure-disposition helpers**

Add these three private methods to `OrderReservationSaga`, next to `recordSagaEnd`:

```kotlin
    // Terminal disposition for a command that failed AFTER ConcurrencyRetryScheduler gave up.
    // Runs on a commandExecutor pool thread, outside saga scope, so it must not touch
    // SagaLifecycle: it sends FailOrderCommand instead and lets the resulting OrderFailedEvent
    // come back to on(OrderFailedEvent) on the saga processor thread.
    private fun abandon(orderId: String, toRelease: List<OrderItem>, stage: String, cause: Throwable) {
        log.error("[SAGA] {} command failed orderId={} — failing order", stage, orderId, cause)
        meterRegistry.counter("saga.command.failed", "stage", stage).increment()
        releaseAll(orderId, toRelease)
        sendFailOrder(orderId, "$stage command failed: ${cause.javaClass.simpleName}")
    }

    private fun releaseAll(orderId: String, toRelease: List<OrderItem>) {
        toRelease.forEach { item ->
            commandGateway.send<Any?>(ReleaseReservationCommand(item.itemId, item.quantity))
                .whenComplete { _, ex ->
                    if (ex != null) {
                        // Reserved stock stays held. Counted rather than retried: a release that
                        // cannot be applied has no second escape hatch either.
                        log.error("[SAGA] compensation failed itemId={} orderId={}", item.itemId, orderId, ex)
                        meterRegistry.counter("saga.command.failed", "stage", "release").increment()
                    }
                }
        }
    }

    private fun sendFailOrder(orderId: String, reason: String) {
        commandGateway.send<Any?>(FailOrderCommand(orderId, reason))
            .whenComplete { _, ex ->
                if (ex != null) {
                    // Residual dead end: the order stays PENDING. There is no further escape hatch
                    // that does not recurse, so this is made visible instead of handled.
                    log.error("[SAGA] FailOrderCommand failed orderId={} — order remains PENDING", orderId, ex)
                    meterRegistry.counter("saga.command.failed", "stage", "fail-order").increment()
                }
            }
    }
```

- [ ] **Step 5: Wire the reserve dispatch to `abandon`**

Replace `sendNextReservation` in full:

```kotlin
    private fun sendNextReservation() {
        val item = items[currentIndex]
        // Snapshotted on the saga processor thread. Safe because reservations are strictly
        // sequential — exactly one command is in flight per saga, and the only thing that mutates
        // reservedItems is the success handler for the very command being dispatched here.
        val orderIdCopy = orderId
        val toRelease = reservedItems.toList()
        log.debug("[SAGA] reserving itemId={} ({}/{}) orderId={}", item.itemId, currentIndex + 1, items.size, orderId)
        commandExecutor.execute {
            commandGateway.send<Any?>(SagaReserveItemCommand(item.itemId, item.quantity, correlationId))
                .whenComplete { _, ex -> if (ex != null) abandon(orderIdCopy, toRelease, "reserve", ex) }
        }
    }
```

- [ ] **Step 6: Add the `@EndSaga` handler**

Add after the `on(event: InventoryReservationFailedEvent)` handler:

```kotlin
    // The only legal place to end a saga that was abandoned off-thread. Reached via
    // abandon() -> FailOrderCommand -> OrderAggregate -> OrderFailedEvent -> this processor.
    // The out-of-stock path never arrives here: it calls SagaLifecycle.end() inline, so by the
    // time its OrderFailedEvent is read back the saga and its associations are already gone.
    @EndSaga
    @SagaEventHandler(associationProperty = "orderId")
    fun on(event: OrderFailedEvent) {
        log.warn("[SAGA] order failed outside the saga orderId={} reason={}", event.orderId, event.reason)
        recordSagaEnd("command_failed")
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.domain.saga.OrderReservationSagaTest"`
Expected: PASS, 3 tests.

- [ ] **Step 8: Run the whole suite**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt \
        src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt
git commit -m "Fail the order when a reservation command exhausts its retries"
```

---

### Task 3: Cover the remaining three dispatch sites

`sendNextReservation` was one of four `commandGateway.send` calls that discard their future. Two of the other three can also strand an order: if `CompleteOrderCommand` fails the order stays `PENDING` even though the saga already ended, and if `FailOrderCommand` fails the same happens on the compensation path. The `runCatching` at the compensation site is ineffective — it catches only synchronous dispatch errors, never the asynchronous command failure.

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`
- Modify: `src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt`

**Interfaces:**
- Consumes from Task 2: `abandon`, `releaseAll`, `sendFailOrder`, the `saga.command.failed` counter.
- Produces: nothing new. After this task all four dispatch sites have a disposition.

- [ ] **Step 1: Write the failing test**

Add to `OrderReservationSagaTest`. This needs a completion command that fails, so add a second switch next to `failingItemId` — put this field beside it:

```kotlin
    /** When true, CompleteOrderCommand completes exceptionally. */
    private var failComplete: Boolean = false
```

Reset it in `setUp` alongside the others:

```kotlin
        failComplete = false
```

Extend the stub in `setUp` so the `shouldFail` decision also covers completion. Replace the `shouldFail` line with:

```kotlin
            val shouldFail = (command is SagaReserveItemCommand && command.id == failingItemId) ||
                (command is CompleteOrderCommand && failComplete)
```

Add the import:

```kotlin
import pl.szymanski.wiktor.service.command.CompleteOrderCommand
```

And add the test:

```kotlin
    @Test
    fun `fails and releases the whole order when the completion command fails`() {
        failComplete = true
        val singleLine = listOf(OrderItem("ITEM-1", 2))

        fixture.givenAPublished(OrderCreatedEvent(orderId, "user-1", singleLine, correlationId))
            .whenPublishingA(InventoryReservedEvent("ITEM-1", correlationId, 2))

        val releases = sent.filterIsInstance<ReleaseReservationCommand>()
        assertEquals(1, releases.size, "the whole order should be released, got: $sent")
        assertEquals("ITEM-1", releases.single().id)
        assertEquals(1, sent.filterIsInstance<FailOrderCommand>().size)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.domain.saga.OrderReservationSagaTest"`
Expected: FAIL on `the whole order should be released, got: [SagaReserveItemCommand(...), CompleteOrderCommand(...)]` — the failed completion is still discarded.

- [ ] **Step 3: Wire the completion dispatch to `abandon`**

Replace the `else` branch of `on(event: InventoryReservedEvent)`:

```kotlin
        } else {
            log.info("[SAGA] all items reserved, completing orderId={}", orderId)
            // reservedItems.add above already ran, so this snapshot is the WHOLE order, not a prefix.
            val orderIdCopy = orderId
            val toRelease = reservedItems.toList()
            commandExecutor.execute {
                commandGateway.send<Any?>(CompleteOrderCommand(orderIdCopy))
                    .whenComplete { _, ex -> if (ex != null) abandon(orderIdCopy, toRelease, "complete", ex) }
            }
            // Recorded as "completed" before the command's verdict is known. If it later fails,
            // saga.command.failed{stage="complete"} is what makes that visible — the saga has
            // already ended by then and cannot be re-tagged. OrderAggregate is uncontended
            // (one writer per order), so this is an infrastructure-only path.
            recordSagaEnd("completed")
            SagaLifecycle.end()
        }
```

- [ ] **Step 4: Replace the compensation path's ineffective `runCatching`**

Replace the body of `on(event: InventoryReservationFailedEvent)`:

```kotlin
    @SagaEventHandler(associationProperty = "correlationId")
    fun on(event: InventoryReservationFailedEvent) {
        log.warn("[SAGA] reservation failed itemId={} orderId={} reason={}", event.id, orderId, event.reason)
        val toRelease = reservedItems.toList()
        val failReason = event.reason
        val orderIdCopy = orderId
        commandExecutor.execute {
            releaseAll(orderIdCopy, toRelease)
            sendFailOrder(orderIdCopy, failReason)
        }
        recordSagaEnd("failed")
        SagaLifecycle.end()
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.domain.saga.OrderReservationSagaTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Confirm `runCatching` is gone from the saga**

Run: `grep -c runCatching src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`
Expected: `0`

- [ ] **Step 7: Run the whole suite**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt \
        src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt
git commit -m "Give the completion and compensation dispatches a failure disposition"
```

---

### Task 4: End-to-end regression test against a real PostgreSQL

The unit tests use a same-thread executor and a mocked gateway. This test boots the real Spring context against Testcontainers PostgreSQL, injects a genuine `ConcurrencyException` at the command handler, and asserts the order reaches `REJECTED` and the saga row is deleted — the two things that were wrong in production.

Fault injection uses a **handler** interceptor, not a dispatch interceptor. A dispatch interceptor throws synchronously out of `send()`, which is the path `runCatching` used to catch and is not the path being fixed. A handler interceptor makes the returned future complete exceptionally, which is the real failure shape.

`axon.saga.total-segments` is overridden to 1 for this test only — 60 tracking threads against a Testcontainers database is needless. This does not violate the "segments stay at 60" constraint, which is about `application.yaml`.

**Files:**
- Create: `src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt`

**Interfaces:**
- Consumes from Tasks 2-3: the failure disposition and the `@EndSaga` handler.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

```kotlin
package pl.szymanski.wiktor.integration

import org.axonframework.commandhandling.CommandBus
import org.axonframework.messaging.MessageHandlerInterceptor
import org.axonframework.modelling.command.ConcurrencyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.szymanski.wiktor.controller.CreateItemRequest
import pl.szymanski.wiktor.controller.CreateOrderRequest
import pl.szymanski.wiktor.controller.CreateOrderResponse
import pl.szymanski.wiktor.controller.OrderItemRequest
import pl.szymanski.wiktor.service.command.SagaReserveItemCommand
import javax.sql.DataSource

private const val FAILING_ITEM = "ITEM-DOOMED"

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "axon.saga.total-segments=1",
        "axon.saga.replicas=1",
        "axon.jdbc.pool.size=10",
        "spring.datasource.hikari.maximum-pool-size=10",
        "snapshot.enabled=false",
    ],
)
class SagaCommandFailureIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("inventory")
            .withUsername("inventory")
            .withPassword("inventory")

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
        }
    }

    // Static nested @TestConfiguration classes are picked up automatically by @SpringBootTest.
    @TestConfiguration
    class FaultInjection {
        @Autowired
        fun failReservationsForDoomedItem(commandBus: CommandBus) {
            commandBus.registerHandlerInterceptor(
                MessageHandlerInterceptor { unitOfWork, chain ->
                    val payload = unitOfWork.message.payload
                    // `id`, not `itemId` — see SagaReserveItemCommand's declaration.
                    if (payload is SagaReserveItemCommand && payload.id == FAILING_ITEM) {
                        // Same exception SQLStateResolver produces from a 23505, so this exercises
                        // the retry-then-give-up path rather than a bare dispatch error.
                        throw ConcurrencyException("injected conflict for $FAILING_ITEM")
                    }
                    chain.proceed()
                },
            )
        }
    }

    @Autowired private lateinit var rest: TestRestTemplate
    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `an order whose reservation never succeeds ends REJECTED with no saga left behind`() {
        val jdbc = JdbcTemplate(dataSource)

        rest.postForEntity("/inventory", CreateItemRequest(FAILING_ITEM, 100, 0), Void::class.java)

        val orderId = rest.postForEntity(
            "/inventory/orders",
            CreateOrderRequest("user-1", listOf(OrderItemRequest(FAILING_ITEM, 1))),
            CreateOrderResponse::class.java,
        ).body!!.orderId

        val status = pollFor(60_000) {
            jdbc.query("SELECT status FROM orders WHERE order_id = ?", { rs, _ -> rs.getString(1) }, orderId)
                .firstOrNull()
                ?.takeIf { it != "PENDING" }
        }
        assertEquals("REJECTED", status, "order $orderId never reached a terminal status")

        val sagaRows = pollFor(30_000) {
            jdbc.queryForObject("SELECT count(*) FROM saga_entry", Long::class.java)?.takeIf { it == 0L }
        }
        assertEquals(0L, sagaRows, "the abandoned saga was never ended")
    }

    /** Polls [supplier] until it returns non-null or [timeoutMs] elapses. */
    private fun <T> pollFor(timeoutMs: Long, supplier: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(250)
        }
        return null
    }
}
```

- [ ] **Step 2: Run it and confirm it passes**

Unlike the previous tasks this is a regression guard written after the fix, so it should pass immediately. Docker must be running — Testcontainers pulls `postgres:16-alpine` on first run.

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.integration.SagaCommandFailureIT"`
Expected: PASS

- [ ] **Step 3: Prove the test actually detects the bug**

Temporarily revert the fix and confirm the test fails, so you know it is not passing vacuously.

```bash
git stash push src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt
```

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test --tests "pl.szymanski.wiktor.integration.SagaCommandFailureIT"`
Expected: FAIL with `order <id> never reached a terminal status ==> expected: <REJECTED> but was: <null>`

Then restore:

```bash
git stash pop
```

- [ ] **Step 4: Re-run to confirm green again**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt
git commit -m "Add end-to-end regression test for abandoned reservation commands"
```

---

### Task 5: Reproduce the original failure at REPLICAS=2 and confirm it is gone

The authoritative check. The recorded failure was 3537 of 10401 orders stuck `PENDING` on ES-2 at `REPLICAS=2`, `RATE=30`, `DURATION=3m`, workload `distinct=6 items/order=4`, with `saga_entry` count and `EventStoreException` count both also exactly 3537.

**Files:**
- Modify: `.env` (temporarily, then restore)
- Creates: a new directory under `bench-results/` (untracked)

**Interfaces:**
- Consumes from Tasks 1-3: the retry classification and the failure disposition.
- Produces: the evidence quoted in Task 9's documentation updates.

- [ ] **Step 1: Tear down the existing stack completely**

`git checkout` does not touch the running stack, and a leftover container from another branch will append events into this branch's event store invisibly. Prometheus also serves a stale scrape config because `git checkout` replaces the bind-mounted file with a new inode.

```bash
docker compose down --remove-orphans
docker compose up -d --force-recreate prometheus
docker compose down --remove-orphans
```

- [ ] **Step 2: Set the replica count and connection ceiling**

Edit `.env` and set both values. `REPLICAS` must be set here, not inline — compose auto-loads `.env` on every command, and `common.sh:24` derives `EXPECTED_REPLICAS` from it, which `reset.sh:63-65` asserts against the actual container count before the measured run starts.

```
REPLICAS=2
PG_MAX_CONNECTIONS=950
```

`PG_MAX_CONNECTIONS` is ~350 per ES replica (Hikari 50 + Axon 300) on top of the 600 default; 950 covers two replicas.

- [ ] **Step 3: Run the reproduction**

```bash
SCENARIO=steady RATE=30 DURATION=3m DISTINCT_ITEMS=6 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
```

The knob is `ITEMS_PER_ORDER`, not `LINES_PER_ORDER` (`bench.sh:116-119`).

- [ ] **Step 4: Assert zero stranded orders**

```bash
docker compose exec -T postgres-es psql -U inventory -d inventory \
  -c "SELECT status, count(*) FROM orders GROUP BY status ORDER BY status;"
```

Expected: rows for `CONFIRMED` and `REJECTED` only. **Zero `PENDING`.** The order projection writes `PENDING/CONFIRMED/REJECTED` — `REJECTED` is the projection's spelling of the aggregate's `FAILED`.

- [ ] **Step 5: Assert the saga store drained**

```bash
docker compose exec -T postgres-es psql -U inventory -d inventory \
  -c "SELECT count(*) FROM saga_entry;"
```

Expected: `0`. If this is non-zero but the order counts are clean, re-run it after ~30 s before treating it as a failure: `OrderFailedEvent` is persisted before the saga ends, so the saga rows disappear one processor hop after the orders reach a terminal status.

- [ ] **Step 6: Confirm the new code paths actually fired**

```bash
curl -s 'http://localhost:9090/api/v1/query?query=sum(saga_completed_total)by(outcome)' | python3 -m json.tool
curl -s 'http://localhost:9090/api/v1/query?query=sum(saga_command_failed_total)by(stage)' | python3 -m json.tool
curl -s 'http://localhost:9090/api/v1/query?query=sum(inventory_optimistic_retry_total)' | python3 -m json.tool
```

Expected:
- `inventory_optimistic_retry_total` > 0 — proves Task 1 works; conflicts are now classified as retryable. Before the fix this was flat at zero because `EventStoreException` never matched.
- `saga_completed_total{outcome="command_failed"}` > 0 — proves Task 2's terminal path is being reached.
- `saga_command_failed_total{stage="fail-order"}` should be 0. Any non-zero value is the residual dead end and means some orders are still stuck; cross-check against Step 4.

Every query is `sum()`-wrapped because at `REPLICAS=2` an unaggregated expression returns one series per replica.

- [ ] **Step 7: Check the harness verdict**

```bash
python3 k6/bench/evaluate.py bench-results/ES-2_steady_*/
```

Expected: not `INVALID`. `INVALID` means the measurement itself was broken — backlog never drained, scrape gap, API restarted mid-run — which is a different thing from a slow system. A `FAIL` on latency thresholds is acceptable here and expected: two replicas contending on six items is a deliberately hostile workload. The question this task answers is whether orders terminate, not whether they are fast.

- [ ] **Step 8: Restore single-node configuration**

`REPLICAS=1` remains the only measurement-grade configuration. Edit `.env` back to:

```
REPLICAS=1
PG_MAX_CONNECTIONS=600
```

Then: `docker compose down --remove-orphans`

- [ ] **Step 9: Record the numbers**

Write the observed `PENDING` count, `saga_entry` count, and the three metric values into the run's directory so Task 9 can quote them:

```bash
{
  echo "Reproduction of the REPLICAS=2 stall, after the command-failure fix."
  echo "Baseline before the fix: 3537 of 10401 orders stuck PENDING."
  echo "--- orders by status ---"
  docker compose exec -T postgres-es psql -U inventory -d inventory \
    -c "SELECT status, count(*) FROM orders GROUP BY status ORDER BY status;"
} > bench-results/REPLICAS2-verification.txt
```

Note this must run *before* Step 8's `docker compose down`. If you already tore the stack down, re-run Steps 1-3 or transcribe the values by hand.

- [ ] **Step 10: Commit**

`bench-results/` is untracked and stays that way — it is measurement output, not source. Nothing to commit in this task. Confirm the tree is clean apart from `bench-results/` and `reports/`:

Run: `git status --short`
Expected: only untracked `bench-results/` and `reports/` entries.

---

### Task 6: Port to ES-1

`OrderReservationSaga.kt` is byte-identical on ES-1, ES-3 and ES-4 today, and ES-2's version is that file plus the metrics block. After Tasks 2-3 all four converge on ES-2's file, so the port is a checkout rather than a re-edit.

ES-1 additionally needs the `saga.lifetime` histogram bounds in `application.yaml`. Without them the new timer inherits Micrometer's 30 s default maximum and every longer saga collapses into `+Inf`.

ES-1 has no benchmark harness — it still carries the legacy `k6/run.sh`, and `common.sh` hard-fails there — so `./gradlew test` is the whole verification.

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/config/AxonConfig.kt`
- Modify: `src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt`
- Create: `src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt`

**Interfaces:**
- Consumes from Tasks 1-3: the final `AxonConfig.kt` and `OrderReservationSaga.kt` on ES-2.
- Produces: nothing consumed by later tasks. Tasks 6, 7 and 8 are independent of each other.

- [ ] **Step 1: Switch branches and tear down the stack**

```bash
docker compose down --remove-orphans
git checkout ES-1
```

- [ ] **Step 2: Take the saga and its tests from ES-2**

```bash
git checkout ES-2 -- src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt
git checkout ES-2 -- src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt
git checkout ES-2 -- src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt
```

This brings the metrics block (`meterRegistry`, `createdAtMillis`, the `@Timestamp` parameter, `recordSagaEnd`) across as well, which is intended — the spec calls for `recordSagaEnd` on every ES branch.

- [ ] **Step 3: Add `SQLStateResolver` to `AxonConfig`**

ES-1's `AxonConfig.kt` differs from ES-2's (no snapshot trigger bean), so do not check the whole file out. Make the same two edits as Task 1, Steps 2-3: add

```kotlin
import org.axonframework.eventsourcing.eventstore.jpa.SQLStateResolver
```

and add this line to the `JdbcEventStorageEngine.builder()` chain, immediately before `.build()`:

```kotlin
            .persistenceExceptionResolver(SQLStateResolver())
```

- [ ] **Step 4: Add the `saga.lifetime` histogram bounds**

In `src/main/resources/application.yaml`, under `management.metrics.distribution`, add `saga.lifetime` to `percentiles-histogram` and give it explicit bounds. ES-1 has no `minimum-expected-value` block at all, so it must be created:

```yaml
      percentiles-histogram:
        # ... existing entries unchanged ...
        saga.lifetime: true
      minimum-expected-value:
        saga.lifetime: 1ms
      maximum-expected-value:
        projection.lag: 10m
        saga.lifetime: 10m
```

Keep any existing `maximum-expected-value` entries and add `saga.lifetime` alongside them.

- [ ] **Step 5: Verify the saga file now matches ES-2 exactly**

Run: `git diff --stat ES-2 -- src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`
Expected: empty output.

- [ ] **Step 6: Run the tests**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test`
Expected: BUILD SUCCESSFUL, including the 4 saga tests and `SagaCommandFailureIT`.

If `SagaCommandFailureIT` fails on a missing property, check ES-1's `application.yaml` actually has `axon.saga.total-segments` and `axon.jdbc.pool.size` — the test overrides them by name.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pl/szymanski/wiktor/config/AxonConfig.kt \
        src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt \
        src/main/resources/application.yaml \
        src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt \
        src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt
git commit -m "Make saga command failure terminal on ES-1"
```

---

### Task 7: Port to ES-3

Same as Task 6. ES-3 differs from ES-1 in that it already has `order.e2e.time` bounds and a `cache.enabled` block, and it uses Axon's stock `CachingEventSourcingRepository` over `StrongCache`. No cache work is needed — Axon evicts the cache entry on rollback, so a retried command cold-replays and sees post-conflict state.

Like ES-1, ES-3 has no benchmark harness.

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/config/AxonConfig.kt`
- Modify: `src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt`
- Create: `src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt`

**Interfaces:**
- Consumes from Tasks 1-3: the final `OrderReservationSaga.kt` on ES-2.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Switch branches and tear down the stack**

```bash
docker compose down --remove-orphans
git checkout ES-3
```

- [ ] **Step 2: Take the saga and its tests from ES-2**

```bash
git checkout ES-2 -- src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt
git checkout ES-2 -- src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt
git checkout ES-2 -- src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt
```

- [ ] **Step 3: Add `SQLStateResolver` to `AxonConfig`**

Same two edits as Task 1, Steps 2-3. Add the import:

```kotlin
import org.axonframework.eventsourcing.eventstore.jpa.SQLStateResolver
```

and add to the `JdbcEventStorageEngine.builder()` chain, immediately before `.build()`:

```kotlin
            .persistenceExceptionResolver(SQLStateResolver())
```

- [ ] **Step 4: Add the `saga.lifetime` histogram bounds**

ES-3 already has `maximum-expected-value` with `order.e2e.time: 10m`, but has no `minimum-expected-value` block. Under `management.metrics.distribution`:

```yaml
      percentiles-histogram:
        # ... existing entries unchanged ...
        saga.lifetime: true
      minimum-expected-value:
        order.e2e.time: 1ms
        saga.lifetime: 1ms
      maximum-expected-value:
        # ... existing entries unchanged ...
        saga.lifetime: 10m
```

Adding `order.e2e.time: 1ms` here is deliberate: ES-3 is currently missing it, and `CLAUDE.md` requires those bounds to be identical on every branch.

- [ ] **Step 5: Verify the saga file matches ES-2 exactly**

Run: `git diff --stat ES-2 -- src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`
Expected: empty output.

- [ ] **Step 6: Run the tests**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pl/szymanski/wiktor/config/AxonConfig.kt \
        src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt \
        src/main/resources/application.yaml \
        src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt \
        src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt
git commit -m "Make saga command failure terminal on ES-3"
```

---

### Task 8: Port to ES-4

ES-4 already has `.persistenceExceptionResolver(SQLStateResolver())` at `AxonConfig.kt:152`, so **do not touch `AxonConfig.kt` on this branch.** It also has `order.e2e.time` bounds already.

ES-4 carries the benchmark harness, so this is also where you confirm the `k6/` byte-identity invariant still holds.

**Files:**
- Modify: `src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt`
- Create: `src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt`

**Interfaces:**
- Consumes from Tasks 2-3: the final `OrderReservationSaga.kt` on ES-2.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Switch branches and tear down the stack**

```bash
docker compose down --remove-orphans
git checkout ES-4
```

- [ ] **Step 2: Confirm `SQLStateResolver` is already present**

Run: `grep -n "persistenceExceptionResolver" src/main/kotlin/pl/szymanski/wiktor/config/AxonConfig.kt`
Expected: one hit, `.persistenceExceptionResolver(SQLStateResolver())`. If this is empty, stop — the branch is not what this plan assumes.

- [ ] **Step 3: Take the saga and its tests from ES-2**

```bash
git checkout ES-2 -- src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt
git checkout ES-2 -- src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt
git checkout ES-2 -- src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt
```

- [ ] **Step 4: Add the `saga.lifetime` histogram bounds**

ES-4 already has both `minimum-expected-value` and `maximum-expected-value` blocks with `order.e2e.time` entries. Add only the three `saga.lifetime` lines:

```yaml
      percentiles-histogram:
        # ... existing entries unchanged ...
        saga.lifetime: true
      minimum-expected-value:
        order.e2e.time: 1ms
        saga.lifetime: 1ms
      maximum-expected-value:
        # ... existing entries unchanged ...
        saga.lifetime: 10m
```

- [ ] **Step 5: Verify the saga file matches ES-2 exactly**

Run: `git diff --stat ES-2 -- src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt`
Expected: empty output.

- [ ] **Step 6: Verify the harness is still byte-identical to ES-2**

Run: `git diff --stat ES-2 -- k6 docker-compose.bench.yml`
Expected: empty output. If this prints anything, you edited a file you should not have.

- [ ] **Step 7: Run the tests**

Run: `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt \
        src/main/resources/application.yaml \
        src/test/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSagaTest.kt \
        src/test/kotlin/pl/szymanski/wiktor/integration/SagaCommandFailureIT.kt
git commit -m "Make saga command failure terminal on ES-4"
```

---

### Task 9: Update the documentation and the durable notes

The `REPLICAS>1` warnings on every ES branch and in `.env` now describe a fixed defect. Leaving them in place means the next run is configured from a stale warning.

**Files:**
- Modify: `CLAUDE.md` on ES-1, ES-2, ES-3, ES-4
- Modify: `.env` on ES-2 (and any other branch whose `.env` carries the stall warning)
- Modify: `/home/wiktor/.claude/projects/-home-wiktor-Projects-Magisterka-InventoryItemReservation/memory/es_multinode_write_unsafe.md`
- Modify: `/home/wiktor/.claude/projects/-home-wiktor-Projects-Magisterka-InventoryItemReservation/memory/to_multinode_outbox_and_retry.md`

**Interfaces:**
- Consumes from Task 5: the measured `PENDING` count, `saga_entry` count, and the three metric values.
- Produces: nothing.

- [ ] **Step 1: Rewrite the `CLAUDE.md` scaling section on ES-2**

```bash
git checkout ES-2
```

In `CLAUDE.md`, replace the whole section headed `### REPLICAS>1 is wired up, but the domain is not multi-node safe yet` — including its numbered list of the two defects and the "Do not publish scale-out numbers" line — with the text below. Substitute the bracketed slots with the values recorded in Task 5 Step 9; everything else is literal.

```markdown
### `REPLICAS>1` is wired up; write races are now terminal, not permanent

The infrastructure is verified at `REPLICAS=3`: nginx spreads load, Prometheus finds all
targets, and the saga splits its 60 segments evenly (20/20/20, none unclaimed).

**ES is still not multi-node write-*correct*.** The only thing serialising writers to an
`InventoryItem` is a JVM-local `LockFactory`, so a second JVM removes it: two replicas load
the same aggregate at sequence N and both append at N+1, leaving the
`(aggregate_identifier, sequence_number)` unique constraint as the backstop. Horizontal write
scale-out cannot be demonstrated without real distributed concurrency control.

**What changed is the outcome of losing that race.** It used to be permanent:

1. `AxonConfig.eventStorageEngine` had no `persistenceExceptionResolver`, so a Postgres
   `23505` surfaced as a generic `EventStoreException` and `ConcurrencyRetryScheduler` — which
   matches only `ConcurrencyException` — never retried.
2. `OrderReservationSaga` discarded the future from every `commandGateway.send`, so an
   exhausted retry appended no event, the association never fired again, and the order stayed
   `PENDING` forever.

Both are fixed on all four ES branches. Conflicts are now retried (5 attempts, `25ms * 2^n`
capped at 500 ms), and a command that still fails triggers compensation plus
`FailOrderCommand`; the resulting `OrderFailedEvent` reaches an `@EndSaga` handler that ends
the saga. A lost race is therefore a `REJECTED` order — the same way TO has always degraded.

Measured on `ES-2` at `REPLICAS=2`, RATE=30, workload `distinct=6 items/order=4`: **[N]
PENDING orders** (baseline before the fix: 3537 of 10401), `saga_entry` drained to
**[N]**, `saga_completed_total{outcome="command_failed"}` = **[N]**,
`saga_command_failed_total{stage="fail-order"}` = **[N]**.

**`REPLICAS=1` is still the measurement-grade configuration**, because at `REPLICAS>1` the
rejection rate is an artefact of lost write races rather than of stock. Read multi-replica
runs as a contention study, not as throughput. `saga.completed{outcome="command_failed"}`
separates contention-driven rejections from genuine out-of-stock ones; it should be zero on
any single-node run.

**TO is unchanged by this work.** `InventoryService.processOrder` is `@Retryable` on
`OptimisticLockingFailureException` (4 attempts) and issues `FailOrderCommand` on exhaustion.
TO-1/TO-2 additionally claim each `event_publication` row with a database-level
`UPDATE … WHERE completion_date IS NULL`, so only one replica delivers; TO-3/TO-4 have no
such guard and rely on stock Modulith republication. None of it is load-tested at
`REPLICAS>1`.
```

- [ ] **Step 2: Update the `.env` warning on ES-2**

Replace the `# WARNING:` paragraph above `REPLICAS=1` — the one stating orders "stay PENDING forever (measured: 3537 of 10401 at REPLICAS=2)" — with:

```
# NOTE: REPLICAS>1 now produces a VALID but hostile measurement, not a broken one. Two
# replicas still race to append to the same InventoryItem — the LockFactory is JVM-local —
# but the loser is now retried and then REJECTED via FailOrderCommand rather than stranded
# in PENDING. Keep this at 1 for any run whose numbers you intend to publish: above 1 the
# rejection rate is an artefact of lost write races, not of stock. See the Scaling section
# of CLAUDE.md.
```

Keep the value at `1`, and keep the surrounding text about `REPLICAS` driving both the container count and `API_REPLICAS`, and the `--scale` warning.

- [ ] **Step 3: Propagate the `CLAUDE.md` change to the other three branches**

For each of ES-1, ES-3, ES-4: check out the branch, apply the equivalent edit, and commit. The wording is shared but the branch-specific facts differ — ES-1 and ES-3 have no benchmark harness, so their sections must not tell the reader to run `bench.sh`.

- [ ] **Step 4: Update `es_multinode_write_unsafe.md`**

Keep the file's `name: es_multinode_write_unsafe` slug unchanged so existing
`[[es_multinode_write_unsafe]]` links keep resolving. Replace the `description:` line with:

```
description: "ES-1..ES-4 lose write races at REPLICAS>1 (JVM-local LockFactory) but no longer park orders — fixed 2026-08-03; a lost race is now a REJECTED order"
```

Rewrite the body to record: both defects fixed on all four branches; the mechanism
(`SQLStateResolver` + `whenComplete` → `FailOrderCommand` → `@EndSaga on(OrderFailedEvent)`);
the Task 5 numbers against the 3537-of-10401 baseline; and that ES is still not multi-node
write-*correct*, only write-*terminal*.

Replace the "How to apply" section with this correction, which supersedes the current claim
that every ES baseline needs re-running:

```
**How to apply:** existing single-node baselines remain valid. At REPLICAS=1 the JVM-local
PessimisticLockFactory prevents 23505 entirely, so SQLStateResolver is a no-op and the
whenComplete failure branch is unreachable — the only single-node deltas are the new metric
series on ES-1/3/4 and one extra event type reaching the saga processor. The standing check
is that saga_completed_total{outcome="command_failed"} stays at zero on any REPLICAS=1 run;
a non-zero value falsifies the assumption and means the baselines do need re-running.
```

- [ ] **Step 5: Update `to_multinode_outbox_and_retry.md`**

Its framing — "the TO-vs-ES scale-out story is asymmetric in TO's favour" — is now wrong on the point that mattered. Both families degrade to terminal failures. Update that paragraph. Leave the TO-3/TO-4 duplicate-delivery gap and the "not load-tested at `REPLICAS>1`" caveat intact; neither was addressed by this work.

- [ ] **Step 6: Commit the branch documentation**

Memory files live outside the repository and need no commit. For each branch:

```bash
git add CLAUDE.md .env
git commit -m "Document that ES command failure is now terminal at REPLICAS>1"
```

`.env` is only staged on branches where it was actually edited — check `git status --short` first.

- [ ] **Step 7: Final check across all four branches**

```bash
for b in ES-1 ES-2 ES-3 ES-4; do
  echo "== $b"
  git diff --stat $b ES-2 -- src/main/kotlin/pl/szymanski/wiktor/domain/saga/OrderReservationSaga.kt
done
```

Expected: empty output under every branch — all four sagas converged on the same file.

```bash
git diff --stat ES-2 ES-4 -- k6 docker-compose.bench.yml
```

Expected: empty output.
