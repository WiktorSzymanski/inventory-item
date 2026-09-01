# TO-3-Eventuate: a per-line saga run by a framework, not by hand

**Date:** 2026-09-01
**Status:** approved, not yet implemented
**Branch:** `TO-3-Eventuate`, cut from `TO-3` (`61e2d13`)

## Goal

Price a **general-purpose saga framework** against the two shapes the campaign already has:
TO-3's one-transaction-per-order, and TO-3-Saga's one-transaction-per-line driven by a
hand-written state machine.

## Current state, measured

`TO-3` does not run "one transaction that books every item" in the naive sense. Since `58c4cd0`
it runs a three-phase split — read (no tx), modify in memory (no tx, no connection, no lock),
then one short `OrderWriteCommandHandler.write` holding four statement groups. The exclusive row
locks on `inventory_state` are taken by the last statement and held only to COMMIT.
`ReserveOrderItemsTransactionBoundaryTest` exists specifically to stop anyone re-adding
`@Transactional` to the entry point.

`TO-3-Saga` (`6ca04fb`, one commit off TO-3) replaces that with per-line transactions: an
`order_saga` row whose `current_index` is moved by guarded single UPDATEs, woken by the Spring
Modulith outbox — `InventoryReservedEvent` both notifies the mock-Kafka listener and advances the
saga to line k+1. One outbox row, two jobs.

What neither branch answers: **how much of the per-line cost is the pattern, and how much is that
particular hand-rolled implementation?** TO-3-Saga's state machine is ~170 lines of bespoke SQL
predicates. A framework brings its own state table, its own step/compensation DSL, its own
command/reply protocol, and its own duplicate detection — and its own overhead. That is the
missing cell.

## Design

Eventuate Tram Sagas 0.25.0 (+ Tram Core 0.36.0, Common 0.20.0) orchestrates the reservation.
Per-line transactions, compensation, and retry curve are TO-3-Saga's; only the engine changes.

### The transport decision

Eventuate Tram normally requires a **broker plus the Eventuate CDC service**: its producer writes
to a `message` table and CDC tails that table and republishes to Kafka/ActiveMQ/RabbitMQ/Redis.
There is no JDBC polling consumer — `eventuate-tram-consumer-jdbc` contains only duplicate
detectors (`TransactionalNoopDuplicateMessageDetector`, `SqlTableBasedDuplicateMessageDetector`).

We use none of it. **Eventuate's messages ride Spring Modulith's event registry.** The SPI is
three methods:

```java
public interface MessageProducer { void send(String destination, Message message); }
public interface MessageConsumer {
  MessageSubscription subscribe(String subscriberId, Set<String> channels, MessageHandler handler);
  String getId();  void close();
}
public interface MessageHandler extends Consumer<Message> {}
```

`Message` is a `Map<String,String>` of headers plus a `String` payload.

- `ModulithMessageProducer` wraps the message in a `TramMessageEvent` and calls
  `ApplicationEventPublisher.publishEvent`, so Modulith writes the `event_publication` row inside
  the caller's transaction — the same outbox guarantee Eventuate wanted from `message`.
- `ModulithMessageConsumer` records subscriptions; one `@ApplicationModuleListener` routes each
  delivered message to the handler whose channel set contains the destination.

This buys three properties that make the comparison single-variable:

| | effect |
|---|---|
| **One outbox** | commands, replies and domain events all land in `event_publication` — same `OutboxPurger`, `outbox.backlog`, `IncompleteEventRepublisher`, V7 autovacuum tuning |
| **Same pool** | `@ApplicationModuleListener` is `@Async` and `OrderWorkerPool` is the only `Executor` bean, so saga steps run on the same order pool |
| **One variable vs TO-3-Saga** | identical outbox, pool and per-line transaction shape; only the engine differs |

Ordering is not a concern: a saga instance is a strict request/reply ping-pong with one command
outstanding at a time, and stale replies are rejected via `saga_instance.last_request_id`.

### Do not `@Import` Eventuate's Spring configuration

