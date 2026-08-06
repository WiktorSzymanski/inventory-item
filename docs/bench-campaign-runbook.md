# Benchmark campaign runbook

Every command for the campaign in
[`superpowers/specs/2026-08-06-load-test-campaign-design.md`](superpowers/specs/2026-08-06-load-test-campaign-design.md),
in execution order. 66 runs (24 + 8 + 34), ~47 h of machine time including a ~10% re-run
allowance.

**This runbook assumes Phase 0 is complete** — see
[`superpowers/plans/2026-08-06-load-test-campaign-phase0.md`](superpowers/plans/2026-08-06-load-test-campaign-phase0.md).
Until it is, `RESERVE_DELAY_MS` is silently ignored on six branches, `PAYLOAD_BYTES` does
nothing on TO after seed, `SCENARIO=stress` does not exist, and `ES-1`/`ES-3` cannot run
`bench.sh` at all.

---

## 0. Read this before the first command

### Runs go through `./scripts/bench_run.sh`, not `./k6/bench/bench.sh`

Identical interface — every knob is an environment variable, inherited straight through —
plus one thing `bench.sh` cannot do: it preserves the Prometheus TSDB.

`bench.sh` keeps no TSDB of its own. The raw series live in the `prometheus-data` volume,
and the `docker compose down -v` before the next branch destroys them. What survives
unaided is `dump.json`'s ~20 extracted series — 20 of the merged dashboard's 56 panels.
Every `pg_stat_*` metric, WAL size, locks, checkpoints, GC pause, HikariCP, Tomcat, and on
the TO family the outbox and order-timing panels have no archived equivalent at any effort,
because they were never captured into `dump.json` in the first place.

`bench_run.sh` runs `bench.sh`, then makes two copies, **both immune to `down -v`**:

| Destination | What it is |
|---|---|
| `bench-results/<run_id>/prom-snapshot/` | an ordinary host directory in the repo, beside `dump.json` and `report.pdf` — nothing in the harness or in Docker can touch it |
| `bench-replay-data` | a docker volume declared `external: true`, so `down -v` cannot remove it |

It exits with `bench.sh`'s own code (0 PASS, 1 FAIL, 2 INVALID) and snapshots either way —
a FAIL or INVALID run is exactly the one whose metrics you need to look at. `ARCHIVE_TSDB=0`
skips the volume merge; `SNAPSHOT_TSDB=0` skips both.

### Do **not** pass `ALLOW_DIRTY=1`

It lets `bench.sh` start with uncommitted changes under `src/`, but `evaluate.py` still
counts `git_dirty` as a **validity** check, so every such run reports `INVALID` and is
unusable as a result. For ad-hoc probing that is fine; for these 66 runs it would void the
campaign. Commit `src/` instead — the knob is then unnecessary, since `git_dirty` only
counts changes under `src/`.

### Shell

The commands below are bash form. In `fish` the `VAR=value command` prefix is not
supported — either start a `bash` subshell for the session, or prefix each command with
`env`.

### Preflight, once per session

```bash
id -u                                     # must NOT be 0 — the harness refuses to run as root
test -w bench-results && echo writable    # must print "writable"
git status --porcelain -- src/            # must be empty, or every run reports INVALID
df -h /                                   # ≥120 GB free before any PAYLOAD_BYTES=1048576 run
docker volume create bench-replay-data    # no-op if it already exists
```

### After every run, check

- **`VERDICT`.** `INVALID` is never a result — re-run once; if it recurs for the same
  reason, the reason is the finding.
- **`image_fresh`.** A false `INVALID` here is a known Docker build-cache artefact. Check
  the jar contents rather than the image timestamp before discarding a good run.
- **`knee`**, on `capacity` runs — apply the bracketing rule in §2.1.
- **`snapshot:`** line printed by `bench_run.sh`. If it says the snapshot failed, the run's
  own artifacts are still valid but it will only ever replay from `dump.json`.

---

## 1. Workload points

| Point | `DISTINCT_ITEMS` | `ITEMS_PER_ORDER` | Probes |
|---|---|---|---|
| **W-base** | 100 | 4 | reference — low contention, moderate fan-out |
| **W-hot** | 8 | 4 | contention |
| **W-fan** | 100 | 16 | fan-out |

`PAYLOAD_BYTES=0` and `RESERVE_DELAY_MS=0` throughout phase 1 — both are harness defaults,
so they are not passed.

