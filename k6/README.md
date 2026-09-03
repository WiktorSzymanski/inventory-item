# Benchmark harness — how to run it

Operational guide. For *why* it is built this way, see [`load-tests-plan.md`](load-tests-plan.md).

---

## 1. Before the first run

**Commit your work.** `evaluate.py` marks any run with uncommitted `src/` changes as
`INVALID` on the `git_clean` check, because the result cannot be tied to a revision.
`ALLOW_DIRTY=1` lets the run proceed but does not make it reproducible — never use it for
data you intend to publish.

**Fix `JAVA_HOME` if needed.** The harness builds before it benchmarks:

```bash
export JAVA_HOME=$HOME/.jdks/corretto-21.0.10     # use $HOME, not ~
```

`~` is *not* expanded when it follows `=` in a command argument, so `JAVA_HOME=~/...`
arrives as a literal tilde and gradle rejects it.

**Do not use `sudo`.** Docker needs no elevation when you are in the `docker` group, and
running elevated makes `bench-results/` root-owned, which blocks every later run. The
harness refuses to start as root.

**There is no `bench.env`, and no replica knob.** `main` owns the only harness now, and the
stack is single-node by construction: `docker-compose.yml` runs exactly one `api` container,
pinned with `container_name: api` and publishing `:8080` to the host itself. There is no
nginx in front of it and nothing to scale. `.env` holds only the Mongo driver-pool
connection sizes, which Compose reads directly.

---

## 2. Running

```bash
SCENARIO=capacity ./k6/bench/bench.sh
SCENARIO=steady RATE=60 DURATION=10m ./k6/bench/bench.sh
SCENARIO=steady DISTINCT_ITEMS=1 ITEMS_PER_ORDER=1 ./k6/bench/bench.sh
```

Everything that touches the measurement runs in a container — k6, the API, MongoDB,
Prometheus. `bench.sh` is the conductor and does nothing inside the measured window.

Each run does: build → reset DB + restart API → seed → warmup → settle → **load** → drain
→ snapshot Prometheus → verdict → PDF. Artifacts land in
`bench-results/<variant>_<scenario>_<timestamp>/`.

### Scenarios

`SCENARIO` selects the load profile (`k6/lib/profiles.js`) and, with it, the verdict rules
(`k6/bench/thresholds.json`). Everything *around* the measured window — build, reset, seed,
warmup, settle, drain, snapshot — is identical for all of them; only the shape of the load
phase and how it is judged change.

| `SCENARIO` | Shape | Sized by | Judged |
|---|---|---|---|
| `steady` *(default)* | Constant arrival rate | `RATE`, `DURATION` | Full default SLO set. The run the thesis tables compare |
| `capacity` | Staircase: `STEP_COUNT` plateaus at `STEP_START + i x STEP_INC`, each reached over `STEP_RAMP_S` and held `STEP_PLATEAU_S` | `STEP_*` | Latency, drain and rejection SLOs **off** — it exists to find the knee, so it fails only when there is no knee to find |
| `stress` | Same shape as `steady`, run *above* the measured knee | `RATE`, `DURATION` | The only scenario where an undrained backlog is the measurement rather than `INVALID`. Latency and lag SLOs off; completion and no-restart still hard |
| `spike` | Idle → burst → idle: `60s` at 0, `10s` ramp in, `60s` at peak, `10s` ramp out, `240s` at 0 (380s total) | `SPIKE_PEAK` | Recovery time after the burst (`max_recovery_seconds` 180). Latency and drain off |
| `soak` | Constant rate, long | `RATE`, `SOAK_DURATION` | Drift, not absolutes: e2e p95 and heap in the last decile vs the first (1.3x / 1.5x), drain <= 300s |
| `legacy` | Single ramp to a peak, the old `reserve-load-test.js` shape | `MAX_RPS_START`, `MAX_RPS`, `RAMP_DURATION` | SLOs disabled — back-compat only, not thesis-grade |
| `legacy-vus` | Closed loop, fixed VU count | `VUS`, `DURATION` | No threshold block of its own, so the defaults apply and mostly do not fit. Back-compat only |

