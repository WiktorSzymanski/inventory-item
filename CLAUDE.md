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
REPLICAS=3 PG_MAX_CONNECTIONS=1000 docker compose up -d
```

- **Never also pass `--scale`.** Mixing `--scale` with `deploy.replicas` makes Compose
  remove middle replicas.
- `REPLICAS` drives *both* the container count and `API_REPLICAS`, which on ES branches
  sets the saga per-node claim to `ceil(axon.saga.total-segments / replicas)`. If they
  diverge, segments are left unclaimed and those orders are never processed.
- `PG_MAX_CONNECTIONS` must rise with `REPLICAS` — ~200 connections per ES replica
  (Hikari 50 + Axon 150). The default `300` is the historical single-node value that all
  existing results assume; `1000` covers ~4–5 replicas.
- Containers are named `<project>-api-es-N`, **not** `api-es`, even at `REPLICAS=1`. Use
  `docker compose logs api-es` / `docker compose exec api-es` (service name), not
  `docker logs api-es`. Any cadvisor query must match `name=~".*api-es.*"`.
- Prometheus discovers the API through `dns_sd_configs`, so a replica count change needs no
  config edit.
- **TO branches are infrastructurally scalable but not domain-verified at `REPLICAS>1`**:
  Spring Modulith's republication poller runs on every node, so orphaned `event_publication`
  rows may be republished more than once. Do not publish TO scale-out numbers without
  checking this.

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
- **`domain/OrderAggregate.kt`** — `CreateOrderCommand` / `CompleteOrderCommand` /
  `FailOrderCommand`. Its `OrderStatus` enum is `PENDING/COMPLETED/FAILED`.
- **`domain/saga/OrderReservationSaga.kt`** — started by `OrderCreatedEvent`. Reserves the
  order's items **strictly sequentially**, so an N-line order costs N saga round trips.
  Compensates with `ReleaseReservationCommand` for each already-reserved line on failure.
  Dispatches on a separate 64-thread executor so the processor thread never blocks on
  aggregate locks.
- **`config/PessimisticCachingRepository.kt`** — copy-on-write cache in front of the
  event-sourcing repository. Strong-reference `ConcurrentHashMap`, **never evicted**; a
  cache hit skips stream replay entirely. Every load deep-copies the aggregate via Jackson.
- **`config/ConcurrencyRetryScheduler.kt`** — retries `ConcurrencyException` only;
  5 attempts, `25ms * 2^n` capped at 500 ms.
- **`subscription/`** — `InventoryProjectionUpdater`, `OrderProjectionUpdater` (tracking
  event processors writing the read models), `MockKafkaPublisher`.

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
`axon.saga.total-segments` (60), `axon.saga.replicas` (`${API_REPLICAS:1}`),
`axon.jdbc.pool.size` (150), and the Micrometer `distribution` block.

**`axon.saga.total-segments` is 60 on every ES branch** and must stay that way — it is the
fixed segment pool that `ceil(total-segments / replicas)` divides, and 60 splits evenly for
2/3/4/5/6 replicas. ES-1/2/3 previously used `segments: 32`, so single-node results
produced before that change are not comparable to later ones. Changing it requires
resetting the `order-saga` tokens (`TRUNCATE token_entry`, which `k6/bench/reset.sh` does).

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

**The harness only exists on `ES-2` and `ES-4`.** `TO-1`..`TO-4`, `ES-1` and `ES-3` still
carry the legacy `k6/run.sh` + `k6/reserve-load-test.js` and have no `bench.env`,
`docker-compose.bench.yml` or `k6/bench/` — `common.sh` hard-fails there. Rolling the
harness out to them is a separate job.

On the branches that do have it, everything under `k6/` and `docker-compose.bench.yml` is
**byte-identical**; `bench.env` is the only per-branch file. Verify against `ES-2` (the
`harness-v1` ref older comments name does not exist in this repository):

```bash
git diff --stat ES-2 <branch> -- k6 docker-compose.bench.yml   # must be empty
```

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