`DRAIN_TIMEOUT=3600` on breakpoints, `1800` elsewhere. The default is 900 s, which several
past capacity runs exceeded — and a drain timeout is an automatic `INVALID`.

---

## 2. Phase 1 — breakpoints (24 runs, ~15 h)

Grouped **by workload point, not by variant**, so a mis-calibrated staircase surfaces on run
1 of 8 rather than run 24 of 24.

### 2.1 The bracketing rule — apply after every run

- knee at the **last step**, or `require_knee` unsatisfied → double `STEP_INC`, re-run
- knee at **step 0** → halve `STEP_START`, re-run

**If you change a staircase, re-run every variant already measured at that point.** Knees
read off different staircases are not comparable, and the selection in §5 rests on
comparing them.

### 2.2 W-base — staircase 40/40/10 (peak 400), ~35–50 min per run

```bash
# ── 01/24 ── TO-1 · breakpoint · W-base
git checkout TO-1 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 STEP_START=40 STEP_INC=40 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 02/24 ── TO-2 · breakpoint · W-base
git checkout TO-2 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 STEP_START=40 STEP_INC=40 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 03/24 ── TO-3 · breakpoint · W-base
git checkout TO-3 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 STEP_START=40 STEP_INC=40 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 04/24 ── TO-4 · breakpoint · W-base
git checkout TO-4 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 STEP_START=40 STEP_INC=40 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 05/24 ── ES-1 · breakpoint · W-base
git checkout ES-1 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 STEP_START=40 STEP_INC=40 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 06/24 ── ES-2 · breakpoint · W-base
git checkout ES-2 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 STEP_START=40 STEP_INC=40 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 07/24 ── ES-3 · breakpoint · W-base
git checkout ES-3 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 STEP_START=40 STEP_INC=40 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 08/24 ── ES-4 · breakpoint · W-base
git checkout ES-4 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 STEP_START=40 STEP_INC=40 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh
```

### 2.3 W-hot — staircase 20/20/12 (peak 240), ~40–55 min per run

```bash
# ── 09/24 ── TO-1 · breakpoint · W-hot
git checkout TO-1 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 STEP_START=20 STEP_INC=20 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 10/24 ── TO-2 · breakpoint · W-hot
git checkout TO-2 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 STEP_START=20 STEP_INC=20 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 11/24 ── TO-3 · breakpoint · W-hot
git checkout TO-3 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 STEP_START=20 STEP_INC=20 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 12/24 ── TO-4 · breakpoint · W-hot
git checkout TO-4 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 STEP_START=20 STEP_INC=20 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 13/24 ── ES-1 · breakpoint · W-hot
git checkout ES-1 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 STEP_START=20 STEP_INC=20 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 14/24 ── ES-2 · breakpoint · W-hot
git checkout ES-2 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 STEP_START=20 STEP_INC=20 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 15/24 ── ES-3 · breakpoint · W-hot
git checkout ES-3 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 STEP_START=20 STEP_INC=20 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 16/24 ── ES-4 · breakpoint · W-hot
git checkout ES-4 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 STEP_START=20 STEP_INC=20 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh
```

### 2.4 W-fan — staircase 10/10/12 (peak 120), ~40–55 min per run

16 lines per order: one transaction on TO, sixteen sequential saga hops on ES. Expect the
lowest ES knees of phase 1 here.

```bash
# ── 17/24 ── TO-1 · breakpoint · W-fan
git checkout TO-1 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 STEP_START=10 STEP_INC=10 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 18/24 ── TO-2 · breakpoint · W-fan
git checkout TO-2 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 STEP_START=10 STEP_INC=10 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 19/24 ── TO-3 · breakpoint · W-fan
git checkout TO-3 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 STEP_START=10 STEP_INC=10 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 20/24 ── TO-4 · breakpoint · W-fan
git checkout TO-4 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 STEP_START=10 STEP_INC=10 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 21/24 ── ES-1 · breakpoint · W-fan
git checkout ES-1 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 STEP_START=10 STEP_INC=10 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 22/24 ── ES-2 · breakpoint · W-fan
git checkout ES-2 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 STEP_START=10 STEP_INC=10 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 23/24 ── ES-3 · breakpoint · W-fan
git checkout ES-3 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 STEP_START=10 STEP_INC=10 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── 24/24 ── ES-4 · breakpoint · W-fan
git checkout ES-4 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 STEP_START=10 STEP_INC=10 STEP_COUNT=12 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh
```

