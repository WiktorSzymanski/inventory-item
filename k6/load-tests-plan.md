# Thesis-grade load-test harness for TO vs ES variants

> **Status — implemented on `ES-3-pesimistic`, 2026-07-27.** The design below is what was
> built; it is kept as the rationale record, so it still reads in the future tense.
> Outstanding work:
>
> - **F1 is applied to this branch only.** Until the `order.e2e.time` bounds are
>   replicated on the other 10 branches, no TO-vs-ES latency comparison is valid.
> - **`bench.sh` has not yet been run end-to-end** (verification step 5). Every component
>   is tested in isolation — all 45 PromQL queries against live Prometheus, all 8 scenario
>   profiles under both k6 v2.0.0 and the pinned v1.1.0 container, and the
>   dump → evaluate → compare chain against real historical data — but the full orchestrated
>   run has not been executed, because it resets the database and rebuilds the image.
> - **Cross-branch rollout has not started.**
>
> Two deviations from the plan as approved, both narrowing per-branch divergence:
> the dashboard now uses a `$job` template variable, so `the-dashboard.json` no longer
> needs per-branch editing and `application.yaml` is the only file that does; and
> `queries.promql` uses a `scalar_s` kind for window-start values, because PromQL binds
> `@` to selectors rather than aggregations (`sum(foo) @ T` is a parse error).

## Context

The thesis compares Traditional Ownership (`TO-1..4`) against Event Sourcing (`ES-1`, `ES-2`, `ES-3` + 5 sub-variants) across 11 branches. The current harness cannot support that comparison, and four defects mean **every number collected so far is unusable**:

1. **The workload differs per branch.** `TO-1` ramps 10→200 rps over 5m with 1 item × 1 MiB; `TO-4` is `constant-vus` VUS=10; `ES-1`/`ES-3-optimistic` ramp to 300 over 10m; the working tree is 100→600 over 5m with 6 items × 0 bytes. Different variants have been measured under different loads.

2. **The headline latency number is meaningless.** `POST /inventory/orders` returns **202 Accepted** — reservation is async via the saga. k6's "POST p50 = 5.31 ms" is admission latency. The real number from the same run is `order_e2e_time` p50 = **17.0 s**, p99 = **38.7 s**. The system was deeply saturated and the report read as healthy.

3. **`order.e2e.time` histogram bounds differ between TO and ES — verified.** `src/main/resources/application.yaml` on ES sets `maximum-expected-value: order.e2e.time: 10m`. `TO-1`/`TO-4` set `maximum-expected-value` only for `publish.lag`, so `order.e2e.time` falls back to Micrometer's **30 s** default: every sample above 30 s lands in `+Inf` and `histogram_quantile` clamps. A saturated TO run reports p99 ≈ 30 s and looks *better* than ES's 38.7 s. **Cross-architecture percentiles are currently invalid.** This is the single highest-value fix here.

4. **`postgres-es-data` is a persistent volume, never reset.** Run *N* inherits the event store of runs 1..*N*−1.

Plus: k6's own output is discarded (no `--out`, no `handleSummary`), so `dropped_iterations` — the overload signal — is invisible; and comparison is done by eyeballing Grafana PDFs in a gitignored directory.

**Outcome:** a scenario catalogue runnable identically on all 11 branches, with per-run machine-readable artifacts, an objective PASS/FAIL/INVALID verdict, and a script that renders any set of runs as one thesis table.

**Settled decisions (do not re-litigate):** all four scenario groups; k6 stays fire-and-forget with e2e latency from server-side Prometheus metrics; clean slate per run; per-run artifact dir + compare script.

---

## Blocking correctness fixes

Do these before writing harness code — nothing downstream is valid without them.

**F1. Pin `order.e2e.time` histogram bounds identically on all 11 branches** (`src/main/resources/application.yaml`):
```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        order.e2e.time: true
      minimum-expected-value:
        order.e2e.time: 1ms
      maximum-expected-value:
        order.e2e.time: 10m
```
Pin `minimum` explicitly too, so the bucket set is provably identical rather than accidentally identical.

