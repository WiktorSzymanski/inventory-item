# ES multi-node write safety: make command failure terminal

Date: 2026-08-03
Branches affected: `ES-1`, `ES-2`, `ES-3`, `ES-4`
Status: approved, not yet implemented

## Problem

At `REPLICAS>1` the ES branches strand orders in `PENDING` permanently. Measured on `ES-2`
at `REPLICAS=2`, `SCENARIO=steady RATE=30 DURATION=3m`, workload `distinct=6 lines/order=4`:
3537 of 10401 orders stuck, with the `saga_entry` count and the `EventStoreException` count
both also exactly 3537 — one failed command, one parked saga, one stuck order. Backlog flat
across three consecutive 30 s samples; zero pool timeouts.

Two independent defects produce that outcome.

**1. Conflicts are not classified as retryable (ES-1/2/3).** The only thing serialising
writers to an `InventoryItem` is a JVM-local `LockFactory`, so a second JVM removes it: two
replicas load the aggregate at sequence N and both append at N+1, leaving the
`(aggregate_identifier, sequence_number)` unique constraint as the backstop.
`AxonConfig.eventStorageEngine` builds `JdbcEventStorageEngine` with no
`persistenceExceptionResolver`, so `AbstractEventStorageEngine.handlePersistenceException`
raises a generic `EventStoreException` rather than `ConcurrencyException`.
`ConcurrencyRetryScheduler.kt:32-34` matches only `ConcurrencyException`, so it never
retries. `ES-4` already has `.persistenceExceptionResolver(SQLStateResolver())`
(`ES-4:AxonConfig.kt:152`); the fix was never backported.

**2. Command failure has no handler (all four branches).** Every
`commandGateway.send(...)` in `OrderReservationSaga` discards the returned future. When a
command ultimately fails, no event is appended, the association never fires again,
`SagaLifecycle.end()` is never reached, and the order stays `PENDING` forever. This is why
the outcome is permanent rather than slow, and why fixing (1) alone is insufficient —
exhausted retries reach the same dead end.

Defect 2 is not multi-node-specific. Any command failure — pool exhaustion, serialization
error — parks an order at `REPLICAS=1` too. The pool-sizing incident recorded in `CLAUDE.md`
(60-thread saga claim against a 150-connection pool, 48 orders stranded) was this shape.

## Goal

Fail-fast parity with the TO family. `InventoryService.processOrder` on TO is `@Retryable`
over 4 attempts and issues `FailOrderCommand` on exhaustion, so a lost race becomes a
`FAILED` order and never a stuck one. ES should degrade the same way: contention converts to
terminal rejections, observable as a rejection rate.

This deliberately does **not** attempt to make ES multi-node write-*correct*. Real
distributed concurrency control (Postgres advisory locks keyed on `aggregate_identifier`, or
command routing so one node owns each aggregate) would change what "ES" means mid-thesis and
risks introducing a new saturation mode that would itself need characterising. The
architectural limitation stands as a finding: the ES design as built has in-process
concurrency control, so horizontal write scale-out cannot be demonstrated without adding
coordination. What changes is that the limitation now shows up as rejections instead of a
hung backlog.

## Non-goals

- No change to `TO-*`.
- No change to anything under `k6/` or `docker-compose.bench.yml`. These are byte-identical
  across `ES-2`/`ES-4` and must stay that way (`git diff --stat ES-2 <branch> -- k6
  docker-compose.bench.yml` must remain empty).
- No aggregate-cache work. Verified unnecessary — see "Cache interaction" below.
- No `DeadlineManager` / saga-timeout safety net. It would catch stall causes beyond command
  failure, but the in-memory deadline manager is node-local and doesn't survive restart, and
  a persistent one is a new component.

## Scope

| Branch | `SQLStateResolver` | Saga failure path | `recordSagaEnd` backport |
|---|---|---|---|
| ES-1 | add | add | add (whole metrics block) |
| ES-2 | add | add | already present, extend |
| ES-3 | add | add | add (whole metrics block) |
| ES-4 | already present | add | add (whole metrics block) |

The saga file differs per branch — `ES-2`'s is ~36 lines longer than the others, that delta
being exactly the metrics block. This is four adapted edits, not a cherry-pick.

## Change 1 — classify conflicts as retryable

In `AxonConfig.eventStorageEngine` on ES-1/2/3, one builder call, matching `ES-4` exactly:

```kotlin
import org.axonframework.eventsourcing.eventstore.jpa.SQLStateResolver
// ...
val jdbc = JdbcEventStorageEngine.builder()
    .connectionProvider(DataSourceConnectionProvider(axonDataSource))
    .transactionManager(axonTransactionManager)
    .schema(eventSchema)
    .eventSerializer(eventSerializer)
    .snapshotSerializer(eventSerializer)
    .persistenceExceptionResolver(SQLStateResolver())
    .build()
```

`handlePersistenceException` then raises `ConcurrencyException` on a Postgres `23505`, which
is what `ConcurrencyRetryScheduler` matches: 5 attempts, `25ms * 2^n` capped at 500 ms.

