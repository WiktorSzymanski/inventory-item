# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**On this branch a retried command does not execute on the retry pool.** Everywhere else in the ES
family, Axon's `RetryingCallback.RetryDispatch.run()` calls `commandBus.dispatch()` inline and
`SimpleCommandBus` handles on the calling thread, so the retry pool is a second execution lane whose
width caps retried work. Here `ConcurrencyRetryScheduler` hands the dispatch task to
`sagaCommandExecutor` instead: the retry pool only serves out the backoff, and one pool runs first
attempts, retries and the saga's terminal dispositions.

This arrived by merging the former `ES-4-NullLock-oneExec` on 2026-08-20. That branch existed to be
the other half of a topology-only A/B against this one; now that its topology *is* this branch's,
`--only ES-4-NullLock,ES-4-NullLock-oneExec` compares a branch against itself and must not be run as
a pair.

The command pool is 112 = the previous 82 + the 30 the retry lane no longer executes on, so the
connection budget is unchanged at `2 x (112 + 60 + 3) = 350`, where the two-lane shape spent the same
350 as `2 x (82 + 30 + 60 + 3)`. Retry threads drop out of the sum because a thread that only calls
`execute(...)` opens no transaction and takes no `axon-jdbc-pool` connection, and the backoff is
served in the `DelayedWorkQueue` on no thread at all. The retry policy is unchanged (`maxRetries=5`,
`initialDelayMs=25`, 500 ms cap), as is the 99-thread Tomcat cap. It is the ES analogue of TO's
`ORDER_RETRY_EXECUTE_ON_RETRY_POOL=false`. Expect the behavioural difference only under contention
(`DISTINCT_ITEMS=1`): a retry now rejoins an unbounded FIFO at the tail, behind first attempts
admitted after it, and the saga's `abandon()`/release/fail-order dispositions share the same queue
(watch `saga_command_failed_total{stage="abandon-rejected"}`).

Both pools are observable: `saga_pool_{active,queued,size}` tagged `pool="command"|"retry"` (same
names as `ES-4-NullLock-mod`), threads named `saga-command-N` / `retry-timer-N` so a
`jcmd <pid> Thread.print` identifies them, and
`logging.level.pl.szymanski.wiktor.config.ConcurrencyRetryScheduler=DEBUG` prints
`[RETRY] executing on saga-command-N` per retry. Expect `saga_pool_active{pool="retry"}` ~0 — those
threads only hand tasks over; sustained activity there means a retry executed on the timer. Note the
thread name changed from `retry-command-N` with this merge; no dashboard panel pins that value.

## What this repository is

A Master's thesis benchmark comparing **Traditional Ownership** (`TO-1`..`TO-4`) against
**Event Sourcing** (`ES-1`..`ES-4`) for the same inventory reservation domain. Each variant
lives on its own branch and implements the same HTTP API, so the branches are meant to be
benchmarked against each other under an identical workload.

`main` has no `k6/` directory. The architecture below describes **`ES-4`** (formerly
`ES-3-pesimistic`); `TO-*` branches implement the same API over a classic mutable schema
with an outbox.

**This branch is `ES-4-parallel`: `ES-4` with the order saga dispatching every line's reserve
command AT ONCE instead of one after the next.** It is a one-variable A/B against `ES-4` — same
lock, same cache, same pools, same gap tuning, same segment count; only the saga differs. The
same port on the uncached baseline is `ES-2-parallel`, so the pair of pairs is what separates
the cache from the dispatch shape. Read `domain/saga/OrderReservationSaga.kt`'s class comment
for what that costs and why the saga can no longer end on the first failure. Everything below
describes `ES-4`, which this branch is otherwise identical to.

**`ES-4` is the lock-free ES variant.** Its `InventoryItem` repository is built with
`NullLockFactory`, so nothing serialises concurrent commands on one aggregate and conflicts
are resolved by the event store's unique constraint plus `ConcurrencyRetryScheduler`. It
kept the name `PessimisticCachingRepository` from when it locked pessimistically; several
files still carry that name, and `ES-4_*` runs recorded before the switch measured the other
write path and are not comparable to later ones.

## Commands

```bash
./gradlew bootRun          # Start on port 8080
./gradlew test             # JUnit 5
./gradlew bootJar          # -> build/libs/app.jar
docker compose up -d       # postgres + nginx + api + prometheus + grafana + cadvisor
```

Single test class: `./gradlew test --tests "pl.szymanski.wiktor.ApplicationTest"`

Note `JAVA_HOME` in the environment may point at a missing JDK; use
`JAVA_HOME=~/.jdks/corretto-21.0.10` for gradle if the build cannot find a toolchain.

## Scaling (identical on all 8 branches)

