# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A Master's thesis benchmark comparing **Traditional Ownership** (`TO-1`..`TO-4`) against
**Event Sourcing** (`ES-1`, `ES-2`, `ES-3` and its sub-variants) for the same inventory
reservation domain. Each variant lives on its own branch and implements the same HTTP API,
so the branches are meant to be benchmarked against each other under an identical workload.

`main` has no `k6/` directory. The architecture below describes the **ES-3-\*** branches;
`TO-*` branches implement the same API over a classic mutable schema with an outbox.

## Commands

```bash
./gradlew bootRun          # Start on port 8080
./gradlew test             # JUnit 5
./gradlew bootJar          # -> build/libs/app.jar
docker-compose up          # postgres + api + prometheus + grafana + cadvisor
```

Single test class: `./gradlew test --tests "pl.szymanski.wiktor.ApplicationTest"`

Note `JAVA_HOME` in the environment may point at a missing JDK; use
`JAVA_HOME=~/.jdks/corretto-21.0.10` for gradle if the build cannot find a toolchain.

## Architecture (ES-3-pesimistic and siblings)

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
`axon.saga.segments` (32), `axon.jdbc.pool.size` (150), and the Micrometer
`distribution` block.

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

Everything under `k6/` and `docker-compose.bench.yml` is **byte-identical on all 11 variant
branches**; `bench.env` is the only per-branch file. Verify with:

```bash
git diff --stat harness-v1 <branch> -- k6 docker-compose.bench.yml   # must be empty
```

### Gotchas when editing the harness

- **k6 ≥ 2.0 no longer copies system env vars into `__ENV`.** Pass every knob with `-e`.
  The container is pinned to `grafana/k6:1.1.0` so `latest` cannot drift mid-thesis.
- **PromQL `@` binds to selectors, not aggregations.** `sum(foo) @ T` is a parse error;
  `sum(foo @ T)` is valid. Only the `hist` queries use `@`; everything else gets its
  evaluation instant from the API `time` parameter.
- **Always `sum()`-wrap PromQL.** The `job` label differs per branch (`inventory-to` vs
  `inventory-es`) and `ES-3-pesimistic-scaling` scrapes via `dns_sd_configs`, so
  unaggregated expressions return one series per replica there.
- **`additionalBytesSize` only rides on `InventoryCreatedEvent`**, never on
  `InventoryReservedEvent`. It does not inflate the append path — it inflates snapshot rows
  and the per-command Jackson deep copy, i.e. it is a copy-on-write cost lever.

Grafana `http://localhost:3000` · Prometheus `http://localhost:9090` · Swagger `/swagger-ui.html`
