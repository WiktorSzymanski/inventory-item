# TO-3-Eventuate Per-Line Saga Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace TO-3's single all-lines write transaction with a per-line saga orchestrated by
Eventuate Tram Sagas 0.25.0, holding every other TO-3 property constant, so the campaign can price
a framework saga against TO-3 (one transaction per order) and TO-3-Saga (a hand-written per-line
saga on the same outbox).

**Architecture:** Eventuate rides Spring Modulith's `event_publication` registry instead of
Eventuate's `message` table, its CDC service or a broker (Task 3, 4). Every Eventuate bean is
constructed by hand rather than `@Import`ed, because the framework's `@Configuration` classes carry
`@Import` chains onto artifacts we exclude (Task 5). The domain half — per-line write transaction,
release, line coordinates — ports from TO-3-Saga minus its cursor, which Eventuate replaces
(Task 6). A participant answers reserve/release commands and owns conflict retry (Task 7); a
`SimpleSaga` builds `MAX_ORDER_LINES` predicate-guarded step pairs (Task 8). `InventoryService`
loses its order-wide path and gains the width guard and terminal metrics (Task 6, 9). Operational
config bounds the new tables (Task 10), integration proves the whole loop (Task 11), and the
variant is registered on `main` (Task 12) before a gate run (Task 13).

**Tech Stack:** Kotlin 2.3.0, Spring Boot 4.0.6, JVM 21, Spring Modulith 2.0.0, Spring Data JDBC,
PostgreSQL, Flyway, Micrometer, JUnit 5, MockK, Gradle (Kotlin DSL). Eventuate Tram Sagas 0.25.0 +
Tram Core 0.36.0 + Common 0.20.0. Jackson 3 (`tools.jackson`) for Modulith/HTTP; Jackson 2
(`com.fasterxml.jackson`) for Eventuate's `JSonMapper` only.

**Spec:** `docs/superpowers/specs/2026-09-01-to-3-eventuate-saga-design.md`

## Global Constraints

- **Never push.** Commit locally only; the user pushes.
- **Branch:** source changes land on `TO-3-Eventuate` (worktree `.worktrees/TO-3-Eventuate`).
  Harness changes (`variants.env`, `docker-compose.yml`) land on `main` — the branch's copies of
  `k6/`, `scripts/` and `bench.env` are dead and must not be edited.
- **Migrations start at V9.** TO-3 stops at V7; V8 is TO-3-Saga's `order_saga`.
- **Metric names are contract.** `state_load_time{source,aggregate}`,
  `state_persist_time{source,outcome}`, `outbox.write.time`, `order.e2e.time{outcome}`,
  `order.processing.time`, `order.queue.wait`, `order.retry.backoff.time{outcome}`,
  `orders.completed{outcome,reason}`, `inventory.optimistic.retry`,
  `inventory.optimistic.exhausted`, `inventory.append.success`, `inventory.exception{type}`,
  `publish.lag{eventType}`, `outbox.purged` keep their exact names and tags. Where a sample changes
  granularity (per line, not per order), the KDoc says so, as TO-3-Saga's does.
- **`MAX_ORDER_LINES = 16`** is a compile-time constant. A wider order is rejected at accept with
  422 and never reaches the saga.
- **No saga lock.** No handler declares `withPreLock`/`withPostLock`; no command carries a
  resource. `saga_lock_table`, `saga_stash_table`, `saga_instance_participants` stay empty, asserted.
- **No `@Import` of Eventuate `@Configuration` classes.**
- **Every Eventuate coordinate is `isTransitive = false`.**
- **Test command:** `JAVA_HOME=~/.jdks/corretto-21.0.10 ./gradlew test` from the worktree root.
- **Must stay green:** `OrderWorkerPoolAutoConfigurationTest`, `OrderRetrySchedulerWiringTest`,
  `OrderRetryJitterTest`, `OutboxPurgerTest`, `InventoryBatchWriterTest`,
  `InventoryItemBenchKnobsTest`, `ApplicationTest`. Tests that die with the code they cover are
  replaced, not silently deleted; each task names its replacement.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `build.gradle.kts` | Eventuate + Jackson 2 dependency wall, all non-transitive | 1 |