---

## 3. Compute the common soak rate

```bash
python3 k6/bench/compare.py --knee bench-results/*_capacity_Wbase_*
```

Record every knee in §7 Table A, then:

```
RATE = round(0.6 × the LOWEST knee across all eight W-base runs)
```

One rate for all eight. Comparing variants at different rates measures nothing, and this
soak is the headline head-to-head table. Write the number into Table A before running §4.

---

## 4. Phase 1 — soaks (8 runs, ~8 h)

W-base, 45 min each, all at the single `RATE` from §3. **Substitute the computed number for
`<RATE>`** — e.g. if §3 gives 72, run 25 becomes:

```bash
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=Wbase RATE=72 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
```

~60 min per run.

```bash
# ── 25/32 ── TO-1 · soak · W-base
git checkout TO-1 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# ── 26/32 ── TO-2 · soak · W-base
git checkout TO-2 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# ── 27/32 ── TO-3 · soak · W-base
git checkout TO-3 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# ── 28/32 ── TO-4 · soak · W-base
git checkout TO-4 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# ── 29/32 ── ES-1 · soak · W-base
git checkout ES-1 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# ── 30/32 ── ES-2 · soak · W-base
git checkout ES-2 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# ── 31/32 ── ES-3 · soak · W-base
git checkout ES-3 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# ── 32/32 ── ES-4 · soak · W-base
git checkout ES-4 && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
```

Read the results:

```bash
python3 k6/bench/compare.py bench-results/*_soak_Wbase_*
python3 k6/bench/compare.py --cols es bench-results/ES-*_soak_Wbase_*
```

---

## 5. Selection — one winner per family

Fill §7 Table B, then, per family:

1. **Disqualify** any variant whose W-base soak verdict is not `PASS`. A variant that cannot
   hold the common rate for 45 minutes does not represent its family.
2. **Rank** the survivors by knee at W-base, W-hot and W-fan separately (1 = highest knee).
3. **Winner** = lowest mean of the three ranks.
4. **Tie** → lower `order_e2e` p95 (confirmed) in the W-base soak.

If a whole family is disqualified, re-run its failing soaks once; if they fail again, that
failure mode is the family's result and the best-ranked variant proceeds with the caveat
recorded.

Record both winners in Table B. §6 refers to them as `<TO-WIN>` and `<ES-WIN>`.

---

## 6. Phase 2 — the two winners (34 runs, ~20 h)

Workload stays at W-base except §6.7.

| Cell | `PAYLOAD_BYTES` | `RESERVE_DELAY_MS` |
|---|---|---|
| **C00** | 0 | 0 |
| **C01** | 0 | 25 |
| **C10** | 1048576 | 0 |
| **C11** | 1048576 | 25 |

**Before every `P=1 MiB` run**, confirm `df -h /` shows ≥120 GB free; abort if free space
falls below 40 GB mid-run. TO's `additional_bytes` column is TOASTed and rewritten on every
reserve, and `TRUNCATE` reclaims only *between* runs, never within one. Do not tune
autovacuum to suppress the bloat — it is part of what the lever measures.

All `P=1 MiB` runs carry `WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m`. The default 5000
would exceed the 5 min warmup cap at 1 MiB and silently deliver *fewer* iterations,
destroying the identical-starting-state property the fixed-iteration warmup exists for.
Confirm the full count in `<run>/warmup/k6.log`.

### 6.1 Cell breakpoints (6 runs)

C00's breakpoint is **reused** from phase 1 — same config, same binary — so it is not
re-run. Staircases are first guesses; §2.1 applies, and within a cell both winners must end
on the *same* staircase.

```bash
# ── C01 · delay only · staircase 10/15/10 (peak 145)
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C01 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 RESERVE_DELAY_MS=25 STEP_START=10 STEP_INC=15 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C01 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 RESERVE_DELAY_MS=25 STEP_START=10 STEP_INC=15 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── C10 · payload only · staircase 5/5/10 (peak 50)
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C10 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m STEP_START=5 STEP_INC=5 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C10 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m STEP_START=5 STEP_INC=5 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── C11 · both · staircase 2/3/10 (peak 29)
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C11 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m STEP_START=2 STEP_INC=3 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C11 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m STEP_START=2 STEP_INC=3 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh
```

### 6.2 Compute `K` per cell

