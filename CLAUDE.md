# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**This branch is `ES-1`: the uncached, unsnapshotted ES baseline, and LOCK-FREE since
2026-08-20.** `AxonConfig` declares an `inventoryItemRepository` bean built with
`NullLockFactory` and `InventoryItem` names it on `@Aggregate`, so nothing serialises
concurrent commands on one item and conflicts are resolved by the event store's unique
constraint plus `ConcurrencyRetryScheduler`.

This code was `ES-1-NullLock` until 2026-08-20, when it was adopted onto `ES-1` wholesale and
the suffixed branch was retired — it was the default, and the pessimistic `ES-1` was the
obsolete one. There is no lock A/B left in the suite: **nothing in the registry still builds
`PessimisticLockFactory` except `ES-3`.** The old write path is still reachable at `ec63eca`
(the adoption commit's first parent) if the comparison is ever wanted back.

**Three things separate this from the pre-2026-08-20 `ES-1`, not one.** The lock;
`ES-3`/`ES-4`'s gap-tracking tuning (`axon.eventstore.max-gap-offset` and friends), which is
load-bearing here because lock-free rollbacks leave permanent `global_index` gaps at every
replica count; and the lane widths (command pool 112, retry 30-as-timer, Tomcat 99, where the
old branch ran 64/23 and Boot's default 200). **Any `ES-1_*` run in `bench-results/` from
before that date measured the other write path** — the variant name did not change, so date the
run before it enters a table.

## Commands

```bash
./gradlew bootRun          # Start on port 8080
./gradlew test             # JUnit 5
./gradlew bootJar          # -> build/libs/app.jar
docker compose up -d       # postgres + nginx + api + prometheus + grafana + cadvisor
```

Note `JAVA_HOME` in the environment may point at a missing JDK; use
`JAVA_HOME=~/.jdks/corretto-21.0.10` for gradle if the build cannot find a toolchain.

Run a single test class: `./gradlew test --tests "pl.szymanski.wiktor.ApplicationTest"`

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
(5 attempts, `25ms * 2^n` capped at 500 ms); a command that still fails after that reaches
`OrderReservationSaga.abandon()`, which releases the already-reserved lines and sends
`FailOrderCommand`, and the resulting `OrderFailedEvent` comes back to an `@EndSaga` handler
that ends the saga. A lost race is therefore a `REJECTED` order — the same way TO has always
degraded.

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
compensation or fail-order dispatch failed. Verdict **PASS** (9/9 validity, 7/7 SLO),
`targets_scraped=2.0`, `completion_ratio_inverse=0.0`, `drain_seconds=6`, e2e p95 0.171 s /
p99 0.263 s. All 15 rejections landed in the **warmup** phase, so the measured window reports
`rejected_ratio=0.0` and `opt_exhausted=0.0` — those zeros mean "none inside the window", not
"the terminal path never fired".

That run used the `k6/bench/` harness. **This branch now has it too** — it was rolled out
here on 2026-08-06, replacing the legacy `k6/run.sh` + `k6/reserve-load-test.js` path, so
`./k6/bench/bench.sh` works the same way it does on `ES-2`. The code fix is shared across
all four branches; the `REPLICAS>1` *measurement* above is still `ES-2`'s alone, and this
branch has never been measured at `REPLICAS>1`.

**The contention-vs-stock split is not exhaustive.** `saga_completed{outcome="command_failed"}`
separates contention-driven rejections from genuine out-of-stock ones on the *reservation*
path only. On the completion path `SagaLifecycle.end()` runs synchronously, before the
`CompleteOrderCommand` future resolves, so the saga's `orderId` association is already gone
when the resulting `OrderFailedEvent` arrives. The order still ends up `FAILED`/`REJECTED`
(`FailOrderCommand` targets the aggregate directly), but that saga stays tagged
`outcome="completed"`. `saga_command_failed_total{stage="complete"}` is the only signal for
that path — read it alongside the outcome split, never instead of it.

The `stage` tag has six values: `reserve`, `complete`, `release`, `fail-order`,
`fail-order-ignored` and `abandon-rejected`. The last three are the ones that can leave an
order non-terminal, so a non-zero value there is a different class of problem from the first
three: `fail-order` means the terminal command itself failed; `fail-order-ignored` means the
aggregate refused it because the order was no longer `PENDING`, so no `OrderFailedEvent`
exists and the saga never ends; `abandon-rejected` means the saga pool refused the
disposition and it ran inline.

**`REPLICAS=1` is still the measurement-grade configuration**, because at `REPLICAS>1` the
rejection rate is an artefact of lost write races rather than of stock. Read multi-replica
runs as a contention study, not as a throughput result. On a single-node run of a
*lock-holding* branch (`ES-1`/`ES-2`/`ES-3`) `saga_completed_total{outcome="command_failed"}`
should be zero — the JVM-local `LockFactory` prevents the `23505` entirely there — and a
non-zero value falsifies that assumption and means the baseline needs re-examining.
`evaluate.py` enforces this as the `saga_command_failed_single_node` validity check, which is
skipped whenever `EXPECTED_REPLICAS > 1` — above 1 the count is the contention signal itself.

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

### `order.e2e.time` on `ES-1`: ported 2026-08-04, so pre-dating runs have none

`ES-1` used not to emit `order.e2e.time` at all — `OrderProjectionUpdater` had no `e2eTimer` /
`recordE2e` / `readCreatedAt` and never wrote `created_at` from the event timestamp, and
`application.yaml` had no entry for it. The symptom was an *absent* series, not a mis-bucketed
one: `order_e2e_time_*` simply never appeared in `/actuator/prometheus`.

Both halves are now in place — the projection code is byte-identical to `ES-2`'s, and the
bounds are pinned. **`bench-results/` directories produced before this change contain no
`order.e2e.time` data and cannot be put in a TO-vs-ES latency table**; re-run `ES-1` if you
need its end-to-end numbers.

`created_at` is written from the event's own `@Timestamp`, not `now()`, so e2e is measured
against admission time and stays correct on replay. The bounds matter for the reason they
matter everywhere: Micrometer's default Timer maximum is 30 s, and without an explicit
`maximum-expected-value` every larger sample collapses into `+Inf` and `histogram_quantile`
reports ~30 s — making a saturated branch look *faster* than a healthy one.

## Architecture

Kotlin 2.3 / Spring Boot 4.0.6, **Spring MVC on Tomcat** (blocking servlet stack — not
WebFlux), **Axon Framework 4.11.2** with a **JDBC event store on PostgreSQL**. There is no
KurrentDB, no R2DBC and no coroutine code on this branch.

`ES-1` is the **uncached baseline** of the ES family: no aggregate cache and no snapshot
trigger, so every command replays its aggregate's stream from event 0. `ES-3`/`ES-4` add
caching and snapshotting on top of the same domain, which is the comparison they exist for.

- **`controller/InventoryController.kt`** — `GET /inventory`, `GET /inventory/{itemId}`,
  `POST /inventory`, `POST /inventory/orders`, `GET /inventory/orders/{orderId}`.
  There is no `POST /inventory/reserve`; standalone reservation was removed.
- **`service/InventoryService.kt`** — thin dispatch layer over `CommandGateway`; it holds no
  retry loop of its own (retries live in `config/ConcurrencyRetryScheduler.kt`).
- **`domain/InventoryItem.kt`** — aggregate. `CreateItemCommand`, `SagaReserveItemCommand`
  (emits `InventoryReservedEvent` or, on insufficient stock, a *persisted*
  `InventoryReservationFailedEvent` — not an exception), `ReleaseReservationCommand`.
  `@Aggregate(repository = "inventoryItemRepository")` with no `snapshotTriggerDefinition`
  anywhere, so snapshots are never taken even though `AxonConfig` configures a
  `snapshot_event_entry` table and serializer. Naming the repository is what makes the
  lock-free bean take effect: `@Aggregate` registration wins over any manual
  `configureAggregate(...)`, so a bean the annotation does not name is silently ignored and the
  aggregate quietly keeps Axon's pessimistic default. `InventoryLockFreeConcurrencyTest` asserts
  both halves — that `Configuration.repository(InventoryItem::class)` *is* that bean, and that
  concurrent reserves actually produce optimistic retries.
- **`domain/OrderAggregate.kt`** — `CreateOrderCommand` / `CompleteOrderCommand` /
  `FailOrderCommand`. Its `OrderStatus` enum is `PENDING/COMPLETED/FAILED`.
- **`domain/saga/OrderReservationSaga.kt`** — started by `OrderCreatedEvent`. Reserves the
  order's items **strictly sequentially**, so an N-line order costs N saga round trips.
  Compensates with `ReleaseReservationCommand` for each already-reserved line on failure.
  Dispatches on a separate 64-thread executor so the processor thread never blocks on
  aggregate locks. Every `commandGateway.send` has a failure disposition: a command that
  fails for good reaches `abandon()`, which releases what was already reserved and sends
  `FailOrderCommand`; the resulting `OrderFailedEvent` comes back to an `@EndSaga` handler.
  That indirection is required — `SagaLifecycle` resolves the current saga from a ThreadLocal
  bound to the processor's unit of work, so it cannot be touched from a pool thread. Emits
  `saga.completed{outcome}`, `saga.lifetime{outcome}` and `saga.command.failed{stage}`.
- **`config/AxonConfig.kt`** — `JdbcEventStorageEngine` over the `domain_event_entry` /
  `snapshot_event_entry` tables, wrapped by `TimedEventStorageEngine`.
- **`config/ConcurrencyRetryScheduler.kt`** — retries `ConcurrencyException` only;
  5 attempts, `25ms * 2^n` capped at 500 ms. **The retry pool TIMES the backoff; it does not
  execute the retry** — see below.
- **`config/CommandGatewayConfig.kt`** — the two pools and the connection budget they are
  derived from.
- **`repository/`** — Spring Data JDBC `CrudRepository` read models: `InventoryProjection`
  (`inventory_state`) and `OrderProjection` (`orders`).
- **`subscription/`** — `InventoryProjectionUpdater`, `OrderProjectionUpdater` (tracking
  event processors writing the read models), `MockKafkaPublisher`.

Config lives in `src/main/resources/application.yaml`. Flyway migrations in
`classpath:db/migration`. Env overrides: `DB_JDBC_URL`, `DB_USER`, `DB_PASSWORD`,
`API_REPLICAS`, `AXON_JDBC_POOL_SIZE`.

Swagger UI at `/swagger-ui.html` · Grafana `http://localhost:3000` ·
Prometheus `http://localhost:9090`.

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
response time is admission latency only. Out-of-stock surfaces as `status=REJECTED` on the
order projection, never as an HTTP error. The only exception thrown anywhere in the app is
`ItemAlreadyExistsException`, from `POST /inventory`.

### Benchmarking

`ES-1` carries the full harness — `bench.env`, `docker-compose.bench.yml` and `k6/bench/` —
as of 2026-08-06. It was the last pair of branches (with `ES-3`) still on the legacy
`k6/run.sh` + `k6/reserve-load-test.js` path, and `common.sh` no longer hard-fails here.

```bash
SCENARIO=steady RATE=60 DURATION=10m ./k6/bench/bench.sh
python3 k6/bench/compare.py bench-results/*_steady_*
```

Everything under `k6/` and `docker-compose.bench.yml` is byte-identical across all the
variant branches, with `ES-4` as the reference; `bench.env` is the only per-branch file, and
its `IMAGE_TAG` (`inventory-reservation-es-1-nulllock:latest`) is unique per variant so
building this branch cannot overwrite a sibling's image — least of all `ES-1`'s, which it
would otherwise be measured against.

To benchmark every variant as a set rather than this one alone, use the entry point on
`main`: `scripts/build-images.sh` then `scripts/run-suite.sh`. It runs each variant from its
own git worktree using that branch's own harness.