Every branch runs its API behind an **nginx load balancer that owns host `:8080`**; the API
service itself has no `container_name` and no published port, only `expose: 8080` and
`deploy.replicas`. Scale with the single `REPLICAS` knob in `.env` (auto-loaded by compose,
so the value is sticky):

```bash
REPLICAS=3 PG_MAX_CONNECTIONS=1300 docker compose up -d
```

- **Never also pass `--scale`.** Mixing `--scale` with `deploy.replicas` makes Compose
  remove middle replicas.
- `REPLICAS` drives *both* the container count and `API_REPLICAS`, which on ES branches
  sets the saga per-node claim to `ceil(axon.saga.total-segments / replicas)`. If they
  diverge, segments are left unclaimed and those orders are never processed.
- `PG_MAX_CONNECTIONS` must rise with `REPLICAS` — ~350 connections per ES replica
  (Hikari 50 + Axon 300). The default is `600`; add ~350 per additional replica.
- Containers are named `<project>-api-es-N`, **not** `api-es`, even at `REPLICAS=1`. Use
  `docker compose logs api-es` / `docker compose exec api-es` (service name), not
  `docker logs api-es`. Any cadvisor query must match `name=~".*api-es.*"`.
- Prometheus discovers the API through `dns_sd_configs`, so a replica count change needs no
  config edit.

### `REPLICAS>1` is wired up; write races are now terminal, not permanent

The infrastructure is verified at `REPLICAS=3`: nginx spreads load, Prometheus finds all
targets, and the saga splits its 60 segments evenly (20/20/20, none unclaimed).

**ES is still not multi-node write-*correct*.** On `ES-1`/`ES-2`/`ES-3` the only thing
serialising writers to an `InventoryItem` is a JVM-local `LockFactory`, so a second JVM
removes it: two replicas load the same aggregate at sequence N and both append at N+1,
leaving the `(aggregate_identifier, sequence_number)` unique constraint as the backstop.
Horizontal write scale-out cannot be demonstrated without real distributed concurrency
control.

**`ES-4` has no aggregate lock at all** — its repository is built with `NullLockFactory`, so
that race exists between two threads in one JVM exactly as it does between two replicas.
Everything below about lost races applies to `ES-4` at **every** replica count, including 1.

**What changed is the outcome of losing that race.** It used to be permanent:

1. **The conflict was not classified as retryable — on `ES-1`/`ES-2`/`ES-3`, never here.**
   Those branches built `JdbcEventStorageEngine` on the builder's *default*
   `persistenceExceptionResolver`, `JdbcSQLErrorCodesResolver` — which recognises only
   `SQLIntegrityConstraintViolationException`, a type pgjdbc never throws, so it is
   effectively blind to Postgres. A `23505` therefore surfaced as a generic
   `EventStoreException` rather than a `ConcurrencyException`, and `ConcurrencyRetryScheduler`
   — which matches only `ConcurrencyException` — never retried it. `ES-4` has carried
   `.persistenceExceptionResolver(SQLStateResolver())` all along; that is now backported to the
   other three. (Older notes said the engine had *no* resolver; that was wrong about the
   cause, right about the effect.)
2. **Command failure had no handler.** `OrderReservationSaga` discarded the future returned by
   every `commandGateway.send(...)`. Once a command failed for good, no reservation event was
   appended, the `correlationId` association never fired again, `SagaLifecycle.end()` was never
   reached, and the order stayed `PENDING` **forever**.

Both are now fixed on all four ES branches — (2) on all four, (1) on the three that lacked it.
`.persistenceExceptionResolver(SQLStateResolver())` turns a `23505` into a
`ConcurrencyException`, so `ConcurrencyRetryScheduler` retries it
(5 attempts, `25ms * 2^n` capped at 500 ms); a command that still fails after that reaches a
terminal disposition in `OrderReservationSaga` that releases the already-reserved lines and
sends `FailOrderCommand`. A lost race is therefore a `REJECTED` order — the same way TO has
always degraded.

**On `ES-4` that disposition runs off-thread and ends the saga through `@EndSaga`; here it does
not.** With every line in flight at once, failing the order from a pool thread would end the
saga while its siblings were still running. An exhausted reserve therefore publishes
`SagaReserveAbandonedEvent`, which carries the correlationId back into the saga; the saga
settles that line like any other, and fails the order once every line has reported. The
`OrderFailedEvent` handler survives as a safety net for a failure from outside the saga and is
NOT `@EndSaga` — it records the order as decided and lets the countdown finish.

**Most of the improvement is the retry, not the saga.** Retrying absorbs almost every
conflict, so far fewer commands ever reach exhaustion; the saga's terminal path only has to
dispose of the handful that still do. Do not read the 3537 → 0 delta as evidence that the
saga fix alone would have sufficed — on its own it would have converted 3537 parked orders
into 3537 rejections, not into successes.