```bash
python3 k6/bench/compare.py --knee bench-results/*_capacity_C01_*
python3 k6/bench/compare.py --knee bench-results/*_capacity_C10_*
python3 k6/bench/compare.py --knee bench-results/*_capacity_C11_*
```

`K = min(knee of the TO winner, knee of the ES winner)` per cell. `K_C00` comes from the two
winners' **W-base** knees in Table A. Record all four in Table C, then derive:

| Test | Setting |
|---|---|
| soak | `RATE = round(0.6 × K)` |
| spike | `SPIKE_BASE = round(0.4 × K)`, `SPIKE_FACTOR=4` → peak `1.6 × K` |
| stress | `RATE = round(1.25 × K)` |

One `K` per cell keeps the two winners comparable within it. If one collapses at C10 or C11,
the other soaks nearly idle there — the accepted price of a common rate.

### 6.3 C00 — reference (6 runs)

`<S00>` = `round(0.6 × K_C00)`, `<B00>` = `round(0.4 × K_C00)`, `<X00>` = `round(1.25 × K_C00)`.

```bash
# soak
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=C00 RATE=<S00> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=C00 RATE=<S00> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# spike
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=spike RUN_LABEL=C00 SPIKE_BASE=<B00> SPIKE_FACTOR=4 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=spike RUN_LABEL=C00 SPIKE_BASE=<B00> SPIKE_FACTOR=4 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# stress
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=stress RUN_LABEL=C00 RATE=<X00> DURATION=10m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=stress RUN_LABEL=C00 RATE=<X00> DURATION=10m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
```

An undrained backlog on a `stress` run is the measurement, not a broken run — the `stress`
thresholds block disables the drain validity gate for exactly that reason. What must still
hold: every admitted order reaches a terminal state, and the API does not restart.

### 6.4 C01 — delay only (6 runs)

`<S01>`, `<B01>`, `<X01>` from `K_C01`.

```bash
# soak
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=C01 RATE=<S01> RESERVE_DELAY_MS=25 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=C01 RATE=<S01> RESERVE_DELAY_MS=25 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# spike
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=spike RUN_LABEL=C01 SPIKE_BASE=<B01> SPIKE_FACTOR=4 RESERVE_DELAY_MS=25 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=spike RUN_LABEL=C01 SPIKE_BASE=<B01> SPIKE_FACTOR=4 RESERVE_DELAY_MS=25 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# stress
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=stress RUN_LABEL=C01 RATE=<X01> DURATION=10m RESERVE_DELAY_MS=25 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=stress RUN_LABEL=C01 RATE=<X01> DURATION=10m RESERVE_DELAY_MS=25 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
```

### 6.5 C10 — payload only (6 runs)

`<S10>`, `<B10>`, `<X10>` from `K_C10`. Soak is **15 min**, not 45. Check `df -h /` first.

```bash
# soak (15m)
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=C10 RATE=<S10> SOAK_DURATION=15m PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=C10 RATE=<S10> SOAK_DURATION=15m PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# spike
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=spike RUN_LABEL=C10 SPIKE_BASE=<B10> SPIKE_FACTOR=4 PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=spike RUN_LABEL=C10 SPIKE_BASE=<B10> SPIKE_FACTOR=4 PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# stress
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=stress RUN_LABEL=C10 RATE=<X10> DURATION=10m PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=stress RUN_LABEL=C10 RATE=<X10> DURATION=10m PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
```

### 6.6 C11 — both (6 runs)

`<S11>`, `<B11>`, `<X11>` from `K_C11`. Soak is **15 min**. The heaviest cell — check
`df -h /` before every run.

```bash
# soak (15m)
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=C11 RATE=<S11> SOAK_DURATION=15m PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=soak RUN_LABEL=C11 RATE=<S11> SOAK_DURATION=15m PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# spike
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=spike RUN_LABEL=C11 SPIKE_BASE=<B11> SPIKE_FACTOR=4 PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=spike RUN_LABEL=C11 SPIKE_BASE=<B11> SPIKE_FACTOR=4 PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh

# stress
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=stress RUN_LABEL=C11 RATE=<X11> DURATION=10m PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=stress RUN_LABEL=C11 RATE=<X11> DURATION=10m PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 DRAIN_TIMEOUT=1800 ./scripts/bench_run.sh
```

### 6.7 Workload strip at C11 (4 runs)

The one place the campaign asks whether contention and fan-out compound with the
aggregate-cost levers. Breakpoints only, at the most-stressed cell. Staircases start very
low — expect to apply §2.1 here more than anywhere else.