| `src/test/.../eventuate/ClasspathHygieneTest.kt` | no Spring 5 / Boot 2 / broker jars leaked in | 1 |
| `src/main/resources/db/migration/V9__eventuate_saga.sql` | Eventuate's five tables + autovacuum | 2 |
| `src/main/.../eventuate/TramMessageEvent.kt` | the wrapper event Modulith persists | 3 |
| `src/main/.../eventuate/ModulithMessageProducer.kt` | `MessageProducer` → `publishEvent`; assigns `Message.ID` | 3 |
| `src/main/.../eventuate/ModulithMessageConsumer.kt` | `MessageConsumer` + the one listener + dedup | 4 |
| `src/main/.../config/EventuateSagaConfiguration.kt` | every Eventuate bean, constructed directly | 5 |
| `src/main/.../domain/{InventoryItem,events}.kt` | `release()`, release event, line coordinates | 6 |
| `src/main/.../repository/InventoryBatchWriter.kt` | `deleteReservation` | 6 |
| `src/main/.../service/command/SagaStepWriter.kt` | the per-line write transaction | 6 |
| `src/main/.../exception/exception.kt`, `controller/GlobalExceptionHandler.kt` | `OrderTooWideException` → 422 | 6 |
| `src/main/.../saga/InventoryLineCommands.kt` | reserve/release commands, failure reply | 7 |
| `src/main/.../saga/InventoryLineParticipant.kt` | `CommandHandlers` + in-handler conflict retry | 7 |
| `src/main/.../saga/{OrderSagaData,OrderReservationSaga}.kt` | saga data + the `MAX_ORDER_LINES` loop | 8 |
| `src/main/.../service/InventoryService.kt` | accept guard, saga creation, terminal metrics | 6, 9 |
| `src/main/.../service/command/CompleteOrderCommandHandler.kt` | order → CONFIRMED + event | 9 |
| `src/main/resources/{application.yaml,logback.xml}` | log levels, purge knobs | 10 |
| `src/main/.../publisher/SagaTablePurger.kt` | purges ended sagas and old `received_messages` | 10 |
| `variants.env`, `docker-compose.yml` (on `main`) | registers the variant | 12 |

Deleted with their tests: `OrderWriteCommandHandler.kt`, `ReserveOrderItemsCommandHandler.kt`,
`ReserveOrderItemsTransactionBoundaryTest`, `ReserveOrderItemsPhaseTest`,
`OrderWritePersistTimingTest`, `StateLoadAggregateTagTest`, `InventoryServiceRetryTest`,
`OrderRetryPoolTopologyTest`, `OrderRetryUnblocksWorkerTest`.

## Task 1: Build the dependency wall

The design rests on Eventuate loading without dragging Spring 5 / Boot 2 in, and on Jackson 2
coexisting with Jackson 3 without displacing Modulith's serializer. Both are classpath facts, so
they get proved before any saga code exists.

**Files:** modify `build.gradle.kts`; create `ClasspathHygieneTest.kt`
**Interfaces:** produces the runtime classpath; consumes nothing.

- [ ] **Step 1: Write the failing tests** — reflection only, no Spring context. Eventuate saga
      classes load; `JSonMapper.objectMapper` is non-null (forces `<clinit>`, which calls
      `Jdk8Module.configureAbsentsAsNulls`, deprecated since 2.6 — a removal surfaces here rather
      than at 300 rps); `SpringVersion` major is 7; `SagaOrchestratorConfiguration`,
      `TramConsumerJdbcAutoConfiguration` and `TramCommandProducerConfiguration` all throw
      `ClassNotFoundException`.
- [ ] **Step 2: Run the tests to verify they fail** — no Eventuate on the classpath yet.
- [ ] **Step 3: Write the implementation** — the 13 Eventuate coordinates, each
      `implementation(it) { isTransitive = false }`, plus `jackson-databind`,
      `jackson-datatype-jdk8` and `jackson-module-kotlin` at 2.21.2. Comments record why
      non-transitive (the published POMs pull `spring-jdbc:5.1.8.RELEASE` and
      `spring-boot-starter:2.7.14`) and why Jackson 2 does not displace Jackson 3 (Modulith's
      Jackson 3 config carries `@AutoConfigureBefore` the Jackson 2 one plus
      `@ConditionalOnMissingBean(EventSerializer)`).
- [ ] **Step 4: Run the tests to verify they pass**
- [ ] **Step 5: Audit the resolved classpath once by hand** —
      `./gradlew dependencies --configuration runtimeClasspath | grep -E 'spring-jdbc|spring-boot-starter|jackson|eventuate'`.
      Expect `spring-jdbc` 7.x only, no `spring-boot-starter:2.7.14`, `jackson-databind` at both
      2.21.2 and 3.x, every eventuate line childless.