Measured on `ES-2` at `REPLICAS=2`, `PG_MAX_CONNECTIONS=950`, `SCENARIO=steady RATE=30
DURATION=3m DISTINCT_ITEMS=6 ITEMS_PER_ORDER=4` (run `ES-2_steady_20260804T083139Z`):
**0 orders left `PENDING`** — the baseline before the fix was 3537 of 10401 — and
`saga_entry` drained to **0**. Of 10400 orders, 10385 `CONFIRMED` and 15 `REJECTED`.
`inventory_optimistic_retry_total` = **8717** (flat zero before the fix) against
`inventory_optimistic_exhausted_total` = **15**: the retry absorbed the conflicts and only 15
commands needed the terminal path. `saga_completed_total{outcome="command_failed"}` = **15**
and `saga_command_failed_total{stage="reserve"}` = 15 match it exactly; the
`{stage="fail-order"}` and `{stage="release"}` series never appeared at all, so no
compensation or fail-order dispatch failed. Harness verdict **PASS** (9/9 validity, 7/7 SLO),
`targets_scraped=2.0`, `completion_ratio_inverse=0.0`, `drain_seconds=6`, e2e p95 0.171 s /
p99 0.263 s. All 15 rejections landed in the **warmup** phase, so the measured window reports
`rejected_ratio=0.0` and `opt_exhausted=0.0` — those zeros mean "none inside the window", not
"the terminal path never fired". `ES-4` itself was not re-measured at `REPLICAS>1`: it shares
the saga file byte-for-byte and already had the resolver, but its lock-free caching repository
is a different aggregate-access path — and one that produces conflicts far more readily — so
treat the numbers above as indicative of the mechanism rather than as an `ES-4` result.

**The contention-vs-stock split is not exhaustive.** `saga_completed{outcome="command_failed"}`
separates contention-driven rejections from genuine out-of-stock ones on the *reservation*
path only. On the completion path `SagaLifecycle.end()` runs synchronously, before the
`CompleteOrderCommand` future resolves, so the saga's `orderId` association is already gone
when the resulting `OrderFailedEvent` arrives. The order still ends up `FAILED`/`REJECTED`
(`FailOrderCommand` targets the aggregate directly), but that saga stays tagged
`outcome="completed"`. `saga_command_failed_total{stage="complete"}` is the only signal for
that path — read it alongside the outcome split, never instead of it.

The `stage` tag has seven values here — the six every other branch emits (`reserve`, `complete`,
`release`, `fail-order`, `fail-order-ignored`, `abandon-rejected`) plus `abandon-publish`, which
exists only on the two `-parallel` branches. The last four are the ones that can leave an order
non-terminal, so a non-zero value there is a different class of problem from the first three:
`fail-order` means the terminal command itself failed; `fail-order-ignored` means the aggregate
refused it because the order was no longer `PENDING`, so no `OrderFailedEvent` exists;
`abandon-rejected` means the saga pool refused the disposition and it ran inline; and
`abandon-publish` means an exhausted reserve could not publish its `SagaReserveAbandonedEvent`,
so that line never settles, the saga waits forever and its `saga_entry` row survives the run.
`abandon-publish` is the one series on this branch that invalidates a run on its own — check it
before reading a `saga_entry` residue as a leak in the design. `python3 k6/bench/compare.py
--cols saga <run-dirs>` tabulates all of them alongside the contention-vs-stock split.

**`REPLICAS=1` is still the measurement-grade configuration**, because at `REPLICAS>1` the
rejection rate is an artefact of lost write races rather than of stock. Read multi-replica
runs as a contention study, not as a throughput result. On a single-node run of a
*lock-holding* branch (`ES-1`/`ES-2`/`ES-3`) `saga_completed_total{outcome="command_failed"}`
should be zero — the JVM-local `LockFactory` prevents the `23505` entirely there — and a
non-zero value falsifies that assumption and means the baseline needs re-examining.
`evaluate.py` enforces this as the `saga_command_failed_single_node` validity check, which is
skipped whenever `EXPECTED_REPLICAS > 1` — above 1 the count is the contention signal itself.
The underlying series come from the `saga_completed` and `saga_cmd_failed` deltas and the
`saga_lifetime` histogram in `queries.promql`, so they land in `dump.json` on every run.

**That check is wrong for `ES-4` and is knowingly left in place.** With `NullLockFactory`
there is no lock to prevent a `23505`, so a single-node `ES-4` run in which any command
exhausts its 5 retries reports `INVALID` on `saga_command_failed_single_node` alone. That is
an artefact of a harness assumption, not a broken measurement: read the rest of the check
list, and treat the run as valid if `saga_command_failed_single_node` is its only failure.
The check is not relaxed here because everything under `k6/` must stay byte-identical across
all eight branches, so the fix would have to land on all eight at once.