**F2. Fix stock exhaustion.** Seeded `availableQty: 1000000` with random `quantity = 1..10` (mean 5.5), 4 lines/order over 6 items = ~700 units/s/item at 190 orders/s → **stock hits zero in ~24 minutes**, after which every order flips to `rejected` and a 45m soak is benchmarking the compensation path. Fix: `QTY_PER_LINE=1` **fixed, not random** (the randomness adds variance with no scientific content), `SEED_QTY=2_000_000_000` (`availableQty` is `Int`).

**F3. Drop `GET /inventory?size=100` from the load loop.** It corrupts the offered rate (the old `if (!ok) { sleep(1); return; }` path still counts as an issued iteration in an arrival-rate executor, so a projection hiccup silently reduced write rate while k6 reported the nominal one); it doubles `http_server_requests_seconds_count` so every throughput panel is 2× inflated; and `InventoryService.getItems` runs `findAllBy(pageable)` **plus a separate `count()`** — a full-table count per iteration off the same Hikari pool as the write path. Item IDs are deterministic (`item-1..item-N`), so it is pure confound. Read load returns as an opt-in separate scenario (`READ_RATE`, default 0).

**F4. Constrain the sweep axes.** `slice(0, Math.min(n, allItems.length))` meant past "1 item, 4 per order" runs were silently 1-line orders. Sweep cells must be `(distinct, itemsPerOrder)` pairs with `itemsPerOrder ≤ distinct`; fail fast otherwise, gated by an explicit `ALLOW_DUP_LINES=true` for the deliberate same-aggregate-twice experiment.

**F5. Frame `PAYLOAD_BYTES` correctly.** Verified: `additionalBytes` is only on `InventoryCreatedEvent` (`domain/events.kt:14`, `domain/InventoryItem.kt:29-31`), never on `InventoryReservedEvent`. It does **not** inflate the append path. It inflates (a) snapshot rows — one per 30 events — and (b) `PessimisticCachingRepository.deepCopy`, which runs **twice per command**. It is a copy-on-write-cost and snapshot-IO lever, specific to the ES-3 cache variants. At 1 MiB × ~13 snapshots/s ≈ 47 GB/h — cap payload-sweep runs at 5 min with a `df` precondition.

---

## Design

### Layout

Orchestration runs **on the host**, not in the k6 container. The k6 image is busybox — no `jq`, `psql`, or `curl` — which is exactly why `run.sh` is a two-liner that throws the summary away. Verified present on the host: `python3` 3.12.3, `jq` 1.7, `curl` 8.5.0, `psql` 16.14, `docker` 29.5.2, `k6` v2.0.0. **No new dependencies.** Host sequencing also dissolves the `k6` → `api-es` `depends_on` gap entirely.

```
k6/                                 # byte-identical on all 11 branches
  main.js                           # only entrypoint; dispatches on SCENARIO
  lib/{config,api,workload,profiles,summary}.js
  bench/
    bench.sh                        # host orchestrator — the real entry point
    reset.sh  wait-healthy.sh
    dump.py  evaluate.py  compare.py    # python3 stdlib only
    queries.promql  thresholds.json
    sweep.sh
  run.sh                            # back-compat container shim (see Migration)
docker-compose.bench.yml            # byte-identical override
bench.env                           # THE ONLY per-branch file (~10 lines)
bench-results/                      # tracked JSON artifacts; reports/ stays for PDFs
```

Language policy: **shell** for sequencing docker/psql, **python3 stdlib** for JSON/HTTP/tables. `jq` is painful for `dump.py`'s per-step loop and `compare.py`'s column alignment; python is trivial for both.

**Design invariant:** exactly one file differs per branch — `bench.env`. Acceptance test for rollout is that `git diff --stat harness-v1 <branch> -- k6 docker-compose.bench.yml` is empty on all 11.

### Key modules

- **`lib/config.js`** — env parsing with defaults + `validate()` enforcing F4. Exports `itemIds()` generating `item-1..item-N` deterministically.
- **`lib/workload.js`** — seeded xorshift RNG keyed on `__VU`, so the item-selection sequence is identical across variants for a given `SEED`. Partial Fisher–Yates for line selection — unbiased and O(k), replacing the old `sort(() => Math.random() - 0.5)` (biased, O(n log n), and real k6 CPU at 600 iter/s).
- **`lib/api.js`** — `createItem` / `postOrder` / `getItem` / `listItems`.
- **`main.js`** — sets `discardResponseBodies: true`; `exec` functions `seed` / `order` / `read`. **202 is the only success**; do not branch on 422/409 — both are unreachable on this path (verified: only `ItemAlreadyExistsException` is ever thrown, and only from `POST /inventory`).
- **No k6 `thresholds` on latency.** The verdict lives post-run (see Measurement). Keep only load-generator validity guards.

