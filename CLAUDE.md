# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A Master's thesis benchmark comparing **Traditional Ownership** (`TO-1`..`TO-4`) against
**Event Sourcing** (`ES-1`..`ES-4`) for the same inventory reservation domain. Each variant
lives on its own branch and implements the same HTTP API, so the branches are meant to be
benchmarked against each other under an identical workload.

Those eight are the whole set. `ES-4` was renamed from `ES-3-pesimistic`, so
`bench-results/ES-3-pesimistic_*` directories predate the rename and belong to `ES-4`; the
`ES-3-pesimistic-scaling` branch that older comments refer to has been deleted (its
topology now lives on every branch — see Scaling).

`main` has no `k6/` directory. The architecture below describes the **ES-\*** branches;
`TO-*` branches implement the same API over a classic mutable schema with an outbox.

**This branch is `ES-2-parallel`: `ES-2` with the order saga dispatching every line's reserve
command AT ONCE instead of one after the next.** It is a one-variable A/B against `ES-2` —
same lock, same pools, same gap tuning, same segment count; only the saga differs. Read
`domain/saga/OrderReservationSaga.kt`'s class comment for what that costs and why the saga can
no longer end on the first failure. Everything below describes `ES-2`, which this branch is
otherwise identical to.

**`ES-2` itself is the snapshotting, uncached ES baseline, and has been LOCK-FREE since
2026-08-20.** `AxonConfig` declares an `inventoryItemRepository` bean built with
`NullLockFactory` and `InventoryItem` names it on `@Aggregate`, so nothing serialises
concurrent commands on one item and conflicts are resolved by the event store's unique
constraint plus `ConcurrencyRetryScheduler`.

