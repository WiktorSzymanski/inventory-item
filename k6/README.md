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
nginx in front of it and nothing to scale. `.env` holds only the Postgres and Hikari
connection sizes, which Compose reads directly.

---

## 2. Running

```bash
SCENARIO=capacity ./k6/bench/bench.sh
SCENARIO=steady RATE=60 DURATION=10m ./k6/bench/bench.sh
SCENARIO=steady DISTINCT_ITEMS=1 ITEMS_PER_ORDER=1 ./k6/bench/bench.sh
```

Everything that touches the measurement runs in a container — k6, the API, Postgres,
Prometheus. `bench.sh` is the conductor and does nothing inside the measured window.

Each run does: build → reset DB + restart API → seed → warmup → settle → **load** → drain
→ snapshot Prometheus → verdict → PDF. Artifacts land in
`bench-results/<variant>_<scenario>_<timestamp>/`.

### Knobs

| Variable | Default | Purpose |
|---|---|---|
| `SCENARIO` | `steady` | `capacity` · `steady` · `spike` · `soak` (also `seed`/`warmup` internally) |
| `RATE` | 50 | Arrival rate for `steady` / `soak` |
| `DURATION` | `10m` | Load duration for `steady` |
| `SOAK_DURATION` | `45m` | Load duration for `soak` |
| `DISTINCT_ITEMS` | 6 | Number of item aggregates — the contention axis |
| `ITEMS_PER_ORDER` | 4 | Lines per order — each costs one *sequential* saga hop |
| `PAYLOAD_BYTES` | 0 | Aggregate padding — copy-on-write and snapshot cost |
| `RESERVE_DELAY_MS` | 0 | Artificial per-reserve sleep inside the aggregate — the domain-work cost axis (every variant since 2026-08-06) |
| `WARMUP_ITERATIONS` | 5000 | Orders submitted before the measured window opens |
| `WARMUP_RATE` | **none** | Orders/s the warmup is delivered at. No default — a run without one aborts at k6 init. `points.env` sets it per point |
| `READ_RATE` | 0 | Optional concurrent read load (separate scenario) |
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
