# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew bootRun          # Start server on port 8080
./gradlew test             # Run all tests (JUnit 5)
./gradlew build            # Full build
./gradlew bootJar          # Build executable JAR → build/libs/app.jar
docker-compose up          # Start postgres + KurrentDB + api containers
```

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

That run used the `k6/bench/` harness, which is tracked only on `ES-2` and `ES-4`. **This
branch does not have it** — it still carries the legacy `k6/run.sh` +
`k6/reserve-load-test.js`, and no `bench.env` or `docker-compose.bench.yml`, so there is no
`bench.sh` to run here. (A `k6/bench/` directory left behind untracked by a branch switch is
not the harness; `git status` will show it as `??`.) The code fix is shared across all four
branches; the measurement is not, and this branch has never been measured at `REPLICAS>1`.

**The contention-vs-stock split is not exhaustive.** `saga_completed{outcome="command_failed"}`
separates contention-driven rejections from genuine out-of-stock ones on the *reservation*
path only. On the completion path `SagaLifecycle.end()` runs synchronously, before the
`CompleteOrderCommand` future resolves, so the saga's `orderId` association is already gone
when the resulting `OrderFailedEvent` arrives. The order still ends up `FAILED`/`REJECTED`
(`FailOrderCommand` targets the aggregate directly), but that saga stays tagged
`outcome="completed"`. `saga_command_failed_total{stage="complete"}` is the only signal for
that path — read it alongside the outcome split, never instead of it.

**`REPLICAS=1` is still the measurement-grade configuration**, because at `REPLICAS>1` the
rejection rate is an artefact of lost write races rather than of stock. Read multi-replica
runs as a contention study, not as a throughput result. On any single-node run
`saga_completed_total{outcome="command_failed"}` should be zero — the JVM-local
`LockFactory` (Axon's default is pessimistic) prevents the `23505` entirely there. A non-zero
value falsifies that assumption and means the baseline needs re-examining.

**TO is unchanged by this work.** `InventoryService.processOrder` is `@Retryable`
on `OptimisticLockingFailureException` (4 attempts) and, on exhaustion, issues
`FailOrderCommand` — so a lost race becomes a `FAILED` order there too. TO-1/TO-2
additionally claim each `event_publication` row with a database-level
`UPDATE … WHERE completion_date IS NULL`, so only one replica delivers; TO-3/TO-4 have no
such guard and rely on stock Modulith republication. None of it is load-tested at
`REPLICAS>1`.

Do not publish either family's scale-out numbers as throughput results: on ES the extra
rejections are contention, and TO's multi-node path has never been load-tested at all.

### Known gap on `ES-1`: no `order.e2e.time` histogram configuration

Unrelated to the multi-node work and deliberately left out of its scope. This branch's
`src/main/resources/application.yaml` has **no** `order.e2e.time` entry anywhere in the
Micrometer `distribution` block — no `percentiles-histogram: true`, no
`minimum-expected-value`, no `maximum-expected-value`. Every other branch sets
`minimum-expected-value: 1ms` and `maximum-expected-value: 10m` for it.

The consequence is the classic trap: Micrometer's default Timer maximum is 30 s, so every
sample above 30 s collapses into the `+Inf` bucket and `histogram_quantile` reports ~30 s —
which makes a saturated `ES-1` look *faster* than a healthy branch. **Treat any `ES-1`
end-to-end latency number as suspect until those bounds are added**, and do not put `ES-1`
latency into a TO-vs-ES table without fixing this first.

## Architecture

Kotlin 2.3 / Spring Boot 3.5 REST API on Netty via Spring WebFlux. **Event Sourcing** implementation: KurrentDB is the source of truth (append-only event log per aggregate); PostgreSQL holds a projection (read model) updated by a KurrentDB persistent subscription.

- **`controller/InventoryController.kt`** — `@RestController`; `GET /inventory`, `GET /inventory/{itemId}`, `POST /inventory`, `POST /inventory/reserve`
- **`service/InventoryService.kt`** — retry loop (5 attempts, exponential backoff with jitter); catches `WrongExpectedVersionException` for optimistic concurrency
- **`service/command/CreateItemCommandHandler.kt`** — appends `InventoryCreatedEvent` to KurrentDB with `StreamState.noStream()` (throws `ItemAlreadyExistsException` on conflict)
- **`service/command/ReserveItemCommandHandler.kt`** — loads aggregate by replaying KurrentDB stream, appends `InventoryReservedEvent` with exact stream revision
- **`repository/EventStoreRepository.kt`** — `loadAggregate(itemId)` reads and replays KurrentDB stream; `appendEvent(...)` appends with expected revision
- **`repository/InventoryRepository.kt`** / **`InventoryProjection.kt`** — R2DBC read model; `inventory_state` table is the PostgreSQL projection
- **`subscription/InventoryProjectionSubscriber.kt`** — `ApplicationRunner`; creates and subscribes to a KurrentDB persistent subscription group (`inventory-projection-group`) on `$all`; updates the PostgreSQL projection on each event
- **`config/KurrentDbConfig.kt`** — `KurrentDBClient` and `KurrentDBPersistentSubscriptionsClient` beans
- **`config/KurrentDbProperties.kt`** — `@ConfigurationProperties("kurrentdb")` for `connectionString`

Config lives in `src/main/resources/application.yaml`. Flyway migrations in `classpath:db/migration`. Env overrides: `DB_JDBC_URL`, `DB_R2DBC_URL`, `DB_USER`, `DB_PASSWORD`, `KURRENTDB_URL`.

KurrentDB UI: `http://localhost:2113`. API docs: Swagger UI at `/swagger-ui.html`.

### Stream naming

One KurrentDB stream per aggregate: `inventory-{itemId}`. Events: `InventoryCreatedEvent` (revision 0), `InventoryReservedEvent` (revision ≥ 1).

### Coroutine rules

- Never use `runBlocking` or `.block()` in controllers, services, or repositories.
- All blocking KurrentDB calls (CompletableFuture `.get()`) must be inside `withContext(Dispatchers.IO)`.
- `runBlocking` is allowed in `InventoryProjectionSubscriber.handleEvent()` because the KurrentDB subscription listener callback runs on a non-coroutine thread.
