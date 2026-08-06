# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

**ES is still not multi-node write-*correct*.** The only thing serialising writers to an
`InventoryItem` is a JVM-local `LockFactory`, so a second JVM removes it: two replicas load
the same aggregate at sequence N and both append at N+1, leaving the
`(aggregate_identifier, sequence_number)` unique constraint as the backstop. Horizontal
write scale-out cannot be demonstrated without real distributed concurrency control.

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
runs as a contention study, not as a throughput result. On any single-node run
`saga_completed_total{outcome="command_failed"}` should be zero — the JVM-local
`LockFactory` (Axon's default is pessimistic) prevents the `23505` entirely there. A non-zero
value falsifies that assumption and means the baseline needs re-examining.

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

## Architecture

Kotlin 2.3 / Spring Boot 4.0.6, **Spring MVC on Tomcat** (blocking servlet stack — not
WebFlux), **Axon Framework 4.11.2** with a **JDBC event store on PostgreSQL**. There is no
KurrentDB, no R2DBC and no coroutine code on this branch.

`ES-3` is the **optimistic-lock, cached** variant: it keeps Axon's default optimistic
concurrency on `InventoryItem` and puts a `WeakReferenceCache` in front of the
event-sourcing repository. `ES-4` is the same domain under a `PessimisticLockFactory` with a
custom copy-on-write cache; that pair is the lock comparison the thesis draws.

- **`controller/InventoryController.kt`** — `GET /inventory`, `GET /inventory/{itemId}`,
  `POST /inventory`, `POST /inventory/orders`, `GET /inventory/orders/{orderId}`.
  There is no `POST /inventory/reserve`; standalone reservation was removed.
- **`domain/InventoryItem.kt`** — aggregate. `CreateItemCommand`, `SagaReserveItemCommand`
  (emits `InventoryReservedEvent` or, on insufficient stock, a *persisted*
  `InventoryReservationFailedEvent` — not an exception), `ReleaseReservationCommand`.
  Registered **once**, via `@Aggregate(snapshotTriggerDefinition = ..., cache = ...)`. Do not
  add a second `configureAggregate(InventoryItem)` — that produced two registrations whose
  command handlers were resolved last-wins, making the cache depend on Spring init order.
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
- **`config/AxonCustomizerConfig.kt`** — processor topology, the saga's per-node segment
  claim, and the `inventoryItemCache` bean.
- **`config/ConcurrencyRetryScheduler.kt`** — retries `ConcurrencyException` only;
  5 attempts, `25ms * 2^n` capped at 500 ms.
- **`subscription/`** — `InventoryProjectionUpdater`, `OrderProjectionUpdater` (tracking
  event processors writing the read models), `MockKafkaPublisher`, and `ReserveMetricsCounter`
  (see below).

Config lives in `src/main/resources/application.yaml`. Flyway migrations in
`classpath:db/migration`. Env overrides: `DB_JDBC_URL`, `DB_USER`, `DB_PASSWORD`,
`API_REPLICAS`, `AXON_JDBC_POOL_SIZE`.

Swagger UI at `/swagger-ui.html` · Grafana `http://localhost:3000` ·
Prometheus `http://localhost:9090`.

### `reserve-metrics` is a SUBSCRIBING processor, and that has consequences

`ReserveMetricsCounter` runs synchronously on the thread that published the event, inside the
command's unit of work at `PREPARE_COMMIT` — i.e. *after* the INSERT and *before* COMMIT,
while the aggregate lock is still held. Two things follow.

**It can only over-count, never under-count a conflict.** A `23505` is raised by
`appendEvents`, which runs before subscribers, so a lost write race never increments it. But
any rollback between the INSERT and the COMMIT leaves the counter incremented for an event
that does not exist. Rare, and in the harmless direction. It also counts
`InventoryReservedEvent` only, so on the compensation path it overstates net reserved stock
by the number of released lines.

**It widens the reserve command's failure surface, and that surface is now terminal.** An
exception thrown *inside* the `@EventHandler` is swallowed by Axon's default
`LoggingErrorHandler`, so the command still succeeds. But a failure at the *processor* level
propagates out of `prepareCommit`, rolls back the command, and now reaches the saga's
`abandon()` path — a `REJECTED` order. Today the handler body is one `Counter.increment()`,
so this is structural. **Do not put real work (a DB write, an HTTP call) in this processing
group**: it would become a live rejection source at `REPLICAS=1`, where the Scaling section
above says `saga_completed_total{outcome="command_failed"}` must be zero.

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

### Config knobs that matter

`snapshot.event-count` (30), `cache.enabled`, `axon.saga.total-segments` (60),
`axon.saga.replicas` (`${API_REPLICAS:1}`), `axon.jdbc.pool.size` (300),
`axon.eventstore.*`, and the Micrometer `distribution` block.

**`axon.saga.total-segments` is 60 on every ES branch** and must stay that way — it is the
fixed segment pool that `ceil(total-segments / replicas)` divides, and 60 splits evenly for
2/3/4/5/6 replicas. Changing it requires resetting the `order-saga` tokens
(`TRUNCATE token_entry`).

**`axon.jdbc.pool.size` must exceed the saga per-node claim.** At the old 150 with a
60-thread claim the pool ran dry (`active=150, waiting=97`) and sagas that failed to
dispatch were never retried, stranding orders in `PENDING` forever.

**`order.e2e.time` histogram bounds must stay identical on every branch**
(`minimum-expected-value: 1ms`, `maximum-expected-value: 10m`). Micrometer's default Timer
max is 30 s; when a branch omits these, every sample above 30 s collapses into `+Inf` and
`histogram_quantile` reports ~30 s, making a saturated variant look *faster* than a healthy
one.

### Benchmarking

`ES-3` carries the full harness — `bench.env`, `docker-compose.bench.yml` and `k6/bench/` —
as of 2026-08-06. It was the last pair of branches (with `ES-1`) still on the legacy
`k6/run.sh` + `k6/reserve-load-test.js` path, and `common.sh` no longer hard-fails here.

```bash
SCENARIO=steady RATE=60 DURATION=10m ./k6/bench/bench.sh
python3 k6/bench/compare.py bench-results/*_steady_*
```

Everything under `k6/` and `docker-compose.bench.yml` is byte-identical across all eight
variant branches, with `ES-4` as the reference; `bench.env` is the only per-branch file, and
its `IMAGE_TAG` (`inventory-reservation-es-3:latest`) is unique per variant so building this
branch cannot overwrite a sibling's image.

To benchmark every variant as a set rather than this one alone, use the entry point on
`main`: `scripts/build-images.sh` then `scripts/run-suite.sh`. It runs each variant from its
own git worktree using that branch's own harness.

The `saga_completed{outcome}` / `saga_command_failed{stage}` split that the Scaling section
calls the primary diagnostic is now collected automatically — it is in `queries.promql`, so
it lands in `dump.json` on every run, and `python3 k6/bench/compare.py --cols saga <run-dirs>`
tabulates it. Reading it off `/actuator/prometheus` or Grafana by hand is no longer the only
route.