- [ ] **Step 6: Commit** — `deps: Eventuate Tram Sagas 0.25 on a non-transitive classpath`

## Task 2: `V9__eventuate_saga.sql`

**Files:** create the migration; test asserts the five relations exist and `saga_data_json` is `text`.
**Interfaces:** consumed by `SagaInstanceRepositoryJdbc`, `SagaLockManagerImpl`,
`SqlTableBasedDuplicateMessageDetector` — column names come from their disassembled SQL and must
match exactly.

- [ ] **Step 1: Write the failing test**
- [ ] **Step 2: Run to verify it fails** — relations do not exist
- [ ] **Step 3: Write the migration** — `saga_instance` (`saga_data_json TEXT`),
      `saga_instance_participants`, `saga_lock_table`, `saga_stash_table`, `received_messages`
      (`creation_time BIGINT`, epoch millis, because the detector writes
      `(ROUND(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000))`). Absolute autovacuum thresholds on
      `saga_instance` and `received_messages`, same argument as V7. Partial index on unfinished
      sagas. Comments record all three divergences from upstream.
- [ ] **Step 4: Run the test to verify it passes**
- [ ] **Step 5: Confirm Flyway ordering** — V1, V2, V6, V7, V9; note in the commit that V8 is TO-3-Saga's
- [ ] **Step 6: Commit** — `db: V9 Eventuate saga schema, saga_data_json widened to TEXT`

## Task 3: `TramMessageEvent` + `ModulithMessageProducer`

**Interfaces:** produces the `MessageProducer` bean. **Contract:** `send` MUST set `Message.ID`
before returning — `CommandProducerImpl.send` calls `message.getId()` immediately after, and
`MessageImpl.getId()` throws on a missing header.

- [ ] **Step 1: Write the failing tests** — `send` assigns `ID` before publishing; destination and
      payload survive verbatim; `send` outside a transaction throws (a `publishEvent` with no
      active transaction skips the outbox row, so the command is delivered but never durable);
      and a `@SpringBootTest` slice asserting the `EventSerializer` bean is Modulith's **Jackson 3**
      one and that it round-trips a `TramMessageEvent`.
- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Write the implementation**
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Eyeball one `serialized_event`** — a single JSON object with
      `destination`/`headers`/`payload`, not a Jackson 2 rendering
- [ ] **Step 6: Commit**

## Task 4: `ModulithMessageConsumer` + duplicate detection

**Interfaces:** produces the `MessageConsumer` bean; consumes `DuplicateMessageDetector`.

- [ ] **Step 1: Write the failing tests** — routing by destination; two matching subscriptions both
      fire; the reconstructed `MessageImpl` has a **mutable** headers map (`CommandDispatcher`
      calls `setHeader`); a duplicate `(subscriberId, messageId)` invokes the handler zero times;
      `unsubscribe` stops delivery; an unknown destination WARNs and returns rather than throwing
      (a throw would strand the publication and republish forever).
- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Write the implementation** — ONE `@ApplicationModuleListener` for every channel,
      because Modulith writes a row per (event, listener) pair and a Tram message has exactly one
      destination. KDoc records that `REQUIRES_NEW` is the transaction Eventuate expects and never
      opens itself: there is no `@Transactional` anywhere in the saga jars.
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Confirm the listener id is stable** — an unstable `event_publication.listener_id`
      would orphan in-flight publications across a deploy
- [ ] **Step 6: Commit**

## Task 5: `EventuateSagaConfiguration`