**Single-node cost is not quite unchanged.** The `@SagaEventHandler` on `OrderFailedEvent`
adds an `association_value_entry` lookup on the saga processor for *every* rejected order,
where before there was no handler and so no lookup. Negligible on a mostly-confirmed
workload; on a `DISTINCT_ITEMS=1` contention sweep, where most orders are `REJECTED`, it is
one extra indexed SELECT per order on the saga processor's critical path. All four ES
branches took the same change, so ES-vs-ES stays comparable — but if pre-fix and post-fix ES
numbers ever share a table, say so.

**TO is unchanged by this work.** `InventoryService.processOrder` is `@Retryable`
on `OptimisticLockingFailureException` (4 attempts) and, on exhaustion, issues
`FailOrderCommand` — so a lost race becomes a `FAILED` order there too. TO-1/TO-2
additionally claim each `event_publication` row with a database-level
`UPDATE … WHERE completion_date IS NULL`, so only one replica delivers; TO-3/TO-4 have no
such guard and rely on stock Modulith republication. None of it is load-tested at
`REPLICAS>1`.

Do not publish either family's scale-out numbers as throughput results: on ES the extra
rejections are contention, and TO's multi-node path has never been load-tested at all.

## Architecture (ES-4)

Kotlin 2.3 / Spring Boot 4.0.6, **Spring MVC on Tomcat** (blocking servlet stack — not
WebFlux), **Axon Framework 4.11.2** with a **JDBC event store on PostgreSQL**. There is no
KurrentDB and no R2DBC on any current branch.

- **`controller/InventoryController.kt`** — `GET /inventory`, `GET /inventory/{itemId}`,
  `POST /inventory`, `POST /inventory/orders`, `GET /inventory/orders/{orderId}`.
  There is no `POST /inventory/reserve`; standalone reservation was removed.
- **`domain/InventoryItem.kt`** — aggregate. `CreateItemCommand`, `SagaReserveItemCommand`
  (emits `InventoryReservedEvent` or, on insufficient stock, a *persisted*
  `InventoryReservationFailedEvent` — not an exception), `ReleaseReservationCommand`.
- **`domain/OrderAggregate.kt`** — `CreateOrderCommand` / `CompleteOrderCommand` /
  `FailOrderCommand`. Its `OrderStatus` enum is `PENDING/COMPLETED/FAILED`.
- **`domain/saga/OrderReservationSaga.kt`** — started by `OrderCreatedEvent`. Reserves every
  line of the order **in parallel**: all N reserve commands are submitted to the command pool
  from the start handler, where `ES-4` dispatches line k+1 only once line k's
  `InventoryReservedEvent` has come back through the processor. The saga counts its lines down
  and takes ONE terminal decision, in `settle()`, when the last one reports — it cannot end on
  the first failure, because that would drop the correlationId association while its siblings
  were still in flight and their reservations would be held forever. Compensation is built
  from the events that arrived, never from a position in `items`: with N in flight, arrival
  order says nothing about which line reported. Dispatch is on `sagaCommandExecutor` so the
  processor thread never blocks waiting on an aggregate; a command that fails for good cannot
  touch `SagaLifecycle` from that pool thread, so a reserve publishes
  `SagaReserveAbandonedEvent` back into the saga and the completion command (sent after the
  saga has ended) keeps `ES-4`'s off-thread `abandon()`. Emits `saga.completed{outcome}`,
  `saga.lifetime{outcome}` and `saga.command.failed{stage}`, unchanged.
- **`domain/events.kt`** — `SagaReserveAbandonedEvent` is branch-local and handled by nothing
  but the saga. It is published by `EventGateway`, not applied by an aggregate: nothing
  happened to inventory, and the aggregate that would have appended it is the one that could
  not be reached.