The resolver is consulted inside `JdbcEventStorageEngine.appendEvents`, beneath the
`TimedEventStorageEngine` decorator, so the decorator sees an already-translated
`ConcurrencyException`. `TimedEventStorageEngine.kt:44-46` wraps the call in
`Timer.recordCallable`, which propagates exceptions unchanged. No masking.

This change alone converts some permanent stalls into successes and leaves the rest stalling
after exhaustion. Change 2 makes the remainder terminal.

## Change 2 — give every saga command a failure disposition

The defect is broader than the reserve command. Four `commandGateway.send` calls discard
their future, and three can strand an order.

| Call site | Today, on final failure | After |
|---|---|---|
| `SagaReserveItemCommand` (`:131`) | order `PENDING` forever, saga parked | release snapshot, `FailOrderCommand`; saga ends via `OrderFailedEvent` |
| `CompleteOrderCommand` (`:83`) | order `PENDING` forever (saga already ended) | release all lines, `FailOrderCommand` |
| `FailOrderCommand` (`:102`) | order `PENDING` forever (saga already ended) | ERROR log + `saga.command.failed{stage="fail-order"}` — residual, see Risks |
| `ReleaseReservationCommand` (`:99`) | stock silently leaks | ERROR log + `saga.command.failed{stage="release"}` |

Line numbers are `ES-2`; the same four call sites exist on every ES branch.

`runCatching` at `:98` is ineffective today for the same underlying reason — it catches only
synchronous dispatch errors, never the asynchronous command failure.

### Mechanism

`SagaLifecycle` resolves the current saga from a ThreadLocal bound to the saga processor's
unit of work. The `whenComplete` callback runs on a `sagaCommandExecutor` pool thread
(`CommandGatewayConfig.kt:33`), where that ThreadLocal is empty, so calling
`SagaLifecycle.end()` there throws `IllegalStateException: No current Saga`. The failure must
therefore re-enter saga scope via an event.

It re-enters through `OrderFailedEvent`, which already exists and requires no new event type.
Dispatch captures state on the saga thread; the callback reads only captures:

```kotlin
private fun sendNextReservation() {
    val item = items[currentIndex]
    val orderIdCopy = orderId
    val toRelease = reservedItems.toList()
    commandExecutor.execute {
        commandGateway.send<Any?>(SagaReserveItemCommand(item.itemId, item.quantity, correlationId))
            .whenComplete { _, ex -> if (ex != null) abandon(orderIdCopy, toRelease, "reserve", ex) }
    }
}

@EndSaga
@SagaEventHandler(associationProperty = "orderId")
fun on(event: OrderFailedEvent) = recordSagaEnd("command_failed")
```

`abandon(orderId, toRelease, stage, cause)` logs at ERROR, increments
`saga.command.failed{stage}`, sends a `ReleaseReservationCommand` per already-reserved line,
then sends `FailOrderCommand`. Each of those sends carries its own `whenComplete` counter.

The `CompleteOrderCommand` call site uses the same `abandon`, snapshotting `reservedItems`
at its own dispatch point in `on(InventoryReservedEvent)`. Because `reservedItems.add(...)`
runs before the completion branch is taken, that snapshot covers every line of the order,
including the last one — so the compensation released there is the whole order, not a
prefix of it.

### Where `OrderFailedEvent` is persisted

Nothing new persists it. `OrderAggregate.kt:36-40` already does, and Change 2 only adds a
second caller of `FailOrderCommand`. The command is routed to `OrderAggregate`, which is
event-sourced from the store, finds `status == PENDING`, and applies `OrderFailedEvent` —
appended to `domain_event_entry` under `aggregate_identifier = orderId`,
`sequence_number = 1`, inside that command's unit of work. Identical row shape and table to
an out-of-stock rejection; only `reason` differs.

The `order-saga` tracking processor then reads that event back and delivers it to the new
`@EndSaga` handler, which matches because `@StartSaga` on
`OrderCreatedEvent(associationProperty = "orderId")` already registered the `orderId`
association in `association_value_entry` at saga creation. That handler runs on the processor
thread, in saga scope, so `@EndSaga` is legal and deletes the `saga_entry` row and both
association rows. `order-projection` reads the same event and writes `REJECTED`.

### Why the off-thread work is safe

- **No state race.** Reservations are strictly sequential (`OrderReservationSaga.kt:78-87`),
  so exactly one command is in flight per saga at a time. The only thing that mutates
  `reservedItems` is the success handler for the very command that just failed, so the
  snapshot taken at dispatch cannot be mutated concurrently.
- **No contention on `OrderAggregate`.** Each order lives in one saga instance, in one
  segment, claimed by one node — one writer per `OrderAggregate` even at `REPLICAS>1`. This
  is why `FailOrderCommand` is a trustworthy escape hatch from a failure caused by contention
  on `InventoryItem`.
- **Idempotent.** `if (status != OrderStatus.PENDING) return` (`OrderAggregate.kt:37`) makes
  a duplicate `FailOrderCommand`, or one racing a `CompleteOrderCommand`, a no-op.
