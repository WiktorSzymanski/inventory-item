# TO vs ES measurement campaign — design

> **Status — design approved 2026-08-05, not yet executed.** This document specifies *what
> to measure and why*. It does not specify the harness, which already exists: see
> `k6/README.md` and `k6/bench/`. The companion document `k6/load-tests-plan.md` (deleted in
> the working tree as of this writing) was the *harness* design; this is the *campaign*
> design and replaces nothing in it.

## 1. Purpose and claims

The thesis compares Traditional Ownership (`TO-1`..`TO-4`) against Event Sourcing
(`ES-1`..`ES-4`) over one inventory-reservation domain and one HTTP API. The results
chapter must support four claims:

1. **TO vs ES head-to-head** — which architecture is faster, and where.
2. **Within-family variant effects** — does snapshotting pay for itself, does the aggregate
   cache pay for itself, does NOTIFY/LISTEN beat polling, does pessimistic locking beat
   optimistic.
3. **Behaviour under contention** — how each family degrades as orders converge on fewer
   aggregates.
4. **Operational envelope** — capacity knee, spike recovery, soak drift, resource cost per
   order.

All four are in scope. The matrix in §4 is tiered accordingly: a deep, repeated core for
(1), and targeted cheaper probes for (2)–(4).

### 1.1 What is already known

Two `capacity` runs from 2026-07-28 and 2026-08-04 are `INVALID` on hygiene grounds (see
§3) but the shape of their curves is unambiguous and is what the rate ladder in §4.1 is
derived from. Server-side `order_e2e_time` p95, confirmed orders:

| offered rps | 20 | 60 | 100 | 120 | 140 | 160 | 200 | 220 | 240 | 300 |
|---|---|---|---|---|---|---|---|---|---|---|
| **TO-3** p95 ms | 9.7 | 9.8 | 12.4 | 12.8 | 41.1 | 84.5 | 189 | 364 | 374 | 404 |
| **TO-3** retries | 0 | 0 | 33 | 207 | 2044 | 2515 | 10411 | 21699 | 28408 | 57973 |
| **ES-2** p95 ms | 43.9 | 55.5 | 81.0 | 82.2 | 105 | 179 | 236 | 465 | 11950 | 135713 |
| **ES-2** retries | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

Two facts follow, and they are the spine of the results chapter:

- **TO-3 is ~4.5× faster at low load** (9.7 ms vs 43.9 ms p95 at 20 rps). The event-store
  append plus sequential saga round trips are a constant tax ES pays on every order.
- **ES-2's knee is ~70% higher** (~220 rps vs ~130 rps). TO-3 enters an optimistic-retry
  storm at 140 rps — retries go 207 → 2044 in a single step — while ES-2 stays linear
  through that region with *zero* retries, then collapses off a cliff between 220 and 240.

So there is a **crossover**: TO wins on latency below ~130 rps, ES wins on sustainable
throughput above it. A single-rate comparison would miss this. The ladder exists to find it.

These numbers are indicative only. Nothing from before this campaign is citable.

## 2. Approach