`spike` sends **nothing** either side of the burst. The leading idle minute is the baseline
recovery is measured against — and a second, uncapped chance for the warmup backlog to
finish settling — while the trailing four minutes let the burst's backlog clear with nothing
arriving on top of it, so `recovery_seconds` describes the variant rather than the base rate
someone picked. Clearing it usually outlasts that tail, which is why `DRAIN_TIMEOUT` defaults
to **1800s for `spike`** and 900s everywhere else: the drain is part of the measurement here,
and a drain that times out reports `INVALID`. The recovery series is dumped over
`[T0, T2]`, so the moment in-flight comes back down is found wherever it falls.

`seed` and `warmup` are also valid profile names, but they are phases `bench.sh` runs itself
around the load phase — never pass them as `SCENARIO`. An unknown name fails at k6 init and
prints the list above.

The staircase defaults (`20`/`+20`/`8` steps of `15s + 120s`) run ~18 minutes and peak at
160 orders/s. `points.env` carries matched `STEP_*` for the workload points where that
bracket is wrong — notably anything with `RESERVE_DELAY_MS`, which lowers the ceiling far
enough that the default staircase saturates at step 0 and evaluates `INVALID`.

`READ_RATE` adds a concurrent read scenario alongside *any* of the above; it is a separate
k6 scenario rather than a ratio, so it does not perturb the write arrival rate.

### Knobs