- **`whenComplete` is the final verdict.** `RetryingCallback` keeps the future incomplete
  across all 5 retry attempts, so the callback never fires on an intermediate failure.

### Two saga-termination paths coexist

The existing out-of-stock handler keeps its inline `SagaLifecycle.end()`. Its
`OrderFailedEvent` then arrives at an already-ended saga and is not routed — harmless, and
`recordSagaEnd` cannot double-count for the same reason. The alternative, deleting the inline
`end()` so both paths terminate via `OrderFailedEvent`, is more uniform but adds a processor
hop to every rejection and shifts `saga.lifetime` against existing `ES-2` baselines.
Rejected on those grounds.

### Cache interaction (verified, no work needed)

A retried command must see post-conflict state or it would just re-conflict until exhaustion.
Both caching branches already handle this:

- **ES-4** — `PessimisticCachingRepository` registers `uow.onRollback { catchUp(...) }`, which
  reads `eventStore.readEvents(id, seq + 1)` and advances the cache. Its documentation states
  this path exists specifically for the multi-node case, the lock being JVM-local.
- **ES-3** — stock `CachingEventSourcingRepository` over `StrongCache`; Axon evicts the entry
  on rollback, so the retry cold-replays.

ES-1 and ES-2 are uncached (`AxonCustomizerConfig.kt:54`) and always re-read the store.

## Change 3 — metrics

- Backport `recordSagaEnd`, the `meterRegistry` field, `createdAtMillis`, and the
  `@Timestamp` handler parameter verbatim from `ES-2` to `ES-1`/`ES-3`/`ES-4`.
- New outcome value `command_failed` on `saga.completed` and `saga.lifetime`, separating
  contention-driven rejections from genuine out-of-stock rejections.
- New counter `saga.command.failed` tagged `stage = reserve | complete | release | fail-order`,
  on all four branches.

`saga.lifetime` keeps the semantics documented at `OrderReservationSaga.kt:108-114` —
recorded where the saga's own lifecycle ends, deliberately earlier than the downstream
projection. The success path is therefore **not** restructured to end on
`OrderCompletedEvent`; that would be more symmetric with the failure path but would silently
redefine the metric against existing baselines.

Accepted inaccuracy: if `CompleteOrderCommand` fails, the saga has already recorded
`outcome="completed"`. `saga.command.failed{stage="complete"}` makes that visible. It is an
infrastructure-only path, since `OrderAggregate` is uncontended.

## Effect on existing baselines

Single-node behaviour should be unchanged. At `REPLICAS=1` a JVM-local
`PessimisticLockFactory` serialises writers to `InventoryItem`, so no `23505` occurs:
`SQLStateResolver` is a no-op and the `whenComplete` failure branch is unreachable. The only
single-node deltas are the new metric series on ES-1/3/4 and one extra event type reaching
the saga processor.

Existing single-node baselines therefore remain valid. This is treated as a documented
assumption rather than something proven upfront — the standing check is that
`saga.completed{outcome="command_failed"}` stays at zero on any `REPLICAS=1` run. This
supersedes the claim in `es_multinode_write_unsafe.md` that every ES baseline would need
re-running.

## Risks

**Residual stuck order.** If `FailOrderCommand` itself fails, the order remains `PENDING`.
There is no further escape hatch that does not recurse, so this is logged at ERROR and
counted, not handled. Expected to be ~zero except when the database is down, in which case
`evaluate.py` returns `INVALID` regardless.

**Stock leak.** A failed `ReleaseReservationCommand` leaks reserved quantity. This hole
exists today, unfixed; the change makes it countable rather than silent.

## Validation

**Unit** — stubbed `CommandGateway` returning an already-failed future: assert
`FailOrderCommand` is sent, and that `ReleaseReservationCommand` is sent for previously
reserved lines only, not for the line that failed.

**Integration** — force a reserve failure: assert the order projection reaches `REJECTED` and
no `saga_entry` row remains.

**Reproduction on ES-2** — re-run the exact configuration that produced the evidence:
`REPLICAS=2`, `SCENARIO=steady RATE=30 DURATION=3m`, workload `distinct=6 lines/order=4`.
Pass criteria:

- zero orders left `PENDING`
- `saga_entry` drained
- `saga.completed{outcome="command_failed"}` in the same order of magnitude as the
  `EventStoreException` count

`PG_MAX_CONNECTIONS` must be raised for the second replica (~350 per ES replica; default 600).

Harness note: `OrderFailedEvent` is persisted before the saga ends, so a `saga_entry`-empty
assertion needs slightly more drain tolerance than the zero-`PENDING` assertion.

## Documentation to update

- The `REPLICAS>1` section of `CLAUDE.md` on each ES branch.
- `es_multinode_write_unsafe.md` — record the fix, and correct the "every ES baseline would
  need re-running" claim.
- `to_multinode_outbox_and_retry.md` — the TO-vs-ES asymmetry framing changes once ES also
  degrades to terminal failures.