Do **not** put `additionalBytesSize` in the order body — `TO-*`'s `CreateOrderRequest` has that field and ES's does not. The payload lever stays purely on `POST /inventory`, which both families support identically.

---

## Scenario catalogue

`SCENARIO` selects a profile from `lib/profiles.js`. VU sizing: `preAllocatedVUs = max(50, ceil(peak × 3))`, `maxVUs = max(500, ceil(peak × 25))`. Generous `maxVUs` matters because `dropped_iterations` in an arrival-rate executor means exactly one thing — "no free VU" — so if `maxVUs` binds, you are measuring k6, not the system.

| Scenario | Executor | Config | Wall |
|---|---|---|---|
| **capacity** | `ramping-arrival-rate` | `startRate=20`, 8 steps of +20 → 20,40,…,160/s; each step 15 s ramp + 120 s plateau | 18 m |
| **steady** | `constant-arrival-rate` | `rate=RATE` (0.6 × the *lowest* knee across all variants), `duration=10m` | 10 m |
| **spike** | `ramping-arrival-rate` | `{25,2m} {100,10s} {100,60s} {25,10s} {25,4m}` — base 0.25×knee, spike `SPIKE_FACTOR`×base | 7 m |
| **soak** | `constant-arrival-rate` | `rate = 0.6 × knee`, `duration=45m` | 45 m |
| **warmup** | `shared-iterations` | `iterations=5000`, `vus=50`, `maxDuration=5m` | ≤5 m |
| **seed** | `shared-iterations` | `iterations=1`, `vus=1` | secs |

The staircase starts low deliberately: the reference point (189 rps → e2e p50 17 s) means the ES knee is far below 189, and at `ITEMS_PER_ORDER=4` each order costs 4 *sequential* saga round trips.

**Contention + payload sweeps** are the same `steady` profile with different env, run one-factor-at-a-time from baseline `DISTINCT_ITEMS=6, PAYLOAD_BYTES=0, ITEMS_PER_ORDER=4`. Axes: `DISTINCT_ITEMS` ∈ {1, 6, 100}, `PAYLOAD_BYTES` ∈ {0, 1 MiB}, `ITEMS_PER_ORDER` ∈ {1, 4, 8}. OFAT gives 5 extra runs per variant instead of 17 — the full cross product would be ~66 hours across 11 branches.

### Warmup — separate `k6 run` invocation, fixed-iteration

Not a `startTime`-offset scenario: that pollutes `summary.json` (p99, iterations, checks all merge warmup and measurement). Two invocations give two clean summaries and allow a **settle gap** so the warmup backlog drains before T0 — which matters here because `order-saga` and `order-projection` are TrackingEventProcessors with real lag.

**Fixed-iteration, not fixed-duration**, is the important part: a 60 s duration-based warmup gives a fast variant 3× more warmup events, so variants would enter the measured window with different event-store depth, snapshot count, and cache state — a systematic bias against exactly what is being measured. 5000 orders × 4 lines = 20 000 reservations ≈ 666 snapshot triggers, identical everywhere.

`SETTLE_S` default 60 s, plus a drain-to-zero poll capped at that. `T0 = t_settle_end`; no Prometheus query ever looks before T0.

### Staircase step boundaries

Do not have k6 emit absolute timestamps — it cannot observe the host clock reliably relative to scenario start. Instead: `seed` runs as its own `k6 run` so the measured invocation has an empty `setup()`; `bench.sh` records `T0`/`T1` around `k6 run`; `handleSummary` writes `profile.json` with step **offsets in seconds**; `dump.py` converts to absolute. The ~0.3–1 s init skew is absorbed by **discarding the first 40% of each plateau** (queue transients anyway).

---

## Orchestration — `bench.sh`

```
guard (clean src/, rebuild image, record image id)
  → reset.sh (stop API → TRUNCATE → start API → wait healthy)
  → k6 run SCENARIO=seed
  → k6 run SCENARIO=warmup
  → settle (SETTLE_S + drain poll)          → T0
  → k6 run <SCENARIO> --out experimental-prometheus-rw --tag testid=$RUN_ID
                                            → T1
  → drain poll                              → T2
  → sleep 15 (3 scrape intervals)
  → dump.py → evaluate.py → grafana PDF
```