| Variable | Default | Purpose |
|---|---|---|
| `SCENARIO` | `steady` | Load profile — see [Scenarios](#scenarios) above |
| `RATE` | 50 | Arrival rate for `steady` / `stress` / `soak` |
| `DURATION` | `10m` | Load duration for `steady` / `stress` |
| `SOAK_DURATION` | `60m` | Load duration for `soak` |
| `DISTINCT_ITEMS` | 6 | Number of item aggregates — the contention axis |
| `ITEMS_PER_ORDER` | 4 | Lines per order — each costs one *sequential* saga hop |
| `PAYLOAD_BYTES` | 0 | Aggregate padding — copy-on-write and snapshot cost |
| `RESERVE_DELAY_MS` | 0 | Artificial per-reserve sleep inside the aggregate — the domain-work cost axis (every variant since 2026-08-06) |
| `WARMUP_ITERATIONS` | 5000 | Orders submitted before the measured window opens |
| `WARMUP_RATE` | **none** | Orders/s the warmup is delivered at. No default — a run without one aborts at k6 init. `points.env` sets it per point |
| `READ_RATE` | 0 | Optional concurrent read load (separate scenario) |
| `SPIKE_PEAK` | 100 | Burst arrival rate for `spike` (was `SPIKE_BASE` x `SPIKE_FACTOR`) |
| `DRAIN_TIMEOUT` | 900 (`spike`: 1800) | Cap on the post-load drain. A timeout is `INVALID` |
| `SEED` | 1337 | RNG seed; identical item sequence across variants |
| `SKIP_BUILD` | 0 | Skip gradle+docker build (only if the image is already current) |
| `ALLOW_DIRTY` | 0 | Permit uncommitted `src/` — still reports `INVALID` |

`ITEMS_PER_ORDER` must be `<= DISTINCT_ITEMS`; the harness refuses otherwise. Set
`ALLOW_DUP_LINES=true` to deliberately hit one aggregate twice in an order.

The warmup is fixed-iteration *and* fixed-rate. Fixed iterations so a fast variant does not
open its window with deeper event-store, snapshot and cache state than a slow one; fixed
rate so it does not open with a *backlog* either. Keep `WARMUP_RATE` below the slowest
variant's sustained rate at the workload in question — roughly half is the rule used in
`points.env` — otherwise the warmup queues work that the settle phase then has to undo, and
past the 60s `SETTLE_S` cap it cannot.

### Reading results

```bash
python3 k6/bench/compare.py bench-results/*_steady_*              # core table
python3 k6/bench/compare.py --cols es bench-results/ES-*          # ES internals
python3 k6/bench/compare.py --cols resource --baseline bench-results/TO-1_steady_… …
python3 k6/bench/compare.py --knee bench-results/*_capacity_*     # staircase + knee
```

Verdicts: **PASS** · **FAIL** (missed an SLO) · **INVALID** (the *measurement* was broken —
backlog never drained, scrape gap, API restarted mid-run, orders that never reached a
terminal state, dirty tree). Never report an INVALID run as a result.

> **End-to-end latency is not in the k6 output.** `POST /inventory/orders` returns 202
> after persisting only `OrderCreatedEvent`, so k6's "admission latency" is typically
> 3 orders of magnitude below reality. Real latency is `order_e2e_time` in `dump.json`.

---

## 3. What to run, and where

**The campaign plan lives in [`docs/bench-campaign-runbook.md`](../docs/bench-campaign-runbook.md),
not here.** That runbook is written against `scripts/run-suite.sh` on `main`, which is the only
supported way to run the suite: it reads `variants.env`, builds each variant's image and drives
this harness against it. Phases, staircase bracketing, the common rate and the result tables are
all there.

This section used to carry a phase plan of its own. It was written when each variant branch
carried its own copy of `k6/` and its own `bench.env`, and it planned **12** variants — including
`ES-3-optimistic`, `ES-3-pesimistic`, `ES-3-WeakRefCache`, `ES-3-WeakRefCache-NullLock` and
`ES-3-pesimistic-scaling`, none of which exist any more. Following it would have meant
`git checkout <branch> && ./k6/bench/bench.sh` per variant, which is exactly the loop `main` now
owns. It was removed on 2026-08-20 rather than renumbered, because the runbook already says all of
it correctly.

**The live set is seven:** `TO-1`, `TO-2`, `TO-3`, `TO-4`, `ES-1`, `ES-2`, `ES-4`. `variants.env`
on `main` is the registry and the only place that set is defined. Branches that were variants and
are not any more are documented in
[`docs/retired-variants.md`](../docs/retired-variants.md).

One thing worth keeping from the old plan, because it is a property of the workload rather than of
any variant list: **`RESERVE_DELAY_MS` lowers the achievable throughput ceiling** to roughly
`workers / (ITEMS_PER_ORDER x delay)` on TO and `DISTINCT_ITEMS / delay` on ES. Lower
`STEP_START`/`RATE` to match, or the staircase saturates at step 0 and evaluates `INVALID`.
Named workload points in `points.env` already carry matched staircases for the cells that use it.

---

## 4. Troubleshooting

| Symptom | Cause |
|---|---|
| `JAVA_HOME is set to an invalid directory: ~/...` | Tilde not expanded — use `$HOME/...` |
| Refuses to start as root | Correct. Drop `sudo`; you are in the `docker` group |
| `bench-results/ is not writable` | Left root-owned by an earlier `sudo` run; the error prints the `chown` |
| `INVALID` on `git_clean` | Uncommitted `src/` changes. Commit |
| `INVALID` on `backlog_drained` | Backlog never cleared — the e2e histogram is truncated. The variant is saturated; lower `RATE` |
| `INVALID` on `targets_scraped` | Prometheus is not scraping `api:8080` — check the api container is up and `up{job="inventory"}` |
| `e2e_p95` is `-` in the table | No orders completed in the window, or the run is INVALID |
| `drain_seconds = 0`, `drain_service_rate` null | Rate too low to build a backlog. Expected below the knee |

**Single harness, on `main`.** `k6/`, `docker-compose.yml` and `docker-compose.bench.yml`
now live in exactly one place — this worktree — shared by every variant; there is no
`bench.env` and no per-branch copy left to keep in step. The variant branches still carry
their own `k6/` and `bench.env`, but those are unsupported: running `./k6/bench/bench.sh`
from a variant branch produces a different stack than `scripts/run-suite.sh` on `main` does.