`SagaOrchestratorConfiguration` carries
`@Import({TramCommandProducerConfiguration, EventuateTramSagaCommonConfiguration})` and
`SagaParticipantConfiguration` carries
`@Import({EventuateTramSagaCommonConfiguration, TramCommandReplyProducerConfiguration})`.
`@Import` values resolve at configuration-parse time, so importing either drags in
`eventuate-tram-spring-commands` and `eventuate-tram-sagas-spring-common`, and
`TramCommandProducerConfiguration` in turn pulls the JDBC outbox producer chain — the `message`
table we just removed, plus `spring-jdbc:5.1.8.RELEASE` and `spring-boot-starter:2.7.14`.

Every bean those configs make is a public constructor call. We construct them ourselves:
`SagaInstanceRepositoryJdbc`, `SagaCommandProducerImpl`, `SagaManagerFactory`,
`SagaInstanceFactory`, `SagaCommandDispatcherFactory`.

### `Message.ID` is ours to assign

`CommandProducerImpl.send` calls `message.getId()` immediately after `MessageProducer.send`
returns void, and `MessageImpl.getId()` is `getRequiredHeader("ID")`, which throws when absent.
`CommandMessageFactory` sets only `command__destination`, `command_type`, `command_reply_to`,
`command_resource` — **nothing in the chain assigns `ID`**; in the reference stack that is the
JDBC producer's job. `ModulithMessageProducer.send` must set it before returning, or the app
starts cleanly and dies on the first order.

### No saga lock, ever

Locking is opt-in: `SagaCommandDispatcher.invoke` calls `SagaLockManager.claimLock` only when the
handler declares `withPreLock`/`withPostLock`, and `saga_instance_participants` is populated only
from the `saga-locked-target` header that a claimed lock sets.

Declaring a lock on `itemId` would take a row-level exclusive lock held for the whole participant
handler — item read, `reserveDelayMs` sleep, the four-statement write, *and* the conflict-retry
backoff — and released only at saga end state. An order would hold line 0's item until the whole
order finished. At `W-hot` (`DISTINCT_ITEMS=8`) that is an 8-way serialisation of the benchmark,
and everywhere else it silently swaps optimistic concurrency for pessimistic. `TO-3-pessimistic`
already answers that question and is retired.

So: no handler declares a lock, no command carries a resource. `saga_lock_table`,
`saga_stash_table` and `saga_instance_participants` are created and asserted empty by a test —
intent checked, not assumed.

### Duplicate detection is required, not optional

`IncompleteEventRepublisher` resubmits anything incomplete for `republication-min-age`, so the
transport is at-least-once by design. The dangerous window is real: the participant's
`REQUIRES_NEW` transaction commits (stock decremented, reply row inserted) and Modulith then marks
the inbound publication complete as a *separate* operation. A crash between the two redelivers the
reserve command — a second decrement of the same line, silent stock corruption.

The domain cannot cover it cheaply: `reservations` PK is `(item_id, reservation_id)` with
`reservation_id == orderId`, so a redelivered reserve of a *different* line on a *different* item
inserts cleanly and double-decrements. `SqlTableBasedDuplicateMessageDetector` + `received_messages`
is the framework's own answer, and paying for it is the honest thing for a "what does Eventuate
cost" measurement. Cost: one INSERT per Tram message, `2N` per order, plus a purger.

## Decisions taken

- **Conflict retry in the participant.** Reuse `OrderRetryPolicy` (4 retries, 25/50/100/200 ms,
  `JITTER_RATIO = 0.5`) with a blocking backoff. Eventuate has no per-step retry; letting a
  conflict become a failure reply would collapse the confirmed/rejected split. **This is a second
  variable against TO-3** — TO-3 parks the backoff in a `DelayedWorkQueue` at zero thread cost —
  and must be named in every comparison. Escape hatch in Risks.
- **`MAX_ORDER_LINES = 16`**, a compile-time constant. The campaign ceiling is `W-fan`'s 16;
  `ITEMS_PER_ORDER` is an identity knob (`scripts/lib.sh:247`) so nothing wider can arrive by
  environment. A wider order is rejected at accept with 422 and never reaches the saga —
  under-provisioning would otherwise mark an order CONFIRMED with its last lines never reserved.
- **Static DSL, predicate-guarded.** `SimpleSagaDsl` fixes its step list at construction, so build
  `MAX_ORDER_LINES` step pairs in a loop, each guarded by `data.lines.size > i`. Skipped steps
  resolve in memory in `SagaExecutionState.nextState(skipCount)` — no message, no row.