`bench.sh` aborts if `git status --porcelain src/` is non-empty (unless `ALLOW_DIRTY=1`) and rebuilds unless `SKIP_BUILD=1`. This closes a real footgun: `image: inventory-reservation-es:latest` with no build step means `git checkout ES-1 && docker compose up` silently benchmarks whatever was last built.

### Clean slate — `reset.sh`

**Truncate, don't recreate the volume.** `TRUNCATE` drops the relation files outright, so `pg_database_size_bytes` genuinely resets and becomes a clean per-run "bytes written to disk" measure; recreating forces initdb + Flyway for no benefit. `RESTART IDENTITY` also resets the `BIGSERIAL global_index`, which Axon's `GapAwareTrackingToken` and `createTailToken()` are indexed off.

**Stop the API first — not negotiable.** Truncating a live API lets in-flight commands re-insert rows and TEPs write tokens back into freshly emptied tables. The restart is independently mandatory because `PessimisticCachingRepository.confirmed` is a **never-evicted `ConcurrentHashMap`** holding aggregates that no longer exist in the store; plus TEP tokens are cached in memory, plus JIT profile. Micrometer counters resetting to zero is a *feature*: Δ over a window equals the absolute total, and `resets()` becomes a clean "did the API restart mid-run" validity check.

**Discover tables dynamically** — this is what lets `reset.sh` be byte-identical despite the schemas genuinely differing (ES: `domain_event_entry`, `snapshot_event_entry`, `token_entry`, `saga_entry`, `association_value_entry`, `inventory_state`, `orders`; TO-1: `reservations`, `event_publication`, `inventory_state`, `orders`):

```sql
DO $$ DECLARE t text; BEGIN
  SELECT string_agg(format('%I.%I', schemaname, tablename), ', ') INTO t
    FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history';
  IF t IS NOT NULL THEN EXECUTE 'TRUNCATE TABLE '||t||' RESTART IDENTITY CASCADE'; END IF;
END $$;
```

Health is polled on published `localhost:8080`, which exists on every branch including `ES-3-pesimistic-scaling` (nginx). **Do not reset Prometheus** — all queries are absolutely window-scoped, and retaining history is what makes runs re-analyzable later.

---

## Measurement

### Two windows, not one

`order.e2e.time` is recorded in `OrderProjectionUpdater` on the **order-projection** TEP when it processes the terminal event. Under saturation that processor lags by minutes: an order admitted at T1−10 s produces its e2e *sample* at T1+3 min. Dumping `[T0, T1]` silently drops the slowest tail.

- `window_load = [T0, T1]` — throughput, CPU, HTTP latency, conflict rate, DB growth rate
- `window_full = [T0, T2]` — e2e histogram, outcome counts, total DB growth

### Query strategy — `dump.py` + `queries.promql`

Use **instant queries with the `@` modifier at both endpoints, subtracted client-side**, not `increase()` over a long range. Because the API restarts per run every counter starts at 0, so `expr @ E − expr @ S` is *exact* with no `increase()` edge extrapolation. For histograms the bucket-vector difference gives the exact window-scoped histogram, and `histogram_quantile` over that is the correct run-wide percentile — as opposed to averaging `histogram_quantile(rate(...[1m]))` over time, which is not a percentile of anything. Prometheus 2.52 supports `@`.

**Every expression must be `sum()`-wrapped.** Verified: the `job` label differs per branch (`inventory-to` vs `inventory-es`) and `ES-3-pesimistic-scaling` uses `dns_sd_configs`, so every metric has N `instance` series there. `$JOB`/`$DB`/`$API_CONTAINER_RE` are substituted from `bench.env`, keeping `queries.promql` byte-identical.