```bash
# ── W-hot at C11 · staircase 2/2/10 (peak 20)
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C11-Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m STEP_START=2 STEP_INC=2 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C11-Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m STEP_START=2 STEP_INC=2 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh

# ── W-fan at C11 · staircase 1/1/10 (peak 10)
# 16 lines x 1 MiB per order — the heaviest run in the campaign. Check df -h / first.
git checkout <TO-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C11-Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m STEP_START=1 STEP_INC=1 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh
git checkout <ES-WIN> && docker compose down -v
JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RUN_LABEL=C11-Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m STEP_START=1 STEP_INC=1 STEP_COUNT=10 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh
```

---

## 7. Results

### Table A — phase 1 knees and the common rate

| Variant | knee W-base | knee W-hot | knee W-fan |
|---|---|---|---|
| TO-1 | | | |
| TO-2 | | | |
| TO-3 | | | |
| TO-4 | | | |
| ES-1 | | | |
| ES-2 | | | |
| ES-3 | | | |
| ES-4 | | | |

**Lowest W-base knee:** ______  →  **`RATE` = 0.6 × that, rounded:** ______

### Table B — phase 1 soaks and selection

| Variant | soak verdict | e2e p95 (s) | rank W-base | rank W-hot | rank W-fan | mean rank |
|---|---|---|---|---|---|---|
| TO-1 | | | | | | |
| TO-2 | | | | | | |
| TO-3 | | | | | | |
| TO-4 | | | | | | |
| ES-1 | | | | | | |
| ES-2 | | | | | | |
| ES-3 | | | | | | |
| ES-4 | | | | | | |

**`<TO-WIN>` =** ______   **`<ES-WIN>` =** ______

### Table C — phase 2 per-cell rates

| Cell | knee TO-WIN | knee ES-WIN | `K` | soak `0.6K` | spike base `0.4K` | stress `1.25K` |
|---|---|---|---|---|---|---|
| C00 | | | | | | |
| C01 | | | | | | |
| C10 | | | | | | |
| C11 | | | | | | |

### Table D — phase 2 outcomes

| Cell | Test | TO-WIN verdict | TO-WIN e2e p95 | ES-WIN verdict | ES-WIN e2e p95 |
|---|---|---|---|---|---|
| C00 | soak | | | | |
| C00 | spike | | | | |
| C00 | stress | | | | |
| C01 | soak | | | | |
| C01 | spike | | | | |
| C01 | stress | | | | |
| C10 | soak | | | | |
| C10 | spike | | | | |
| C10 | stress | | | | |
| C11 | soak | | | | |
| C11 | spike | | | | |
| C11 | stress | | | | |

### Table E — workload strip at C11

| Point | knee TO-WIN | knee ES-WIN |
|---|---|---|
| W-hot | | |
| W-fan | | |

---

## 8. Reading the results

```bash
python3 k6/bench/compare.py --knee bench-results/*_capacity_*        # staircases and knees
python3 k6/bench/compare.py bench-results/*_soak_Wbase_*             # phase 1 head-to-head
python3 k6/bench/compare.py --cols es bench-results/ES-*             # ES internals
python3 k6/bench/compare.py --cols resource --baseline <run> <run>   # resource deltas
```

**End-to-end latency is not in the k6 output.** `POST /inventory/orders` returns 202 after
persisting only `OrderCreatedEvent`, so k6 sees admission latency — typically three orders
of magnitude below reality. Real latency is `order_e2e_time` in `dump.json`.

**No run is repeated**, so there is no spread estimate and no error bars. Differences under
roughly 10% are reported as "not separated by this campaign", not as a ranking. If a
headline number surprises you, spot-repeat that single run before writing it up.

### Viewing any archived run at full fidelity

Because `bench_run.sh` snapshotted the TSDB, every panel works months later:

1. Open `the-dashboard` in Grafana.
2. Switch the **"Data source"** dropdown from "Prometheus" to **"Prometheus Replay"**.
3. Set the time picker to that run's `windows.full` from its `meta.json`.

If a snapshot was ever skipped, that run falls back to `bench-replay` and `dump.json` — ~20
of 56 panels. See [`bench-replay.md`](bench-replay.md).

To re-merge a host-side snapshot that failed to archive at the time:

```bash
./scripts/prom_archive.sh bench-results/<run_id>/prom-snapshot
```