- **`config/PessimisticCachingRepository.kt`** — copy-on-write cache in front of the
  event-sourcing repository, built **lock-free** with `NullLockFactory` (`AxonConfig`
  overrides `LockingRepository`'s pessimistic default). The class name predates that switch
  and is kept because `k6/lib/config.js` and `k6/bench/reset.sh` name it and `k6/` must stay
  byte-identical across the eight branches. Nothing serialises commands on one aggregate, so
  concurrent commands all load at sequence N, all append N+1, one wins, and the losers take
  `23505` → `SQLStateResolver` → `ConcurrencyException` → `ConcurrencyRetryScheduler`. A cache
  hit can therefore be stale; that is caught at append time, repaired by the rollback's
  incremental `catchUp`, and retried. The deep copy per load is what makes this safe — without
  a lock, concurrent commands would otherwise mutate one shared root.
  **Caffeine**, bounded by `cache.ttl` (`expireAfterAccess`, 10m)
  and `cache.maximum-size` (10000); a cache hit skips stream replay entirely. Every load
  deep-copies the aggregate via Jackson. Also implements `ConfirmedStateSource`, the read side
  `CacheFedSnapshotter` uses.
  **Eviction is a performance event, never a correctness one.** The cache is a pure accelerator
  over the event store: an evicted entry is just a miss, and the miss path is
  `super.doLoadWithLock`, which reads the authoritative store. The cached
  `SnapshotTrigger` dies with the entry, but that is benign too — `initializeState` replays
  through `publish`, which calls `eventHandled` per replayed event, so the snapshot counter is
  rebuilt from the tail instead of restarting at zero.
  The TTL is an *idle* timeout, so an aggregate under continuous load is never evicted: on a
  benchmark hot set of a handful of items nothing expires and behaviour is identical to the old
  never-evicted `ConcurrentHashMap`. The bounds exist to stop cold aggregates pinning heap.
  Expect `inventory_opt_cache_evicted_total` = 0 on any normal run; a non-zero value means the
  hot set outgrew `cache.maximum-size` and misses are now being paid.
- **`config/CacheFedSnapshotter.kt`** — builds `InventoryItem` snapshots from cached confirmed
  state instead of replaying the store. See the section below.
- **`config/ConcurrencyRetryScheduler.kt`** — retries `ConcurrencyException` only;
  5 attempts, `25ms * 2^n` capped at 500 ms.
- **`subscription/`** — `InventoryProjectionUpdater`, `OrderProjectionUpdater` (tracking
  event processors writing the read models), `MockKafkaPublisher`.

### Snapshots are built from the cache, not from a replay (ES-4 only)

Snapshotting is a **separate path from command execution** and it used to ignore the cache
entirely. `EventCountSnapshotTriggerDefinition` counts every applied event on the cached trigger;
every 30th it fires, and `AbstractSnapshotTrigger.prepareSnapshotScheduling` registers the work on
**`onPrepareCommit`**. Axon's auto-configured `SpringAggregateSnapshotter` sets no executor, so it
inherits `DirectExecutor.INSTANCE`. The stock task then does `eventStore.readEvents(id)` — the fat
snapshot row plus the whole tail — deserialises it, replays it through the
`@EventSourcingHandler`s, serialises the result and stores it. All of that ran **synchronously on
the command thread, before commit**, so every 30th command on a hot item paid a full replay of that
item's stream inline — and while this branch still locked the aggregate pessimistically, it did so
with the lock held, blocking every other command on that item. `additionalBytes` lives on the
aggregate root, so the row being read and written is the fat one.

`CacheFedSnapshotter` serialises the cached root instead. Eliminated per snapshot: the fat
snapshot-row read, the tail reads, the deserialise and the replay; what remains is the serialise
and one INSERT. It hooks `AbstractSnapshotter.createSnapshotterTask` — the one `protected` seam —
so the `snapshotsInProgress` de-duplication, tracing spans, transaction wrapper and silent-failure
handling all still apply, and only the store-reading part is replaced. On a cache miss, a type
mismatch, or `cache.enabled=false` it falls back to the stock replay task.

- **The type guard is required, not defensive.** `inventorySnapshotTrigger` is the only
  `SnapshotTriggerDefinition` bean, so Axon applies it to `OrderAggregate` too and those snapshots
  arrive at the same snapshotter. They must never be served from the `InventoryItem` cache.
- **The snapshot lands behind the triggering command's sequence, typically at N-1.** The trigger
  fires at `onPrepareCommit` while `advance` runs at `afterCommit`, so the cache still holds an
  earlier sequence — and without a lock, exactly which earlier sequence is not fixed, since a
  concurrent command may have advanced the cache in between. Whatever is there is committed *by
  construction*, because the cache only ever holds persisted state; that is what makes this safer
  than making the stock replay asynchronous would have been, since an async `readEvents` would race
  the in-flight commit for the same result with no such guarantee. Costs those few events on the
  next cold replay and nothing else.
- **`AggregateSnapshotter`'s "replaces more than one event" guard is dropped**, because evaluating
  it needs the very read being eliminated. Harmless at threshold 30.
- **Metrics:** `inventory_opt_snapshot_duration_seconds{source="cache"|"replay"}`. The `_count`
  suffix is the per-source snapshot count; no separate counter is registered. Not in
  `queries.promql` — that file is byte-shared with ES-2 and this change is ES-4-only, so read
  these from `/actuator/prometheus` or the Prometheus UI. The existing `state_load_time{phase}`
  series already captures the win with no harness edit: after warmup its sample **count** should
  drop sharply, because the snapshotter's replay was most of what it was measuring. The fallback
  replay is tagged `path="snapshot"` there, so it no longer inflates the write path — see
  `state_load_time` below.
- **The failure mode is silent and the benchmark cannot catch it.** A wrong aggregate type name or
  sequence yields an unusable snapshot, and a cache that rarely evicts almost never performs the
  cold load that would read it back. `InventoryPessimisticConcurrencyTest.a cache-fed snapshot
  restores correct state on a cold load` is the guard: it forces the cold load and asserts
  behaviourally (a reserve that must be refused on the restored quantity). Keep it.

**This makes ES-4's write path differ from ES-1/ES-2/ES-3**, which all still pay the synchronous
replay. It is a property *of the copy-on-write cache* rather than generic Axon tuning, which is
why it is defensible as an ES-4-only change — the other branches have no confirmed-state cache to
read from. But any table sharing ES-3 and ES-4 snapshot or write-path numbers must say so.

### Reading `state_load_time` on this branch

The metric is recorded in `TimedEventStorageEngine`, which wraps the storage engine and therefore
sees **every** store round trip with nothing in the call saying who asked. Three unrelated callers
reach it here, so two tags are needed before any phase means what it looks like:

- `{aggregate}` — `OrderAggregate` is loaded from the store once per order, `InventoryItem` once per
  line and (on this branch) almost never, because the cache absorbs it. Without the tag one
  histogram pools both, and the p50 silently changes meaning per branch rather than changing value.
- `{path}` — `command` is the write path; `repair` is `PessimisticCachingRepository.catchUp` reading
  the delta *after* the command's append already failed; `snapshot` is `CacheFedSnapshotter` falling
  back to the stock replay task, which runs inline on the command thread at `onPrepareCommit`. Only
  an empty repair probe was ever separable before (it identifies no aggregate and lands under
  `aggregate="unknown"`); a repair that found events looked exactly like a cold miss.

Phases, all of which carry both tags:

| phase | what it measures |
|---|---|
| `load` | **the write path's whole state-load cost, before the append** — hits and misses pooled |
| `copy` | the hit arm: Jackson deep copy + `reconstruct`, what replaces the store round trip |
| `snapshot` / `events` / `replay` | the miss arm, decomposed: snapshot row read, tail fetch, in-memory replay |
| `total` | the miss arm end to end, first I/O call to fully replayed |

`load` is the one to read for "what does the write path pay to load state". `copy` and `total`
describe the two arms and are disjoint populations over disjoint sets of commands, so neither one's
percentile is that number and the ratio between them moves with the hit rate. `load` is recorded
around the whole of `doLoadWithLock` (so it also covers `validateOnLoad` and, on a miss, the deep
copy that seeds the cache); for `OrderAggregate`, which has no caching repository, the engine emits
it directly from the store round trip. Exactly one of the two records per load.

Only `path="command"` gets a `load` phase at all, so a query needs no path filter to be
write-path-only — including `queries.promql`'s byte-shared `state_load` row, which picks the new
phase up unchanged. For the other phases, filter with `path!="repair"` rather than `path="command"`:
an absent label satisfies `!=`, so the same query also resolves on the other branches and on runs
archived before the tag existed.

`inventory_opt_catchup_duration_seconds{outcome}` is the whole repair (delta read, copy, replay,
merge), and `path="repair"` on `state_load_time` is the store read inside it; the gap between them
is what the repair costs beyond reading.

### Two traps that have caused real bugs

**Order status values differ between the aggregate and the projection.** The aggregate uses
`PENDING/COMPLETED/FAILED`; `OrderProjectionUpdater` writes `PENDING/CONFIRMED/REJECTED`,
and `GET /inventory/orders/{orderId}` returns the *projection* values. `TO-*` writes
`COMPLETED`. Anything that must work on both families should key off `PENDING` — the one
value both schemas share and both default to.

**`POST /inventory/orders` returns 202 Accepted.** It persists only `OrderCreatedEvent`;
the reservation is asynchronous. A 202 says nothing about stock availability, and the HTTP
response time is admission latency only — frequently 3 orders of magnitude below true
end-to-end latency. Out-of-stock surfaces as `status=REJECTED` on the order projection,
never as an HTTP error. `422` and `409` are unreachable on this endpoint; the only
exception thrown anywhere in the app is `ItemAlreadyExistsException`, from `POST /inventory`.

### Config knobs that matter

`src/main/resources/application.yaml`: `snapshot.event-count` (30), `cache.enabled`,
`cache.ttl` (10m, `CACHE_TTL`) and `cache.maximum-size` (10000, `CACHE_MAXIMUM_SIZE`),
`axon.saga.total-segments` (`${AXON_SAGA_TOTAL_SEGMENTS:60}`), `axon.saga.replicas` (`${API_REPLICAS:1}`),
`axon.jdbc.pool.size` (300), `axon.eventstore.*` (below), and the Micrometer
`distribution` block.

**`axon.eventstore.max-gap-offset` (500) is a correctness knob, not a tuning one.** It exists
on `ES-3`/`ES-4` only; `ES-1`/`ES-2` run Axon's defaults (10000 / 60000).
`GapAwareTrackingToken` discards every gap more than `max-gap-offset` indices behind the
token, so an event whose row commits after the token has advanced that far past it is skipped
by every tracking processor **permanently**. Rolled-back appends leave permanent gaps because
`global_index` is a `BIGSERIAL` and appends autocommit on their own connection. On the
lock-holding branches they are rare below `REPLICAS>1`; **on `ES-4` they are routine at every
replica count**, because `NullLockFactory` means ordinary in-JVM contention produces them. For
scale: the reference `ES-2` run at `REPLICAS=2` burnt ~8717 index values in 180 s, which makes
500 indices a ~1.4 s window. The exposure is still small (index assignment and commit are in
one autocommitted statement) and that run measured `completion_ratio_inverse=0.0`, so the
value stands — but revisit it before any run appreciably faster, and treat a non-zero
`completion_ratio_inverse` on any `ES-4` run as a reason to suspect it first.

**`axon.saga.total-segments` defaults to 60 on every ES branch, and every archived run used
that value.** It is the fixed segment pool that `ceil(total-segments / replicas)` divides,
and 60 splits evenly for 2/3/4/5/6 replicas. ES-1/2/3 previously used `segments: 32`, so
single-node results produced before that change are not comparable to later ones. Changing
it requires resetting the `order-saga` tokens (`TRUNCATE token_entry`, which
`k6/bench/reset.sh` does on every run, so a bench run needs no manual step).

**It is now a per-run knob**: `AXON_SAGA_TOTAL_SEGMENTS` (compose default 60) binds to it,
and `bench.sh` records the value in `meta.json` as `saga_total_segments` — without which two
runs of the same commit would be indistinguishable in `bench-results/`. It is suite-wide,
not per-variant, because `run-suite.sh` has no per-variant env hook and a compose default
always SETS the variable: vary it across passes, e.g.
`AXON_SAGA_TOTAL_SEGMENTS=8 scripts/run-suite.sh --only ES-4`.

Why it matters beyond tidiness: `order-saga` is a `TrackingEventProcessor`, so EACH segment
opens its own tracking query over the whole event stream and discards the events it does not
own. At 60 segments every appended event is read 63 times (60 saga + 3 projections), which
the Phase-1 capacity runs measured as ~50-57 Postgres transactions per event — against ~1.2%
of transactions for the item appends themselves. Prefer a power of two: at 60 under a
64-bucket hash mask, segments 29-32 carry exactly double the load of the other 56.

**`axon.jdbc.pool.size` must exceed the saga per-node claim.** At the old 150 with a
60-thread claim the pool ran dry (`active=150, waiting=97`) and sagas that failed to
dispatch were never retried, stranding orders in `PENDING` forever. Measured on an
identical 3m steady run: 60 segments at pool 150 stranded 48 orders and never drained;
at pool 300, zero stranded.

**`order.e2e.time` histogram bounds must stay identical on every branch**
(`minimum-expected-value: 1ms`, `maximum-expected-value: 10m`). Micrometer's default Timer
max is 30 s; when a branch omits these, every sample above 30 s collapses into `+Inf` and
`histogram_quantile` reports ~30 s, making a saturated variant look *faster* than a
healthy one. This silently invalidated all TO-vs-ES latency comparisons before it was fixed.

## Benchmarking

The supported entry point is **`./k6/bench/bench.sh`**, run from the host.

```bash
SCENARIO=capacity ./k6/bench/bench.sh                          # find the knee
SCENARIO=steady RATE=60 DURATION=10m ./k6/bench/bench.sh       # head-to-head point
SCENARIO=steady DISTINCT_ITEMS=1 ./k6/bench/bench.sh           # contention sweep
SCENARIO=soak ./k6/bench/bench.sh                              # drift
python3 k6/bench/compare.py bench-results/*_steady_*           # thesis table
python3 k6/bench/compare.py --knee bench-results/*_capacity_*  # staircase table
```

Scenarios: `capacity` (stepped staircase), `steady`, `spike`, `soak`, plus the internal
`seed` / `warmup` phases and a deprecated `legacy` profile. Each run resets the database,
warms up a fixed number of iterations, runs the load, waits for the backlog to drain, then
snapshots Prometheus into `bench-results/<variant>_<scenario>_<ts>/`.

**k6 is deliberately fire-and-forget.** Because of the 202 above, k6 never observes
end-to-end latency; that comes exclusively from the server-side `order_e2e_time` histogram
via `k6/bench/dump.py`. Verdicts are therefore computed *post-run* by
`k6/bench/evaluate.py`, which returns `PASS` / `FAIL` / `INVALID` — `INVALID` meaning the
measurement itself was broken (backlog never drained, scrape gap, API restarted mid-run,
orders that never reached a terminal event), which is not the same as a slow system.

**All eight variant branches now carry the harness.** It used to exist only on `ES-2` and
`ES-4`; `TO-1`..`TO-4` gained it earlier, and `ES-1`/`ES-3` gained it on 2026-08-06, so no
branch is left on the legacy `k6/run.sh` + `k6/reserve-load-test.js` path.

Everything under `k6/` and `docker-compose.bench.yml` is **byte-identical** on all eight;
`bench.env` is the only per-branch file. The reference is `ES-4`, not `ES-2` (the
`harness-v1` ref older comments name does not exist in this repository):

```bash
git diff --stat ES-4 <branch> -- k6/bench k6/lib k6/main.js docker-compose.bench.yml
```

must print nothing. `k6/benchmark-campaign-plan.md` and `k6/campaign-prerequisites-plan.md`
are `ES-4`-local notes rather than harness, which is why the path list is explicit rather
than a bare `k6`.

**`IMAGE_TAG` is unique per variant, not per family.** `bench.env` sets
`inventory-reservation-<variant>:latest` (lowercased), and `docker-compose.yml` substitutes
`${IMAGE_TAG:-<that variant>}` rather than hardcoding a tag. Before this, `ES-2` and `ES-4`
both built `inventory-reservation-es:latest` and `TO-1`..`TO-4` shared the `-to` one, so
building any variant silently overwrote its sibling and a bare `docker compose up` could
run another variant's jar. `common.sh` exports `IMAGE_TAG` from `bench.env`, and Compose
prefers the shell environment over `.env`, so the harness and manual compose always agree.

### Benchmarking all eight variants: `main`

`main` carries no application code anybody benchmarks — its `src/` is an early prototype.
What it carries is the cross-variant entry point:

```bash
scripts/build-images.sh                                     # one image per branch
SCENARIO=steady RATE=60 DURATION=10m scripts/run-suite.sh   # every variant, in turn
python3 scripts/compare.py bench-results/*_steady_*
```

Each variant is built and run from its own git worktree under `.worktrees/<variant>/`,
using **that branch's** harness — `main` deliberately holds no unified `docker-compose.yml`
or `queries.promql`, because the families genuinely differ and a third copy would drift
from both. Worktree `bench-results/` are symlinked to `main`'s, so every run lands in one
place. `run-suite.sh` passes `SKIP_BUILD=1`, pins one Compose project (`iir`), and runs
`down -v --remove-orphans` before each variant — which is also what forces Prometheus to
re-read its bind-mounted config across a TO↔ES switch.

`build-images.sh` stamps the commit SHA with a **`RUN`** layer, not a `LABEL`. `image_fresh`
is a *validity* check computed as `image Created >= HEAD commit time`, and since the
`Dockerfile` only COPYs `gradle/` and `src/`, a docs-only commit reuses every cached layer
and leaves `Created` older than `HEAD` — reporting a good run `INVALID`. A `LABEL` does not
fix it: BuildKit inherits `Created` from the base image, so the stamp yields a new image ID
carrying the old timestamp. A `RUN` creates a layer and the image is dated now.

`EXPECTED_REPLICAS` is **not** set in `bench.env`; `common.sh` derives it from `REPLICAS`
in `.env`, the file compose actually acts on. `reset.sh` asserts the running container
count against it before the measured run starts.

### Gotchas when editing the harness

- **k6 ≥ 2.0 no longer copies system env vars into `__ENV`.** Pass every knob with `-e`.
  The container is pinned to `grafana/k6:1.1.0` so `latest` cannot drift mid-thesis.
- **PromQL `@` binds to selectors, not aggregations.** `sum(foo) @ T` is a parse error;
  `sum(foo @ T)` is valid. Only the `hist` queries use `@`; everything else gets its
  evaluation instant from the API `time` parameter.
- **Always `sum()`-wrap PromQL.** The `job` label differs per branch (`inventory-to` vs
  `inventory-es`) and every branch now scrapes via `dns_sd_configs`, so unaggregated
  expressions return one series per replica as soon as `REPLICAS>1`.
- **`API_CONTAINER_RE` must stay unanchored** (`.*api-es.*`). The API service carries no
  `container_name`, so cadvisor sees `<project>-api-es-N`; `queries.promql` matches it with
  an anchored `name=~"$CRE"`, and a bare `api-es` would silently match nothing.
- **`additionalBytesSize` only rides on `InventoryCreatedEvent`**, never on
  `InventoryReservedEvent`. It does not inflate the append path — it inflates snapshot rows
  and the per-command Jackson deep copy, i.e. it is a copy-on-write cost lever.

Grafana `http://localhost:3000` · Prometheus `http://localhost:9090` · Swagger `/swagger-ui.html`