Four query kinds:
- **delta** (`@ $S`, `@ $E`, subtract): `http_server_requests_seconds_count` by status for `/inventory/orders`; `order_e2e_time_seconds_{count,sum}` by outcome; `inventory_append_success_total`; `inventory_optimistic_{retry,exhausted}_total`; `inventory_opt_cache_{hit,miss}_total`; `inventory_opt_catchup_total`; `inventory_exception_total` by type; `es_events_processed_total` by eventType
- **hist** (bucket-vector difference at p50/p95/p99): `order_e2e_time` by outcome, `http_server_requests` for POST orders, `projection_lag`, `order_projection_lag`, `state_load_time` by phase, `state_persist_time`, `publish_lag`
- **scalar** (`avg/max_over_time(...[${W}s] @ $E)`): `process_cpu_usage`, `system_cpu_usage`, `jvm_memory_used_bytes{area="heap"}`, `pg_database_size_bytes{datname="$DB"}` at both ends, cadvisor container CPU/RSS
- **range** (`query_range`, `step=5s`): offered/achieved/in-flight/e2e-p95/cpu/heap/db-size/conflict-rate/`k6_dropped_iterations_total` — for plots and soak drift checks

**In-flight orders** is the single best saturation indicator and needs no SLO guess:
```promql
sum(http_server_requests_seconds_count{job="$JOB",uri="/inventory/orders",method="POST",status="202"})
  - sum(order_e2e_time_seconds_count{job="$JOB"})
```

**Knee detection** — highest step satisfying all three: `achieved_202_rate ≥ 0.95 × targetRate`; `e2e_p95_confirmed ≤ SLO` (default 2 s); **`inflight_end ≤ inflight_start × 1.2`**. The third is the real signal — a queue growing monotonically during a constant-rate plateau *is* saturation, by definition.

### Drain time

Primary signal is Postgres, polled from the host — exact, cheap, and identical on both families. Verified: ES uses `CONFIRMED`/`REJECTED` and TO-1 uses `COMPLETED`, but **both schemas default `status` to `'PENDING'`**, so the family-agnostic poll is:

```sql
SELECT count(*) FROM orders WHERE status = 'PENDING'
```

Record `BACKLOG_AT_STOP` at T1, poll every 2 s to zero (cap `DRAIN_TIMEOUT=900`), T2 on exit. Derived — arguably the most informative single number in the harness:

`drain_service_rate = BACKLOG_AT_STOP / DRAIN_SECONDS` — the system's true unloaded terminal-service rate in orders/s, with no offered-rate confound.

If drain times out the run is **INVALID** for e2e purposes (the histogram is truncated), not merely FAIL.

### Verdict — `evaluate.py`

Three states. **`INVALID` is what makes this trustworthy for a thesis**: it separates "the system failed the SLO" from "the measurement was broken."