**Fixed-rate grid.** Every variant is measured at the same set of a-priori offered rates,
chosen from §1.1. The alternative considered and rejected was a knee-anchored design
(discover each variant's knee, then measure all variants at one rate derived from the
lowest). The grid was chosen because a rate *ladder* exposes the whole latency-versus-load
relationship, whereas a single anchor point requires a judgment call ("70% of the lowest
knee") that a reviewer can question. Both designs use identical offered rates across
variants; the grid simply fixes them in advance.

The grid's known weakness is that some cells will be saturated for some variants, and
end-to-end latency above the knee is queue delay rather than an architectural property. Two
guardrails address it:

- **The lowest rung (20 rps) must be sub-knee for every variant**, so every variant has at
  least one fully interpretable cell. Night 1 (§5) validates this before the grid runs.
- **Saturation is reported per cell, never hidden.** A saturated cell stays in the table
  with its latency *and* a saturation flag derived from `dropped_iterations`,
  `backlog_drained` and in-flight growth. Dropping such cells would be less objective, not
  more.

**Repetitions.** Every cell in the head-to-head grid, the capacity staircase, the contention
sweep and the order-width sweep runs three times with `SEED=1337`, `2338`, `3339`. Reported
as **median across seeds with observed [min–max]**. The workload RNG is seeded per-VU, so
seeds are genuinely independent samples of the same workload shape. Envelope and payload
probes are n=1 (§4.5, §4.6) for reasons stated there.

**Single node.** All headline results are at `REPLICAS=1`. §4.7 adds a deliberately scoped
multi-replica study framed as contention, not throughput — ES has no distributed
concurrency control (the `LockFactory` is JVM-local) and TO's multi-node outbox path has
never been load-tested, so neither family can produce an honest scale-out number before
submission.

## 3. Validity protocol and prerequisites

Nothing in §4 is worth running until these hold. Of 23 previous runs, exactly one carries a
`PASS` verdict; the failures are overwhelmingly procedural rather than systemic:

| Cause | Runs | Nature |
|---|---|---|
| `git_clean` | 7 | Uncommitted changes under `src/` at run time — the run measured code in no commit |
| `image_fresh` | 3 | Known Docker layer-cache false negative |
| `scrape_up` / `no_api_restart` / `backlog_drained` | 3 | Real: the API died mid-run (`ES-3-WeakRefCache`) |
| `completion_ratio_inverse` | 2 | Real: orders never reached a terminal event |

Exactly one genuinely broken system run in the set. The rest is hygiene, and hygiene is
cheap to fix.

**V1. Commit gate.** No batch starts with anything modified under `src/`. The batch runner
asserts `git status --porcelain -- src/` is empty and aborts before the first reset. Note
that `bench.sh` scopes `git_dirty` to `src/` only, so untracked `bench-results/` and
unrelated working-tree changes do not trip it — but uncommitted application source does,
and correctly so, because such a run is unreproducible.

**V2. Track results.** `bench-results/` is currently untracked *and* absent from
`.gitignore`. Commit it after each night, so every table in the thesis cites a run ID that
resolves to a committed artifact.

**V3. Fix the `image_fresh` false negative.** Docker's layer cache preserves the old
`Created` timestamp, so `image_built_after_head` reads false for a correctly-built image.
Compare the built jar's content against a build of `HEAD` rather than comparing timestamps.

**V4. Port the harness to ES-1 and ES-3.** Both branches lack `k6/bench/`, `bench.env` and
`docker-compose.bench.yml`; `common.sh` hard-fails there. Cherry-pick `k6/` and
`docker-compose.bench.yml` from ES-2 and write `bench.env`. This is not optional: **ES-1 vs
ES-2 is the snapshot hypothesis and ES-3 vs ES-4 is the lock hypothesis**, so without the
port two of the four within-family ES claims are untestable.

Acceptance, on all eight branches:

```bash
git diff --stat ES-2 <branch> -- k6 docker-compose.bench.yml   # must be empty
```

**V4a. Audit metric parity while porting.** On every branch verify that
`src/main/resources/application.yaml` carries `order.e2e.time` with
`minimum-expected-value: 1ms` and `maximum-expected-value: 10m`. Micrometer's default Timer
maximum is 30 s; a branch missing these collapses every sample above 30 s into `+Inf`, so
`histogram_quantile` reports ~30 s and a *saturated* variant looks faster than a healthy
one. This has already silently invalidated a full set of TO-vs-ES latency comparisons once.
Also confirm `axon.saga.total-segments` is 60 on all four ES branches and that
`.persistenceExceptionResolver(SQLStateResolver())` is present.

**V5. Pin the environment.** `REPLICAS=1` and `PG_MAX_CONNECTIONS=600` in `.env` for every
measurement night. No browser, IDE, or gradle daemon during a batch: the host is an
i5-11400F with 12 threads and 31 GB, no CPU or memory limits are set on any container, and
k6 runs on the host — load generator, API, Postgres, Prometheus and cadvisor all share those
12 threads. Record a `docker stats` idle baseline at the start of each night.

**V6. Batch runner.** 361 runs across 8 branches cannot be driven by hand. One script takes
a cell list `(variant, scenario, knobs, seed)`, checks out the branch, rebuilds, runs, and
appends a result line to a manifest. Requirements:

- Crash-resumable — a night that dies at run 14 of 30 must not restart from zero.
- Records failures and continues, rather than aborting the night.
- Groups cells by branch to avoid one image rebuild per run.
- Randomises branch-block order per night (§5.1).

**V7. Rehearsal.** Before committing to the campaign, run one cell per family — TO-3 and
ES-2 at `steady RATE=60` — and confirm both return `PASS`. Two runs, ~35 minutes, and it
proves V1–V6 work.

## 4. The matrix

Common settings unless a subsection overrides them: `REPLICAS=1`, `QTY_PER_LINE=1`,
`SEED_QTY=2000000000`, `WARMUP_ITERATIONS=5000`, `READ_RATE=0`, `PAYLOAD_BYTES=0`,
seeds `1337 / 2338 / 3339`.

### 4.1 Rate ladder — the head-to-head grid

`SCENARIO=steady`, `DISTINCT_ITEMS=6`, `ITEMS_PER_ORDER=4`, `DURATION=10m`,
`RATE ∈ {20, 60, 120, 200}`, all 8 variants, 3 seeds → **96 runs**.

Rungs are derived from §1.1, not invented:

| rung | rationale |
|---|---|
| **20** | Sub-knee for every variant. Isolates the constant architectural tax — the 9.7 ms vs 43.9 ms gap — with no queueing component. |
| **60** | Comfortable mid-load, still sub-knee everywhere. The "normal operation" cell most tables quote. |
| **120** | TO-3's knee edge (retries 33 → 207 between 100 and 120 rps); ES-2 still linear. The crossover region. |
| **200** | Past TO's knee, at ES-2's. Shows the reversal, and shows *how* each family fails — TO by retry storm, ES by cliff. |

`DURATION=10m` rather than 5m because at 20 rps a five-minute window yields ~6,000 orders,
putting the top percentile on ~60 samples. The low rungs are where the architectural tax is
measured most precisely and can least afford a noisy p99.

**Primary output:** Table T1, Figure F1.

### 4.2 Capacity staircase — the continuous curve

`SCENARIO=capacity`, `STEP_START=20`, `STEP_INC=20`, `STEP_COUNT=15` (20 → 300 rps),
`STEP_PLATEAU_S=120`, `DISTINCT_ITEMS=6`, `ITEMS_PER_ORDER=4`, all 8 variants, 3 seeds →
**24 runs**.

The grid gives four clean, mutually independent points; the staircase gives fifteen points
in one run but **accumulates event-store depth as it climbs**, biasing later steps — most
severely on ES-1, where stream depth is the entire phenomenon under study. Run both and say
so in the thesis: the grid is the evidence, the staircase is the shape. `compare.py --knee`
tabulates it directly.

**Primary output:** Table T2.

### 4.3 Contention sweep — the hot-seat story

`SCENARIO=steady`, `RATE=60`, `DURATION=5m`, `ITEMS_PER_ORDER=1`,
`DISTINCT_ITEMS ∈ {1, 2, 8, 32}`, all 8 variants, 3 seeds → **96 runs**.

Holding `ITEMS_PER_ORDER=1` keeps work-per-order constant so aggregate convergence is the
only moving variable. `RATE=60` is sub-knee for every variant at low contention, so anything
that degrades is attributable to contention rather than to saturation.

`DISTINCT_ITEMS=1` places every order on a single aggregate. This is **the only place ES-3
vs ES-4 is observable at all** — with no contention, optimistic and pessimistic locking are
indistinguishable code paths.

Metrics of interest beyond latency: `opt_retry`, `opt_exhausted`, `rejected_ratio`,
`OptimisticLockingFailureException` (TO), and on ES the
`saga_completed{outcome}` split plus `saga_command_failed{stage}`. Note that on the
completion path a saga stays tagged `outcome="completed"` even when `CompleteOrderCommand`
fails, so `saga_command_failed{stage="complete"}` must be read alongside the outcome split,
never instead of it.

**Primary output:** Table T3.

### 4.4 Order-width sweep — the saga fan-out cost

`SCENARIO=steady`, `DURATION=10m`, `DISTINCT_ITEMS=32`, `ITEMS_PER_ORDER ∈ {1, 2, 4, 8}`,
**line rate held constant at 80 lines/s** so `RATE ∈ {80, 40, 20, 10}`, all 8 variants,
3 seeds → **96 runs**.

80 lines/s rather than a rounder 60: `CONFIG.rate` is parsed with `parseInt` and `timeUnit`
is hardcoded `'1s'`, so fractional rates are unrepresentable. 60 lines/s would need
`RATE=7.5` at N=8, and rounding to 8 would give 64 lines/s — silently breaking the
constant-line-rate control the experiment depends on. 80 divides exactly by all four widths,
and 80 rps at N=1 is comfortably sub-knee for TO-3 (p95 11.4 ms, 3 retries at that rate).

`DURATION=10m` rather than the 5m used in §4.3: at `RATE=10` a five-minute window is only
3,000 orders, too thin for a stable p99.

`OrderReservationSaga` reserves an order's lines **strictly sequentially**, so an N-line
order costs N saga round trips; TO reserves all N lines inside one transaction. Holding
*line* rate constant rather than *order* rate separates per-order coordination overhead from
raw work — otherwise a wider order would simply be more work and the comparison would be
confounded.

**Hypothesis:** ES end-to-end latency scales approximately linearly in N; TO stays
comparatively flat. If it holds, this is a structural property of the saga pattern rather
than a tuning artefact, and it is the sharpest single ES-vs-TO result available from this
system. If it does not hold, that is equally worth reporting.

Report both per-order and per-line latency. `DISTINCT_ITEMS=32` keeps contention low so the
fan-out effect is not confounded with lock contention.

**Primary output:** Figure F2.

### 4.5 Envelope — soak and spike

`SCENARIO=soak` (`SOAK_DURATION=45m`, `RATE=60`) and `SCENARIO=spike`
(`SPIKE_BASE=25`, `SPIKE_FACTOR=4`), `DISTINCT_ITEMS=6`, `ITEMS_PER_ORDER=4`, all 8
variants, **1 seed** → **16 runs**.

Soak is where the **snapshot hypothesis (ES-1 vs ES-2) resolves**. With `DISTINCT_ITEMS=6`
and 5,000 warmup orders at 4 lines each, every aggregate already carries roughly 3,300
events before the measured window opens. ES-1 replays that entire stream on every command
and the cost grows monotonically; ES-2 snapshots every 30 events. The expectation is that
ES-1's p95 climbs across the 45 minutes while ES-2 stays flat — which is exactly what
`evaluate.py`'s `max_e2e_p95_drift_ratio` (limit 1.3) measures.

n=1 because drift is a within-run trend measured against the run's own first decile, not a
between-run mean.

Spike measures recovery: `max_recovery_seconds` (limit 180), how long after the burst the
in-flight backlog returns to its pre-spike level.

**Primary output:** Table T6.

### 4.6 Payload sweep — copy-on-write and snapshot cost

`SCENARIO=steady`, `RATE=60`, `DURATION=5m`, `PAYLOAD_BYTES ∈ {0, 4096, 65536}`, on TO-3,
TO-4, ES-2, ES-3, ES-4, 1 seed → **15 runs**.

`additionalBytesSize` rides only on `InventoryCreatedEvent`, never on
`InventoryReservedEvent`, so it does **not** inflate the append path. It inflates snapshot
rows and the per-command Jackson deep copy — making it a targeted lever on precisely the
caching variants. Restricted to five variants for that reason; ES-1 has neither snapshots
nor cache so the axis is meaningless there.

Check `df` before the batch and cap runs at 5m.

**Primary output:** Table T5 (resource section).

### 4.7 Multi-node contention study

`SCENARIO=steady`, `RATE=60`, `DURATION=5m`, `REPLICAS ∈ {1, 2, 3}`, on ES-4 and TO-3,
3 seeds → **18 runs**.

`PG_MAX_CONNECTIONS` must rise with `REPLICAS` (~350 per ES replica; 600 default, add ~350
per additional replica) and `API_REPLICAS` must equal `REPLICAS` or saga segments go
unclaimed and those orders are never processed. Both are driven by the single `REPLICAS`
knob in `.env` — never also pass `--scale`.

**Framing in the thesis is not optional.** This section reports degradation under lost write
races: rejection rate, `inventory_optimistic_retry_total`,
`inventory_optimistic_exhausted_total`, and the saga terminal path. It is never presented as
scale-out throughput. On ES the only thing serialising writers to an `InventoryItem` is a
JVM-local `LockFactory`, so a second JVM removes it entirely; the extra rejections are
contention artefacts. TO-3 and TO-4 additionally lack the database-level
`event_publication` claim guard that TO-1/TO-2 have, and TO's multi-node path has never been
load-tested at all.

Note that `evaluate.py` skips the `saga_command_failed_single_node` validity check whenever
`EXPECTED_REPLICAS > 1`, because above one replica that count is the contention signal
itself rather than a falsified assumption.

**Primary output:** Table T7, explicitly caveated.

### 4.8 Totals

| § | Experiment | Runs | Hours |
|---|---|---|---|
| 4.1 | Rate ladder grid | 96 | 25.6 |
| 4.2 | Capacity staircase | 24 | 16.0 |
| 4.3 | Contention sweep | 96 | 17.6 |
| 4.4 | Order-width sweep | 96 | 25.6 |
| 4.5 | Soak + spike | 16 | 8.5 |
| 4.6 | Payload sweep | 15 | 2.8 |
| 4.7 | Multi-node | 18 | 3.3 |
| | **Total** | **361** | **99.4** |

Per-run wall time assumed: `steady 5m` ≈ 11 min, `steady 10m` ≈ 16 min, `capacity` (15
steps) ≈ 40 min, `soak` ≈ 51 min, `spike` ≈ 13 min — each including reset, seed, warmup,
drain and dump.

## 5. Schedule

Capacity runs come **first**, at n=1, before the grid. ES-1 is the campaign's largest
unknown: it replays ~3,300 events per aggregate on every command with no snapshots and no
cache, and if its knee sits at 30 rps then three of the four ladder rungs are saturated for
it. One five-hour night reveals that before three nights are spent on it.

| Night | Content | Runs | Hours |
|---|---|---|---|
| Prep | V1–V6, ES-1/ES-3 port, V7 rehearsal | 2 | 0.6 |
| 1 | Capacity, all 8, seed 1337 — **ladder validation gate** | 8 | 5.3 |
| 2–4 | Rate ladder grid (§4.1) | 96 | 25.6 |
| 5–6 | Contention sweep (§4.3) | 96 | 17.6 |
| 7–9 | Order-width sweep (§4.4) | 96 | 25.6 |
| 10–11 | Capacity, seeds 2338 and 3339 (§4.2) | 16 | 10.7 |
| 12 | Soak + spike (§4.5) | 16 | 8.5 |
| 13 | Payload (§4.6) + multi-node (§4.7) | 33 | 6.1 |
| 14+ | Slack: re-runs, failed cells, follow-ups | — | — |

Thirteen nights of runtime at roughly 8.5 h each, starting from a 2026-08-05 design date and
a September submission — about two and a half weeks of slack for re-runs.

**Night 1 gate.** Two thresholds. If any variant's knee falls below **20 rps**, the grid's
lowest rung is saturated for it and the ladder needs a lower rung (10 rps) before Night 2.
If any variant's knee falls below **80 rps**, the fixed rates in §4.3 (60 rps) and §4.4
(80 lines/s) are saturated for it, and those sweeps need their rate lowered for all variants
— they must stay identical across variants to remain controlled. Do not proceed to the grid
on an unvalidated ladder.

### 5.1 Run ordering

Randomise branch-block order within each night. An i5-11400F under nine hours of sustained
load will thermal-drift; if every night runs TO-1 → TO-4 → ES-1 → ES-4 in the same order,
drift correlates with variant and becomes a systematic bias indistinguishable from an
architectural effect. Cells within a branch stay grouped so the image is rebuilt once per
branch per night. Record `system_cpu_usage` and check for a within-night trend during
analysis.

## 6. Analysis

**Aggregation.** Group runs by cell — `(variant, scenario, rate, distinctItems,
itemsPerOrder, payloadBytes, replicas)` — and report **median across seeds with observed
[min–max]**. `compare.py` currently renders one row per run directory with no aggregation,
so a 96-run grid renders as 96 rows. It needs an `--aggregate` mode. This is campaign
tooling work, not harness work.

**Saturation flag.** Every cell carries a flag derived from `dropped_iterations`,
`backlog_drained` and in-flight growth. Saturated cells appear in tables with their latency
and the flag. They are never silently dropped.

**Two derived metrics to add to `dump.py`.** Both are headline TO-vs-ES numbers, both come
from series already collected in `queries.promql`, and neither has been computed yet:

- **Bytes of storage per order** = `(db_size_end − db_size_start) / orders_accepted`. The
  direct cost of ES appending events forever versus TO mutating rows in place. `db_size_start`
  and `db_size_end` already exist as `pg_database_size_bytes` scalars.
- **CPU-seconds per order** = `container_cpu / achieved_rps`, from
  `container_cpu_usage_seconds_total`.

**Verdicts.** `evaluate.py` returns `PASS` / `FAIL` / `INVALID`. `INVALID` means the
measurement was broken and the run is excluded and re-run; `FAIL` means the system missed an
SLO and the run is **kept** — a variant failing its SLO at 200 rps is a result, not an
error.

## 7. Thesis outputs

| ID | Content | Source |
|---|---|---|
| T1 | Head-to-head grid: 8 variants × 4 rates, e2e p50/p95/p99, achieved rps, rejection % | §4.1 |
| T2 | Capacity knee per variant | §4.2 |
| T3 | Contention: latency, retries, exhaustion, rejections vs `DISTINCT_ITEMS` | §4.3 |
| T4 | Within-family deltas: ES-1→2 (snapshots), ES-2→3/4 (cache), ES-3↔4 (lock), TO-3→4 (cache), TO-1/2/3 (delivery mechanism, via `publish.lag`) | §4.1, §4.3 |
| T5 | Resource cost per order: bytes, CPU-seconds, heap, plus payload sweep | §4.1, §4.6 |
| T6 | Soak drift and spike recovery | §4.5 |
| T7 | Multi-node contention, caveated | §4.7 |
| **F1** | **e2e p95 vs offered rate, all 8 variants** — the crossover figure | §4.1, §4.2 |
| **F2** | **e2e p95 vs order width** — TO flat vs ES linear, the saga fan-out figure | §4.4 |

F1 and F2 carry the argument. Everything else supports them.

## 8. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | ES-1 knees far below 60 rps, making most grid cells uninterpretable for it | Night 1 gate (§5); add a 10 rps rung and report ES-1 only below its knee |
| R2 | Thermal drift over 9-hour nights correlates with variant | Randomised branch order (§5.1); check `system_cpu_usage` trend |
| R3 | An API replica dies mid-run, as `ES-3-WeakRefCache` did | Batch runner records and continues; `evaluate.py` catches it as `INVALID` via `no_api_restart` |
| R4 | A branch has divergent metric config, silently invalidating comparisons | V4a audit before any measurement |
| R5 | Disk exhaustion during the payload sweep | `df` precondition; 5m cap; each run resets the DB so there is no accumulation across runs |
| R6 | A night is lost to a crash | Batch runner is crash-resumable (V6) |
| R7 | Campaign overruns into September | ~2.5 weeks of slack after Night 13; §4.6 and §4.7 are the first cuts if needed, then the third seed |

## 9. Work required before Night 1

1. V1 commit gate in the batch runner.
2. V2 track `bench-results/`.
3. V3 fix `image_fresh`.
4. V4 port harness to ES-1 and ES-3; V4a metric-parity audit on all 8 branches.
5. V6 batch runner.
6. `compare.py --aggregate` (§6).
7. Two derived metrics in `dump.py` (§6).
8. V7 rehearsal, both families `PASS`.
