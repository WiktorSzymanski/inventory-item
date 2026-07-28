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

**Check `bench.env`** — the only per-branch file. On `ES-3-pesimistic-scaling` it must set
`EXPECTED_REPLICAS` to the replica count, or every run fails the `targets_scraped`
validity check.

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
| `READ_RATE` | 0 | Optional concurrent read load (separate scenario) |
| `SEED` | 1337 | RNG seed; identical item sequence across variants |
| `SKIP_BUILD` | 0 | Skip gradle+docker build (only if the image is already current) |
| `ALLOW_DIRTY` | 0 | Permit uncommitted `src/` — still reports `INVALID` |

`ITEMS_PER_ORDER` must be `<= DISTINCT_ITEMS`; the harness refuses otherwise. Set
`ALLOW_DUP_LINES=true` to deliberately hit one aggregate twice in an order.

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

12 variants. Running every scenario on all of them is roughly 60+ hours, most of it
uninformative. Run in phases — **each phase depends on the previous one**.

### The variants

| Family | Variants | Distinguishing axis (verified markers) |
|---|---|---|
| **TO** | `TO-1` | `@Version` OL + outbox, `NOTIFY`, `@Async` publisher |
| | `TO-2` | as TO-1 plus `LISTEN` — full NOTIFY/LISTEN outbox |
| | `TO-3`, `TO-4` | outbox without NOTIFY/LISTEN or `@Async` |
| **ES base** | `ES-1`, `ES-2`, `ES-3` | event store progression; `ES-3` adds `StrongCache` |
| **ES-3 lock A/B** | `ES-3-optimistic` | `NullLockFactory` + CoW cache |
| | `ES-3-pesimistic` | `PessimisticLockFactory` + CoW cache |
| **ES-3 cache A/B** | `ES-3-WeakRefCache` | weak-reference cache |
| | `ES-3-WeakRefCache-NullLock` | weak-ref cache + `NullLockFactory` |
| **Scale-out** | `ES-3-pesimistic-scaling` | multi-replica behind nginx |

### Phase 1 — capacity, on all 12 (~6 h)

```bash
SCENARIO=capacity ./k6/bench/bench.sh
```

Nothing else can be interpreted until this is done: it produces each variant's **knee**,
the highest arrival rate it sustains with bounded latency and a non-growing queue. Without
it you have no defensible basis for choosing a comparison rate.

```bash
python3 k6/bench/compare.py --knee bench-results/*_capacity_*
```

Then take **`RATE = 0.6 × the lowest knee across all 12`**. One rate for every variant —
comparing variants at *different* rates measures nothing.

### Phase 2 — steady, on all 12 (~4 h)

```bash
SCENARIO=steady RATE=<0.6 × min knee> DURATION=10m ./k6/bench/bench.sh
```

The head-to-head table: same workload, same rate, compare latency, CPU, DB growth per
order. This is your primary thesis result. Run each variant **twice** and check the pair
agrees within ~5% — if it doesn't, the harness isn't controlling something and no
cross-variant conclusion is safe yet.

### Phase 3 — targeted sweeps (~6 h)

One factor at a time from the Phase 2 baseline. Do **not** run the full cross product.

| Sweep | Command delta | Run on | Why there |
|---|---|---|---|
| **Contention** | `DISTINCT_ITEMS=1` then `100` (with `ITEMS_PER_ORDER=1`) | `ES-3-optimistic`, `ES-3-pesimistic`, `ES-3-WeakRefCache-NullLock`, `TO-1` | This is the lock A/B. One hot aggregate is where `NullLockFactory` and `PessimisticLockFactory` diverge; 100 items is the no-contention control. |
| **Payload** | `PAYLOAD_BYTES=1048576 DURATION=5m` | `ES-3-*` cache variants, `TO-1` control | Padding rides only on `InventoryCreatedEvent`, so it does **not** inflate appends — it inflates snapshot rows and the per-command Jackson deep copy. It is a copy-on-write cost lever, and it is what separates strong-ref from weak-ref caching. |
| **Fan-out** | `ITEMS_PER_ORDER=1` then `8` | one TO, one ES-3 | Each line is a *sequential* saga hop on ES; on TO it is one transaction. The clearest structural difference between the families. |
| **Read load** | `READ_RATE=200` | one TO, one ES-3 | Tests the projection-decoupling claim. Deserves its own table — never fold it into the write numbers. |

**Cap payload runs at 5 minutes** and check free disk first: 1 MiB snapshots at ~13/s is
roughly 47 GB/hour.

### Phase 4 — spike, on 6 (~1.5 h)

```bash
SCENARIO=spike ./k6/bench/bench.sh
```

Run on `TO-1`, `TO-2`, `TO-3`, `ES-1`, `ES-3-pesimistic`, `ES-3-optimistic`. Measures
backlog build-up and recovery time — the async-outbox variants (TO-1/TO-2 with
NOTIFY/LISTEN) against the saga. `drain_service_rate` is the payoff number here and is
meaningless at low rates, so it only becomes real once a backlog exists.

### Phase 5 — soak, on 4 (~4 h)

```bash
SCENARIO=soak RATE=<0.6 × min knee> ./k6/bench/bench.sh
```

Run only on your headline variants — one TO, `ES-1`, `ES-3-pesimistic`, and whichever
ES-3 cache variant wins Phase 3. Catches projection-lag creep, heap drift, and DB growth
per order that a 10-minute run cannot show. Everything here is drift-ratio based
(last decile ÷ first decile).

### Phase 6 — scale-out (optional)

`ES-3-pesimistic-scaling` at the Phase 2 rate, then above the single-node knee. The only
variant where horizontal scaling is on the table. Set `EXPECTED_REPLICAS` first.

### Minimum viable campaign

If time is short, Phases 1 and 2 alone (~10 h) give a defensible TO-vs-ES comparison.
Add the contention sweep for the lock A/B and you have the thesis' core claims covered.

---

## 4. Troubleshooting

| Symptom | Cause |
|---|---|
| `JAVA_HOME is set to an invalid directory: ~/...` | Tilde not expanded — use `$HOME/...` |
| Refuses to start as root | Correct. Drop `sudo`; you are in the `docker` group |
| `bench-results/ is not writable` | Left root-owned by an earlier `sudo` run; the error prints the `chown` |
| `INVALID` on `git_clean` | Uncommitted `src/` changes. Commit |
| `INVALID` on `backlog_drained` | Backlog never cleared — the e2e histogram is truncated. The variant is saturated; lower `RATE` |
| `INVALID` on `targets_scraped` | `EXPECTED_REPLICAS` in `bench.env` disagrees with reality |
| `e2e_p95` is `-` in the table | No orders completed in the window, or the run is INVALID |
| `drain_seconds = 0`, `drain_service_rate` null | Rate too low to build a backlog. Expected below the knee |

**Cross-branch invariant** — everything under `k6/` and `docker-compose.bench.yml` must be
byte-identical on every branch; `bench.env` is the only per-branch file:

```bash
git diff --stat harness-v1 <branch> -- k6 docker-compose.bench.yml    # must be empty
```