Validity checks (any failure ⇒ INVALID): `vus_max < maxVUs` (generator not VU-starved); `dropped_iterations/iterations ≤ 0.005`; `min(up) == 1` over the window (no scrape gap); `resets(process_uptime_seconds) == 0` (API didn't restart mid-run); `target_count == EXPECTED_REPLICAS`; `drained == true`; `git_dirty == 0`; `image_built_after_head == true`; and **`completion_ratio = Δorder_e2e_count / Δhttp_202_count ≥ 0.999`**.

That last one closes a survivorship bias: `order_e2e_time` only records orders that reach a terminal event, so a stuck saga is otherwise invisible and the percentile silently improves.

SLO checks (any failure ⇒ FAIL), from `thresholds.json` with `null` disabling: `e2e_p95_confirmed ≤ 2 s`, `e2e_p99 ≤ 5 s`, `non202_ratio ≤ 0.001`, `rejected_ratio ≤ 0.01`, `opt_exhausted == 0`, `projection_lag_p95 ≤ 1 s`, `drain_seconds ≤ 120`. Per-scenario overrides: `capacity` disables the latency SLO (its job is to *find* the knee — failing it on latency is nonsense) and requires a knee; `soak` uses drift ratios computed from the `series` block (e2e p95 last-10min ÷ first-10min ≤ 1.3, heap growth ≤ 1.5).

### Per-run artifacts — `bench-results/<VARIANT>_<SCENARIO>_<ts>/`

`meta.json` (variant, branch, commit, `git_dirty`, image id + created, harness/k6 versions, full resolved config, timeline `t_reset`→`t_drain_end`, both windows, steps, host specs) · `dump.json` (`scalars`/`derived`/`per_step`/`series`) · `verdict.json` · `summary.json` + `profile.json` from k6 · `report.pdf`.

Track these JSONs in git — they are the thesis data and must be versioned alongside the code that produced them. `reports/` stays gitignored for PDFs.

### `compare.py`

```
compare.py [-f md|csv|tsv] [--cols core|latency|resource|es|all] [--baseline DIR] RUNDIR...
compare.py --knee bench-results/*_capacity_*
```
Runs outside Docker, python3 stdlib. Markdown default (paste-ready into the thesis). Column sets: **core** (variant/scenario/rate/achieved/e2e p50-p95-p99/rejected%/drain_s/verdict), **latency**, **resource** (cpu, heap, container, `db_bytes_per_order`), **es** (append_success, opt_retry/exhausted, cache hit%, catchup, state_load/persist). `--baseline` adds Δ% columns — that is what turns the table into a result table rather than a data dump. `--knee` emits the per-step staircase table with the detected knee marked: a second thesis figure straight out of the harness.

---

## Compose, k6 output, dashboard

**`docker-compose.bench.yml`** (byte-identical; only touches services whose names are identical everywhere — `BASE_URL` stays in each branch's own compose since the API service name differs):
- `prometheus.command` — restated in full (a compose list is *replaced*, not merged, which conveniently normalizes the per-branch flag divergence) plus `--web.enable-remote-write-receiver`
- `k6.image: grafana/k6:1.1.0` — **pin it**. Verified drift: host k6 is v2.0.0, `grafana/k6:latest` is v1.1.0. `latest` moving mid-thesis silently changes the load generator between variants.
- k6 RW env: `K6_PROMETHEUS_RW_SERVER_URL`, `_TREND_STATS`, `_PUSH_INTERVAL=5s`, `_STALE_MARKERS=true`

**Enable k6 → Prometheus remote-write** (`--out experimental-prometheus-rw --tag testid=$RUN_ID`). It puts `k6_dropped_iterations_total` and `k6_vus` on the same time axis as server metrics, gives the staircase an *independent* witness of the offered rate, and is the only way to see client-side admission latency alongside per-replica metrics on the scaling branch. Caveats: `testid` is required (k6 RW adds no `job` label); RW trend stats are per-push-interval so `summary.json` stays authoritative for run-wide percentiles; skip native histograms (2.52 support is experimental).

**Dashboard** (`monitoring/grafana/provisioning/dashboards/the-dashboard.json`) — fix panel `id:10` (`uri="/inventory/reserve"` → `/inventory/orders"`; the endpoint was removed, panel has printed "No data" in every recent report), panel `id:5` (drop the dead `kurrentdb_disk_io_bytes_total` target, add `deriv(pg_database_size_bytes[5m])`), the `id:10`/`id:9` `gridPos.y=60` collision, and wrap all unaggregated `rate(inventory_*)`/`es_events_processed_total`/`process_cpu_usage` targets in `sum()`/`avg()` so they render as one line on the scaling branch. Add: **in-flight orders**, **offered vs achieved vs dropped**, **outcome mix** (catches F2 instantly), **cache hit ratio**.

**PDF window is a real bug, not cosmetic.** `?from=now-${DURATION}&to=now` means for a 5 m ramp the PDF starts at the *end* of the ramp and never shows the drain. Use absolute epoch-ms bounds of `window_full`: `?from=$((T0*1000))&to=$((T2*1000))`.

---

## Cross-branch rollout

```sh
# Commit on ES-3-pesimistic, split in two:
#   (a) "bench: harness v1"  -> k6/**, docker-compose.bench.yml, bench.env, .gitignore   [branch-agnostic]
#   (b) "metrics: pin order.e2e.time bounds" -> application.yaml, the-dashboard.json     [per-branch]
git tag harness-v1

for b in TO-1 TO-2 TO-3 TO-4 ES-1 ES-2 ES-3 ES-3-optimistic \
         ES-3-WeakRefCache ES-3-WeakRefCache-NullLock ES-3-pesimistic-scaling; do
  git checkout "$b"
  git checkout harness-v1 -- k6 docker-compose.bench.yml
  git rm -q --ignore-unmatch k6/reserve-load-test.js
  # edit bench.env (10 lines) + apply (b) by hand — application.yaml and the dashboard genuinely differ
done
# Verify the invariant — must be empty for all 11:
for b in ...; do git diff --stat harness-v1 "$b" -- k6 docker-compose.bench.yml; done
```

`bench.env` — the only per-branch file: `VARIANT`, `VARIANT_FAMILY`, `API_SVC`, `DB_SVC`, `PROM_JOB`, `API_CONTAINER_RE`, `DB_NAME`, `DB_USER`, `HEALTH_URL`, `EXPECTED_REPLICAS`, `IMAGE_TAG`. For `ES-3-pesimistic-scaling`: `API_CONTAINER_RE=.*api-es.*`, `EXPECTED_REPLICAS=${REPLICAS:-2}`.

(Normalizing all branches to one `job_name` is the cleaner long-term alternative but forces edits to 11 branch-specific dashboards — skip it; `$JOB` substitution costs nothing.)

## Migration

**Delete `k6/reserve-load-test.js`.** Keeping it as a duplicate guarantees someone edits the wrong file — that is exactly how the current 6-way divergence happened. `git show <branch>:k6/reserve-load-test.js` recovers any old profile.

`k6/run.sh` becomes a back-compat shim so `DURATION=5m docker compose --profile load-test up k6` still works: it prints a deprecation banner, runs a `legacy` profile in `profiles.js` reproducing the old ramp shape (but *without* the in-loop `GET /inventory`), and still drops a PDF in `reports/`. Explicitly not thesis-grade — no clean slate, no warmup, no dump, no verdict. `VUS` is ignored except by a `legacy-vus` profile kept for TO-4's old `constant-vus` shape.

**Also fix `CLAUDE.md`** — it still describes KurrentDB + WebFlux + `POST /inventory/reserve`, none of which exists on any current branch. It will keep misleading tooling (it misled the initial exploration here).

---

## Verification

Each step is independently checkable — do not batch.

1. **F1** — `curl -s localhost:8080/actuator/prometheus | grep order_e2e_time_seconds_bucket | tail -3` on a TO branch and an ES branch; the `le` bucket sets must be identical, with the top finite bucket ≈ 600 s on both.
2. **k6 modules** — `k6 run --vus 5 --duration 30s k6/main.js` against a locally running stack (`SCENARIO=steady RATE=10`). Assert: `summary.json` written, `checks` 100%, `dropped_iterations` 0, and exactly one HTTP request per iteration in `http_reqs` (proves F3).
3. **`reset.sh`** — run it, then `SELECT relname, n_live_tup FROM pg_stat_user_tables` must be all-zero, and `/actuator/health` UP.
4. **Remote-write** — after a short run, `curl -s 'localhost:9090/api/v1/query?query=k6_dropped_iterations_total' | jq` must return the `testid` series.
5. **First real run** — `SCENARIO=capacity ./k6/bench/bench.sh` on `ES-3-pesimistic`. This produces the first honest knee number and shakes out timeline anchoring. Sanity-check `dump.json`: `completion_ratio ≥ 0.999`, `inflight` rising across late steps, `drain_service_rate` plausible against `rate_terminal`.
6. **Cross-check the dump against Grafana** — open the run's `window_full` in the dashboard and confirm `e2e_p95` and `achieved_rps` match `dump.json` within a few percent. Do this once; after it passes, the dump is the source of truth and the PDF is illustrative.
7. **`compare.py`** — run `steady` twice on the same branch; the two rows must agree within ~5% on `achieved_rps`, `e2e_p95`, `db_bytes_per_order`. Run-to-run variance above that means the harness isn't controlling something yet, and no cross-variant conclusion is safe until it is.
8. **Rollout invariant** — the `git diff --stat` loop above returns empty for all 11 branches.

## Execution order

1. **F1 first** — nothing else matters until the histogram bounds match.
2. F2–F5 + `k6/lib/*` + `main.js` + `profiles.js`; validate with step 2 above.
3. `reset.sh` + `wait-healthy.sh`; validate with step 3.
4. `docker-compose.bench.yml` + remote-write; validate with step 4.
5. `bench.sh` end-to-end, `SCENARIO=capacity` on `ES-3-pesimistic`; step 5.
6. `dump.py` → `evaluate.py` → `compare.py` against those artifacts; steps 6–7.
7. Dashboard fixes (the dump is now primary; the dashboard is secondary).
8. Fan out to the other 10 branches; step 8.
9. **Campaign:** capacity on all 11 → set global `RATE = 0.6 × min(knee)` → steady on all 11 → OFAT sweeps → spike → soak on the 4–5 headline variants.