This code was `ES-2-NullLock` until 2026-08-20, when it was adopted onto `ES-2` wholesale and
the suffixed branch was retired — it was the default, and the pessimistic `ES-2` was the
obsolete one. There is no lock A/B left in the suite: **nothing in the registry still builds
`PessimisticLockFactory` except `ES-3`.** The old write path is still reachable at `0603497`
(the adoption commit's first parent) if the comparison is ever wanted back.

**Three things separate this from the pre-2026-08-20 `ES-2`, not one.** The lock;
`ES-3`/`ES-4`'s gap-tracking tuning (`axon.eventstore.max-gap-offset` and friends), which is
load-bearing here because lock-free rollbacks leave permanent `global_index` gaps at every
replica count; and the lane widths (command pool 112, retry 30-as-timer, Tomcat 99, where the
old branch ran 64/23 and Boot's default 200). **Any `ES-2_*` run in `bench-results/` from
before that date measured the other write path** — the variant name did not change, so date the
run before it enters a table.

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

**This branch has no aggregate lock at all** — `inventoryItemRepository` is built with
`NullLockFactory`, so that race exists between two threads in one JVM exactly as it does
between two replicas. Everything below about lost races applies here at **every** replica
count, including 1.

**What changed is the outcome of losing that race.** It used to be permanent:

1. **The conflict was not classified as retryable.** `AxonConfig.eventStorageEngine` built
   `JdbcEventStorageEngine` on the builder's *default* `persistenceExceptionResolver`,
   `JdbcSQLErrorCodesResolver` — which recognises only `SQLIntegrityConstraintViolationException`,
   a type pgjdbc never throws, so it is effectively blind to Postgres. A `23505` therefore
   surfaced as a generic `EventStoreException` rather than a `ConcurrencyException`, and
   `ConcurrencyRetryScheduler` — which matches only `ConcurrencyException` — never retried it.
   (Older notes here said the engine had *no* resolver; that was wrong about the cause, right
   about the effect.)
2. **Command failure had no handler.** `OrderReservationSaga` discarded the future returned by
   every `commandGateway.send(...)`. Once a command failed for good, no reservation event was
   appended, the `correlationId` association never fired again, `SagaLifecycle.end()` was never
   reached, and the order stayed `PENDING` **forever**.

Both are fixed on all four ES branches. `.persistenceExceptionResolver(SQLStateResolver())`
turns a `23505` into a `ConcurrencyException`, so `ConcurrencyRetryScheduler` retries it
(5 attempts, `25ms * 2^n` capped at 500 ms); a command that still fails after that reaches a
terminal disposition in `OrderReservationSaga` that releases the already-reserved lines and
sends `FailOrderCommand`. A lost race is therefore a `REJECTED` order — the same way TO has
always degraded.

**On `ES-2` that disposition runs off-thread and ends the saga through `@EndSaga`; here it does
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
"the terminal path never fired".

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

**That check is wrong for this branch and is knowingly left in place.** With `NullLockFactory`
there is no lock to prevent a `23505`, so a single-node run in which any command exhausts its 5
retries reports `INVALID` on `saga_command_failed_single_node` alone. That is an artefact of a
harness assumption, not a broken measurement: read the rest of the check list, and treat the run
as valid if `saga_command_failed_single_node` is its only failure. The check is not relaxed here
because everything under `k6/` must stay byte-identical across all the variant branches, so the
fix would have to land on all of them at once.

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

## Architecture (ES-\* branches)

Kotlin 2.3 / Spring Boot 4.0.6, **Spring MVC on Tomcat** (blocking servlet stack — not
WebFlux), **Axon Framework 4.11.2** with a **JDBC event store on PostgreSQL**. There is no
KurrentDB and no R2DBC on any current branch.

- **`controller/InventoryController.kt`** — `GET /inventory`, `GET /inventory/{itemId}`,
  `POST /inventory`, `POST /inventory/orders`, `GET /inventory/orders/{orderId}`.
  There is no `POST /inventory/reserve`; standalone reservation was removed.
- **`domain/InventoryItem.kt`** — aggregate. `CreateItemCommand`, `SagaReserveItemCommand`
  (emits `InventoryReservedEvent` or, on insufficient stock, a *persisted*
  `InventoryReservationFailedEvent` — not an exception), `ReleaseReservationCommand`.
  `@Aggregate(repository = "inventoryItemRepository")`, which is what makes the lock-free bean
  in `AxonConfig` take effect: `@Aggregate` registration wins over any manual
  `configureAggregate(...)`, so a bean the annotation does not name is silently ignored and the
  aggregate quietly keeps Axon's pessimistic default. The `snapshotTriggerDefinition` moved onto
  that repository for the same reason — Axon ignores the annotation attribute once `repository`
  is set, and leaving it there would have disabled snapshotting without a word.
  `InventoryLockFreeConcurrencyTest` asserts all three: that
  `Configuration.repository(InventoryItem::class)` *is* that bean, that concurrent reserves
  actually produce optimistic retries, and that snapshots are still written.
- **`domain/OrderAggregate.kt`** — `CreateOrderCommand` / `CompleteOrderCommand` /
  `FailOrderCommand`. Its `OrderStatus` enum is `PENDING/COMPLETED/FAILED`.
- **`domain/saga/OrderReservationSaga.kt`** — started by `OrderCreatedEvent`. Reserves every
  line of the order **in parallel**: all N reserve commands are submitted to the command pool
  from the start handler, where `ES-2` dispatches line k+1 only once line k's
  `InventoryReservedEvent` has come back through the processor. The saga counts its lines down
  and takes ONE terminal decision, in `settle()`, when the last one reports — it cannot end on
  the first failure, because that would drop the correlationId association while its siblings
  were still in flight and their reservations would be held forever. Compensation is built
  from the events that arrived, never from a position in `items`: with N in flight, arrival
  order says nothing about which line reported. Dispatch is on `sagaCommandExecutor` so the
  processor thread never blocks on aggregate locks; a command that fails for good cannot touch
  `SagaLifecycle` from that pool thread, so a reserve publishes `SagaReserveAbandonedEvent`
  back into the saga and the completion command (sent after the saga has ended) keeps `ES-2`'s
  off-thread `abandon()`. Emits `saga.completed{outcome}`, `saga.lifetime{outcome}` and
  `saga.command.failed{stage}`, unchanged.
- **`domain/events.kt`** — `SagaReserveAbandonedEvent` is branch-local and handled by nothing
  but the saga. It is published by `EventGateway`, not applied by an aggregate: nothing
  happened to inventory, and the aggregate that would have appended it is the one that could
  not be reached.
- **`config/PessimisticCachingRepository.kt`** — copy-on-write cache in front of the
  event-sourcing repository. Strong-reference `ConcurrentHashMap`, **never evicted**; a
  cache hit skips stream replay entirely. Every load deep-copies the aggregate via Jackson.
- **`config/ConcurrencyRetryScheduler.kt`** — retries `ConcurrencyException` only;
  5 attempts, `25ms * 2^n` capped at 500 ms. **The retry pool TIMES the backoff; it does not
  execute the retry** — see below.
- **`config/CommandGatewayConfig.kt`** — the two pools and the connection budget they are
  derived from.
- **`subscription/`** — `InventoryProjectionUpdater`, `OrderProjectionUpdater` (tracking
  event processors writing the read models), `MockKafkaPublisher`.

### One pool, not two: retries execute on the command pool

Ported from `ES-4` on 2026-08-20, where it arrived as `ES-4-NullLock-oneExec`. `ES-1`, `ES-2`
and `ES-4` now share this topology; **`ES-3` does not**, so it is the one ES design point where
a retry still executes on a retry pool.

The two-lane shape was never a design choice — it was a consequence nobody opted into. Axon's
`RetryingCallback.RetryDispatch.run()` calls `commandBus.dispatch()` **inline** and the
autoconfigured `SimpleCommandBus` handles on the calling thread, so the whole retried command —
aggregate load, `reserveDelayMs` sleep, append, commit — executes wherever the scheduled task
runs. `ConcurrencyRetryScheduler` now hands that task to `sagaCommandExecutor`, so first
attempts, retries and the saga's terminal dispositions all share one pool. It is the ES
analogue of TO's `ORDER_RETRY_EXECUTE_ON_RETRY_POOL=false`.

The connection budget is unchanged, which is what keeps runs from either side of the port
comparable:

```
before  2 x ( 82 command + 30 retry + 60 saga + 3 projections) = 350
after   2 x (112 command +  0 retry + 60 saga + 3 projections) = 350
```

Retry threads leave the sum because a thread that only calls `execute(...)` opens no
transaction and takes no `axon-jdbc-pool` connection, and the backoff is served in the
`DelayedWorkQueue` on no thread at all. Equal executing threads (175), equal connections (350,
the `AXON_JDBC_POOL_SIZE` default), identical retry policy, identical Tomcat cap.
`RetryDispatchTargetTest` asserts both the hand-off and that sum.

**Two effects push opposite ways, which is the question.** A retry now rejoins an unbounded
FIFO at the **tail**, behind first attempts admitted after it, so its real wait becomes backoff
+ queue depth; and `OrderReservationSaga` submits `abandon()`/release/fail-order to the same
executor, so a saturated pool delays terminal dispositions behind retried work — watch
`saga_command_failed_total{stage="abandon-rejected"}`.

**What to read.** Threads are named `saga-command-N` and `retry-timer-N`, and both pools publish
`saga_pool_{active,queued,size}` plus Micrometer's `executor_*`. Expect
`saga_pool_active{pool="retry"}` at ~0 — sustained activity there means a retry executed on the
timer, which is exactly what must not happen. `saga_pool_queued{pool="retry"}` is the in-flight
retry count (delayed tasks in the queue), **not** threads waiting for a slot.
`inventory_retry_handoff_rejected_total` should be 0: it counts retries that ran on the timer
thread after the command pool refused the hand-off, which the unbounded queue makes possible
only at shutdown. Setting `ConcurrencyRetryScheduler` to `DEBUG` prints
`[RETRY] executing on saga-command-N` per retry.

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
`axon.saga.total-segments` (`${AXON_SAGA_TOTAL_SEGMENTS:60}`), `axon.saga.replicas` (`${API_REPLICAS:1}`),
`axon.jdbc.pool.size` (300), `axon.eventstore.max-gap-offset` (500) and its two companions,
and the Micrometer `distribution` block.

**`axon.eventstore.max-gap-offset` is a correctness knob here, not just a token-size one.**
`GapAwareTrackingToken` discards every gap more than that many indices behind the token, so an
event whose row commits after the token has advanced that far past it is skipped by every
tracking processor **permanently**. Rolled-back appends leave permanent gaps because
`global_index` is a `BIGSERIAL` and appends autocommit on their own connection — and on this
branch they are routine at every replica count, because `NullLockFactory` means ordinary in-JVM
contention produces them. `ES-2` runs Axon's defaults (10000 / 60000); the tightened values here
come from `ES-3`/`ES-4`. Treat a non-zero `completion_ratio_inverse` on any run as a reason to
suspect this value first.

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