**Interfaces:** produces `EventuateSchema` (EMPTY — the default `"eventuate"` would qualify tables
away from `public`), `EventuateSpringJdbcStatementExecutor` (the transaction-aware one; the common
variant takes a raw `Supplier<Connection>` and runs outside Spring's transaction),
`TransactionTemplate` (REQUIRED, so the detector joins rather than suspends),
`EventuateSpringTransactionTemplate`, `DefaultCommandNameMapping`, `CommandProducerImpl`,
`CommandReplyProducer`, `SagaLockManagerImpl`, `SqlTableBasedDuplicateMessageDetector`,
`SagaInstanceRepositoryJdbc`, `SagaCommandProducerImpl`, `SagaManagerFactory`,
`SagaInstanceFactory`, `SagaCommandDispatcherFactory`.

- [ ] **Step 1: Write the failing test** — each bean exists and is the expected type;
      `schema.qualifyTable("saga_instance") == "saga_instance"`; the `TransactionTemplate`
      propagation is REQUIRED; `JSonMapper` round-trips a Kotlin data class (fails without the
      Kotlin module, which is the whole reason it is registered).
- [ ] **Step 2: Run to verify it fails**
- [ ] **Step 3: Write the implementation** — the constructor first registers `KotlinModule` onto
      `JSonMapper.objectMapper` (a static field with `Int128Module` and `Jdk8Module` and nothing
      else), then the beans, each with a line saying why it is constructed rather than imported.
- [ ] **Step 4: Run to verify it passes**
- [ ] **Step 5: Start against real PostgreSQL** — watch for `ApplicationIdGenerator`, which derives
      a machine id and has historically probed network interfaces; swap a UUID-backed generator if
      it warns
- [ ] **Step 6: Commit**

## Task 6: Per-line domain, the write transaction, the accept guard

Ports TO-3-Saga's domain half, which is already correct and already argued in its KDoc.

**Interfaces:** `SagaStepWriter.writeReserve/writeRelease`, both `@Transactional(REQUIRED)`.
**Difference from TO-3-Saga:** neither takes a cursor claim, because Eventuate owns the cursor
(`saga_instance.state_name`) and the duplicate detector owns idempotency — so both return `Unit`,
not `Boolean`. That is the clearest single expression of what the framework replaced; say so in
the KDoc. `MAX_ORDER_LINES` lives on a small `OrderLimits` object so Task 6 does not depend on Task 8.

- [ ] **Step 1: Write the failing tests** — statement order is outbox → reservations → versioned
      UPDATE; a `ConcurrencyFailureException` is timed on `state_persist_time{outcome="conflict"}`
      and rethrown; 16 lines accepted, 17 throws `OrderTooWideException` → 422 and **nothing is
      written** (`orderRepo.save` and `publishEvent` never called).
- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Write the implementation**
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Run the full suite** — the four deleted tests gone, `InventoryBatchWriterTest` green
- [ ] **Step 6: Commit**

## Task 7: The participant

**Interfaces:** channel `inventoryService`; `SagaCommandHandlersBuilder.fromChannel(...).onMessage(...)`;
replies via `CommandHandlerReplyBuilder.withSuccess()` / `withFailure(ReserveLineFailure)`.
Registered by `@Bean fun inventoryLineDispatcher(f, p) = f.make("inventoryLineDispatcher", p.commandHandlers())`
— `make` calls `initialize()`, which subscribes. **No `withPreLock`, no `withPostLock`, no resource.**

Conflict retry lives here, blocking on the order-worker thread, reusing `OrderRetryPolicy` unchanged.

- [ ] **Step 1: Write the failing tests** — success replies `withSuccess`; `InsufficientStockException`
      replies `withFailure(code="insufficient_stock")` with **no** retry (not a conflict); a conflict
      retries to `MAX_RETRIES` then replies `withFailure(code="optimistic_exhausted")`;
      `inventory.optimistic.retry` increments on every failed attempt including the last (TO-3
      counts it that way and the pair is only comparable if this is identical);
      `order.retry.backoff.time` records the accumulated jittered delay; channels are exactly
      `{inventoryService}`; **no handler carries a pre- or post-lock**.
- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Write the implementation** — KDoc states plainly that the retry blocks its
      order-worker thread for the whole backoff where TO-3 parks the task in a `DelayedWorkQueue`,
      that this is a second variable against TO-3, and that it must be named in every comparison.
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Wiring check** — `ModulithMessageConsumer` holds a subscription for
      `{"inventoryService"}` after context refresh
- [ ] **Step 6: Commit**

## Task 8: The orchestrator

**Interfaces:** `OrderSagaData(orderId, userId, correlationId, startedAtEpochMs, lines, failureCode,
failureReason)` — `Long`, not `Instant`, because `JSonMapper` registers no `JavaTimeModule`.
`OrderReservationSaga : SimpleSaga<OrderSagaData>` overriding `getSagaType()` to the literal
`"OrderReservationSaga"`.

Two bytecode-level traps the loop must respect: `SimpleSagaDsl.step()` creates a **fresh**
`SimpleSagaDefinitionBuilder` on every call, so the loop chains via
`InvokeParticipantStepBuilder.step()`; and both `.step()` and `.build()` call `addStep()`, so the
last iteration calls `build()` **without** a preceding `.step()` or the final step is added twice.

Terminal callbacks fire inside `performEndStateActions` → `processActions`, inside the reply
listener's transaction, so `CompleteOrderCommandHandler`/`FailOrderCommandHandler` (both REQUIRED)
join it — terminal state, terminal event and the final `saga_instance` UPDATE are one commit.

- [ ] **Step 1: Write the failing tests** — exactly `MAX_ORDER_LINES` steps, not `MAX_ORDER_LINES + 1`
      (the double-`addStep` trap); a 1-line order emits exactly one command; a 4-line happy path
      walks 4 steps and reaches `endState` without touching 4..15; a failure at line 2 compensates
      1 and 0 in reverse and skips 3..15; `getSagaType()` is the literal; **`resource` is null on
      every command**; a 17-line `OrderSagaData` throws before `start` returns.
- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Write the implementation**
- [ ] **Step 4: Run to verify they pass**
- [ ] **Step 5: Check `saga_data_json` size at the ceiling** — serialise a 16-line `OrderSagaData`
      with a 200-char failure reason; this is the number that justifies TEXT in V9
- [ ] **Step 6: Commit**

## Task 9: `InventoryService` and terminal metrics

**Interfaces:** `acceptOrder` unchanged in signature, plus the width guard ahead of everything.
`onOrderCreated` records `order.queue.wait` then calls `sagaInstanceFactory.create(...)` **inline** —
it must NOT hop to `orderWorkerExecutor`, because the hop leaves the listener's transaction and
`saga_instance` + the first command would stop committing atomically. The listener is already
`@Async` on the order pool, so the hop bought nothing.

`submit`/`runOrderTask`/`scheduleRetry`/`rejectOrder`/`OrderAttempt` are removed;
`OrderRetryScheduler`/`OrderWorkerPool` stay (the pool is still the `@Async` executor) and
`OrderWorkerConfig` logs at startup that `schedule()` is now unused on this branch.

`order.e2e.time{outcome}` = now − `startedAtEpochMs` from the saga data, matching how TO-3-Saga
measures from `order_saga.started_at`, so the two saga branches are measured identically.

- [ ] **Step 1: Write the failing tests** — accept publishes `OrderCreatedEvent` and nothing else;
      `onOrderCreated` calls `create` exactly once with the right lines and no executor hop;
      `onSagaCompletedSuccessfully` confirms and records `orders.completed{outcome="confirmed",reason="none"}`;
      `onSagaRolledBack` rejects with the saga's `failureCode`; a 17-line order never reaches `create`.
- [ ] **Step 2–4: Run, implement, re-run**
- [ ] **Step 5: `./gradlew test`** — whole suite green
- [ ] **Step 6: Commit**

## Task 10: Operational configuration

Three things that would otherwise appear as unexplained CPU or disk in the run.

- [ ] **Step 1–4:** TDD `SagaTablePurger` against `OutboxPurgerTest`'s shape — own daemon thread,
      batch size, max batches, min age, a counter. `received_messages` grows `2N` rows per order
      (8 M/hour at 300 orders/s, `N=4`) and nothing else deletes it; ended `saga_instance` rows
      likewise.
- [ ] **Step 5:** pin `io.eventuate.tram.sagas.orchestration` to WARN in `logback.xml`
      (`SagaManagerImpl.handleReply` logs at INFO on every reply — `2N` lines per order), and raise
      `app.outbox-purge.max-batches`: this branch writes ~`3N+3` outbox rows per order against
      TO-3's `N+3`, and the shipped ceiling of 2000 × 10 / 5 s = 4000 rows/s is exceeded at
      300 orders/s × 15. Then run 60 s of load and confirm both row counts plateau.
- [ ] **Step 6: Commit**

## Task 11: End-to-end integration

Against real PostgreSQL, app running.

- [ ] **Step 1:** happy path — 4-line order CONFIRMED; 4 `reservations` rows; each item's
      `available_qty` down by its quantity; one `saga_instance` row `end_state=true, failed=false`
- [ ] **Step 2:** compensation — insufficient stock at line 2; order REJECTED with the reason from
      `ReserveLineFailure`; lines 0 and 1 released (stock restored, rows deleted);
      `compensating=true, end_state=true`
- [ ] **Step 3: the lock assertion** — `saga_lock_table`, `saga_stash_table` and
      `saga_instance_participants` all 0 after both scenarios. This is what keeps the biggest
      throughput risk from creeping back in.
- [ ] **Step 4:** width guard — a 17-line POST returns 422 and writes no `orders` row
- [ ] **Step 5:** idempotency — re-publish a captured `TramMessageEvent` with the same
      `Message.ID`; stock moves once, one `received_messages` row for that pair
- [ ] **Step 6: Commit**

## Task 12: Register the variant on `main`

Harness files live on `main` and the branch's copies are not read.

- [ ] **Step 1:** add the row after `TO-3-Saga`:
      `TO-3-Eventuate  TO-3-Eventuate  TO  reserve-delay,payload-bytes`
- [ ] **Step 2:** add the prose block in the style of `variants.env:577-621` — what a run costs
      that TO-3's does not, which metrics change meaning, the pool budget, and above all that this
      arm is **single-variable against TO-3-Saga** (framework vs hand-written engine) but
      **two-variable against TO-3** (per-line unit of work *and* blocking retry backoff). §5 of the
      runbook must not read the three TO-3 arms as three independent samples.
- [ ] **Step 3:** name the new knobs bare (no `:-default`) under `api.environment` in
      `docker-compose.yml`, so each arm keeps its shipped value: `SAGA_PURGE_ENABLED`,
      `SAGA_PURGE_MAX_BATCHES`
- [ ] **Step 4:** `grep -n 'TO-3-Eventuate' variants.env && ./scripts/run-suite.sh --help`
- [ ] **Step 5:** `scripts/run-tests.sh` green
- [ ] **Step 6: Commit on main** — `bench: register TO-3-Eventuate, the framework-saga A/B for TO-3-Saga`

## Task 13: Run the gate

Not a code task. Decides whether the branch produces a usable campaign result or needs the
non-blocking-retry escape hatch.

- [ ] **Step 1:** `./scripts/build-images.sh --only TO-3-Eventuate`
- [ ] **Step 2:** `POINT=W-base scripts/run-suite.sh --only TO-3-Eventuate`
- [ ] **Step 3:** confirm the arm actually ran — the `EventuateSagaConfiguration` startup line is
      present and no lock was claimed
- [ ] **Step 4: Evaluate**

| Check | Source | Threshold |
|---|---|---|
| Run is valid | `verdict.json` | not `INVALID`; `scrape_up` = 1 |
| A knee exists | `evaluate.py` `knee` | present, not at step 0 or the last step |
| Outbox is bounded | `outbox_backlog` | not monotonically growing |
| `received_messages` is bounded | row count at end of run | plateaus, does not track total orders |
| Pool not parked in backoff | `order.retry.backoff.time` p95 × conflict rate vs `ORDER_WORKER_THREADS` | occupied threads < pool width |
| Stock is consistent | `dump.py` reconciliation | `sum(available_qty) + sum(reservations.quantity)` = seeded total |

- [ ] **Step 5:** bracket `W-hot` and `W-fan` separately, expecting a much lower `STEP_INC` than
      TO-3's. At `W-fan` the branch runs 33 async hops and ~51 outbox rows per order; if 30 steps
      do not reach a knee, halve `STEP_START` per runbook §2.1 rather than calling the run failed.
- [ ] **Step 6: Decide**
  - **All six pass** → register it in the runbook's phase-1 block.
  - **Only "parked in backoff" fails** → implement the non-blocking retry: instead of
    `Thread.sleep`, re-publish the same `TramMessageEvent` with a **fresh** `Message.ID` after
    `OrderRetryPolicy.delayMsFor(attempt)` via `OrderWorkerPool.schedule`, and return no reply.
    Restores TO-3's queue topology across the framework boundary and removes the second variable,
    at one extra outbox row per retried line. Contained to `InventoryLineParticipant`.
  - **Outbox or `received_messages` unbounded** → the Task 10 purge budget is wrong; set it from
    the measured rows/order in `dump.json`.

## Notes for the executor

- The biggest single risk is Task 13's backoff check, not any compile-time concern. Read the spec's
  Risks section before starting Task 7 so the escape hatch is in mind while writing it.
- `TO-3-Saga` is the reference implementation for Tasks 6 and 9. Read
  `git show TO-3-Saga:src/main/kotlin/pl/szymanski/wiktor/service/command/SagaStepWriter.kt`
  before writing ours; the statement-order argument in its KDoc carries over unchanged.