- **`getSagaType()` overridden to a literal.** The default is `getClass().getName()`, so a CGLIB
  proxy would produce a `$$SpringCGLIB$$` saga type and orphan every in-flight saga across a
  restart. Corollary: no AOP annotation on the saga bean.
- **Jackson.** Boot 4.0.6 manages both generations (`jackson-2-bom.version = 2.21.2`,
  `jackson-bom.version = 3.1.2`), so Eventuate's transitive `jackson-databind:2.13.4` is upgraded
  and coexists with the branch's Jackson 3. But `JSonMapper` holds a *static* Jackson 2
  `ObjectMapper` with no Kotlin module and no `JavaTimeModule` — register `KotlinModule` onto it,
  and keep `Instant` out of saga data and commands (`startedAtEpochMs: Long`, `correlationId: String`).
- **`EventuateSchema.EMPTY_SCHEMA`.** The default is `"eventuate"` and `qualifyTable` formats
  `"%s.%s"`, so our Flyway-created `public.saga_instance` would never be found.
- **Migrations start at V9.** V8 is TO-3-Saga's `order_saga`; the TO family keeps numbering
  monotonic across branches so a file name never collides with a different migration on a sibling.
  `saga_instance.saga_data_json` is `TEXT`, not upstream's `VARCHAR(1000)`.

## Risks and costs

- **The order pool parks in backoff.** `docker-compose.yml` pins `ORDER_WORKER_THREADS=50` for
  every TO branch, and `OrderWorkerPool` is the only `Executor` bean, so those 50 threads serve the
  accept listener, every command delivery, every reply delivery and every mock-Kafka publish — ~14
  async tasks per order at `N=4`. A blocking `Thread.sleep(25..300ms)` in the reserve participant
  is exactly the failure `OrderRetryScheduler`'s own KDoc warns about. Most likely to make `W-hot`
  unmeasurable. **Escape hatch:** re-publish the `TramMessageEvent` with a fresh `Message.ID` after
  `OrderRetryPolicy.delayMsFor(attempt)` via `OrderWorkerPool.schedule` and return no reply —
  restores TO-3's queue topology across the framework boundary, at one extra outbox row per retried
  line. Contained to `InventoryLineParticipant`.
- **Volume.** Per order at `N` lines: `2N+1` async hops, `2N+1` `REQUIRES_NEW` transactions,
  `3N+3` `event_publication` rows, `2N` `received_messages` inserts, one `saga_instance` INSERT and
  `2N+1` UPDATEs — against TO-3's one transaction and `N+3` outbox rows. At `W-fan` that is 33 hops
  and ~51 outbox rows per order. The shipped purge ceiling (2000 × 10 / 5s = 4000 rows/s) is
  exceeded at 300 orders/s × 15; raise `max-batches`.
- **Stall-on-failure latency.** A failed listener is not retried until `IncompleteEventRepublisher`'s
  next sweep — `PT30S` cadence, `PT1M` min-age — so one transient failure stalls a saga for ≥60 s,
  with `2N+1` chances per order. Expect a long flat e2e tail that is *not* queueing. Do not tune
  `republication-min-age` down to hide it; TO-3 does not have that change.
- **Log volume.** `SagaManagerImpl.handleReply` logs at INFO on every reply — `2N` lines per order,
  800/s at 100 orders/s and `N=4`. Pin `io.eventuate.tram.sagas.orchestration` to WARN.
- **`ApplicationIdGenerator`** derives a machine id at construction and has historically probed
  network interfaces. Because we construct `SagaInstanceRepositoryJdbc` ourselves, swapping a
  UUID-backed generator is a one-line change if it misbehaves in the container.

## Out of scope

- The Eventuate CDC service, any broker, and Eventuate's `message` table.
- Choreography-style sagas (Eventuate supports them; the comparison target is orchestration).
- Multi-node. The campaign is single-node throughout.

## Verification

- `./gradlew test` green on the branch; `scripts/run-tests.sh` green on `main`.
- End-to-end against real PostgreSQL: happy path confirms and ends the saga; a failure at line 2
  compensates lines 1 and 0 in reverse, restores stock, deletes the reservation rows and rejects
  the order; `saga_lock_table`, `saga_stash_table`, `saga_instance_participants` all empty; a
  17-line POST returns 422 and writes no `orders` row; a duplicate delivery moves stock once.
- One bench run at `W-base` clearing the six gate checks in the implementation plan.
