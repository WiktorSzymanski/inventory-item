# Benchmark campaign runbook

Every command for the campaign in
[`superpowers/specs/2026-08-06-load-test-campaign-design.md`](superpowers/specs/2026-08-06-load-test-campaign-design.md),
in execution order. 66 runs (24 + 8 + 34), ~47 h of machine time including a ~10% re-run
allowance.

**This runbook assumes Phase 0 is complete** — see
[`superpowers/plans/2026-08-06-load-test-campaign-phase0.md`](superpowers/plans/2026-08-06-load-test-campaign-phase0.md).
Until it is, `RESERVE_DELAY_MS` is silently ignored on six branches, `PAYLOAD_BYTES` does
nothing on TO after seed, `SCENARIO=stress` does not exist, and `ES-1`/`ES-3` cannot run
`bench.sh` at all. Run the whole-phase gate at the end of that plan first.

> **Shell note.** Every command below uses the `env VAR=value ...` form, which works in both
> `fish` (your login shell) and `bash`. The bare `VAR=value command` prefix used in
> `k6/README.md` is **bash-only** and fails in fish.

---

## 0. Preflight — once per session

```bash
export JAVA_HOME=$HOME/.jdks/corretto-21.0.10     # $HOME, never ~
```

In fish:

```fish
set -x JAVA_HOME $HOME/.jdks/corretto-21.0.10
```

Then check all of these:

```bash
id -u                                  # must NOT be 0 — the harness refuses to run as root
test -w bench-results && echo writable # must print "writable"
df -h /                                # ≥120 GB free before any PAYLOAD_BYTES=1048576 run
docker volume create bench-replay-data # no-op if it already exists
```

### The archive helper

`bench.sh` does **not** snapshot the Prometheus TSDB, and `docker compose down -v` destroys
it. Archive each run *before* switching branches, or that run is only ever viewable through
the ~20 signals `dump.json` carries instead of all 56 dashboard panels.

fish:

```fish
function archive_last
    set run (basename (ls -td bench-results/*/ | head -1))
    ./scripts/prom_snapshot.sh $run; and ./scripts/prom_archive.sh bench-results/$run/prom-snapshot
    echo "archived: $run"
end
```

bash:

```bash
archive_last() {
    local run; run=$(basename "$(ls -td bench-results/*/ | head -1)")
    ./scripts/prom_snapshot.sh "$run" && ./scripts/prom_archive.sh "bench-results/$run/prom-snapshot"
    echo "archived: $run"
}
```

`bench-replay-data` is an external volume, so `down -v` cannot touch it.

### The per-run shape

Every run below follows the same three steps. They are written out in full for each run so
nothing has to be reconstructed at 2 a.m.

```
git checkout <branch> && docker compose down -v   # stale state fails silently as a health timeout
env ... ./k6/bench/bench.sh                        # the run
archive_last                                       # before the next down -v
```

### After every run, check

- **`VERDICT`** printed at the end. `INVALID` is never a result — re-run once; if it recurs
  for the same reason, the reason is the finding.
- **`image_fresh`** — a false `INVALID` here is a known Docker build-cache artefact. Check
  the jar contents rather than the image timestamp before discarding a good run.
- **`knee`** for `capacity` runs — apply the bracketing rule in §2.1.

---

## 1. Workload points

| Point | `DISTINCT_ITEMS` | `ITEMS_PER_ORDER` | Probes |
|---|---|---|---|
| **W-base** | 100 | 4 | reference — low contention, moderate fan-out |
| **W-hot** | 8 | 4 | contention |
| **W-fan** | 100 | 16 | fan-out |

`PAYLOAD_BYTES=0` and `RESERVE_DELAY_MS=0` for all of phase 1 — both are the harness
defaults, so they are not passed explicitly.

---

## 2. Phase 1 — breakpoints (24 runs, ~15 h)

Grouped **by workload point, not by variant**. That ordering exists so a mis-calibrated
staircase surfaces on run 1 of 8 rather than run 24 of 24.

### 2.1 The bracketing rule — apply after every run

A staircase that does not bracket the knee yields nothing.

- knee at the **last step**, or `require_knee` unsatisfied → double `STEP_INC`, re-run
- knee at **step 0** → halve `STEP_START`, re-run

**If you change a staircase, re-run every variant already measured at that point on the new
staircase.** Knees read off different staircases are not comparable, and the whole selection
in §4 rests on comparing them.

### 2.2 W-base — `DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4`, staircase 40/40/10 (peak 400)

~35–50 min per run.

```bash
# ── 01/24 ── TO-1 · breakpoint · W-base
git checkout TO-1 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    STEP_START=40 STEP_INC=40 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── 02/24 ── TO-2 · breakpoint · W-base
git checkout TO-2 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    STEP_START=40 STEP_INC=40 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── 03/24 ── TO-3 · breakpoint · W-base
git checkout TO-3 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    STEP_START=40 STEP_INC=40 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── 04/24 ── TO-4 · breakpoint · W-base
git checkout TO-4 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    STEP_START=40 STEP_INC=40 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── 05/24 ── ES-1 · breakpoint · W-base
git checkout ES-1 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    STEP_START=40 STEP_INC=40 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── 06/24 ── ES-2 · breakpoint · W-base
git checkout ES-2 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    STEP_START=40 STEP_INC=40 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── 07/24 ── ES-3 · breakpoint · W-base
git checkout ES-3 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    STEP_START=40 STEP_INC=40 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── 08/24 ── ES-4 · breakpoint · W-base
git checkout ES-4 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    STEP_START=40 STEP_INC=40 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last
```

### 2.3 W-hot — `DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4`, staircase 20/20/12 (peak 240)

~40–55 min per run.

```bash
# ── 09/24 ── TO-1 · breakpoint · W-hot
git checkout TO-1 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    STEP_START=20 STEP_INC=20 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 10/24 ── TO-2 · breakpoint · W-hot
git checkout TO-2 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    STEP_START=20 STEP_INC=20 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 11/24 ── TO-3 · breakpoint · W-hot
git checkout TO-3 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    STEP_START=20 STEP_INC=20 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 12/24 ── TO-4 · breakpoint · W-hot
git checkout TO-4 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    STEP_START=20 STEP_INC=20 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 13/24 ── ES-1 · breakpoint · W-hot
git checkout ES-1 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    STEP_START=20 STEP_INC=20 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 14/24 ── ES-2 · breakpoint · W-hot
git checkout ES-2 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    STEP_START=20 STEP_INC=20 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 15/24 ── ES-3 · breakpoint · W-hot
git checkout ES-3 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    STEP_START=20 STEP_INC=20 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 16/24 ── ES-4 · breakpoint · W-hot
git checkout ES-4 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    STEP_START=20 STEP_INC=20 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last
```

### 2.4 W-fan — `DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16`, staircase 10/10/12 (peak 120)

~40–55 min per run. Each order is 16 lines — one transaction on TO, sixteen sequential saga
hops on ES — so expect the ES knees here to be the lowest in phase 1.

```bash
# ── 17/24 ── TO-1 · breakpoint · W-fan
git checkout TO-1 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    STEP_START=10 STEP_INC=10 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 18/24 ── TO-2 · breakpoint · W-fan
git checkout TO-2 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    STEP_START=10 STEP_INC=10 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 19/24 ── TO-3 · breakpoint · W-fan
git checkout TO-3 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    STEP_START=10 STEP_INC=10 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 20/24 ── TO-4 · breakpoint · W-fan
git checkout TO-4 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    STEP_START=10 STEP_INC=10 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 21/24 ── ES-1 · breakpoint · W-fan
git checkout ES-1 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    STEP_START=10 STEP_INC=10 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 22/24 ── ES-2 · breakpoint · W-fan
git checkout ES-2 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    STEP_START=10 STEP_INC=10 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 23/24 ── ES-3 · breakpoint · W-fan
git checkout ES-3 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    STEP_START=10 STEP_INC=10 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last

# ── 24/24 ── ES-4 · breakpoint · W-fan
git checkout ES-4 && docker compose down -v
env SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    STEP_START=10 STEP_INC=10 STEP_COUNT=12 ./k6/bench/bench.sh
archive_last
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

One rate for all eight variants. Comparing variants at different rates measures nothing, and
this soak is the headline head-to-head table. Write the number into §7 Table A before
running anything below.

---

## 4. Phase 1 — soaks (8 runs, ~8 h)

W-base only, 45 minutes each, all at the single `RATE` from §3. **Substitute the computed
number for `<RATE>` in all eight commands** — for example, if §3 gives 72:

```bash
env SCENARIO=soak RUN_LABEL=Wbase RATE=72 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
```

~60 min per run.

```bash
# ── 25/32 ── TO-1 · soak · W-base
git checkout TO-1 && docker compose down -v
env SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# ── 26/32 ── TO-2 · soak · W-base
git checkout TO-2 && docker compose down -v
env SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# ── 27/32 ── TO-3 · soak · W-base
git checkout TO-3 && docker compose down -v
env SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# ── 28/32 ── TO-4 · soak · W-base
git checkout TO-4 && docker compose down -v
env SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# ── 29/32 ── ES-1 · soak · W-base
git checkout ES-1 && docker compose down -v
env SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# ── 30/32 ── ES-2 · soak · W-base
git checkout ES-2 && docker compose down -v
env SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# ── 31/32 ── ES-3 · soak · W-base
git checkout ES-3 && docker compose down -v
env SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# ── 32/32 ── ES-4 · soak · W-base
git checkout ES-4 && docker compose down -v
env SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
```

Read the results:

```bash
python3 k6/bench/compare.py bench-results/*_soak_Wbase_*
python3 k6/bench/compare.py --cols es bench-results/ES-*_soak_Wbase_*
```

---

## 5. Selection — elect one winner per family

Fill §7 Table B, then, **per family**:

1. **Disqualify** any variant whose W-base soak verdict is not `PASS`. A variant that cannot
   hold the common rate for 45 minutes does not represent its family.
2. **Rank** the survivors by knee at W-base, W-hot and W-fan separately (1 = highest knee).
3. **Winner** = lowest mean of the three ranks.
4. **Tie** → lower `order_e2e` p95 (confirmed) in the W-base soak.

If every variant in a family is disqualified, re-run the failing soaks once; if they fail
again, that failure mode is the family's result and the best-ranked variant proceeds with
the caveat recorded.

Record the two winners in §7 Table B. Everything in §6 refers to them as `<TO-WIN>` and
`<ES-WIN>`.

---

## 6. Phase 2 — the two winners (34 runs, ~20 h)

Workload stays at W-base except §6.7.

| Cell | `PAYLOAD_BYTES` | `RESERVE_DELAY_MS` |
|---|---|---|
| **C00** | 0 | 0 |
| **C01** | 0 | 25 |
| **C10** | 1048576 | 0 |
| **C11** | 1048576 | 25 |

**Before every `P=1 MiB` run** (C10, C11 and the §6.7 strip): confirm `df -h /` shows ≥120 GB
free, and abort the run if free space falls below 40 GB. TO's `additional_bytes` column is
TOASTed and rewritten on every reserve; `TRUNCATE` reclaims only between runs, never within
one. Do not tune autovacuum to suppress the bloat — it is part of what the lever measures.

All `P=1 MiB` runs also carry `WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m`. The default
5000 iterations would exceed the 5 min warmup cap at 1 MiB and silently deliver *fewer*
iterations, destroying the identical-starting-state property the fixed-iteration warmup
exists for. Confirm the full count completed in `<run>/warmup/k6.log`.

### 6.1 Cell breakpoints (6 runs)

C00's breakpoint is **reused** from phase 1 — for each winner it is the same config on the
same binary, so it is not re-run. Staircases below are first guesses; the §2.1 bracketing
rule applies, and within a cell both winners must end up on the *same* staircase.

```bash
# ── C01 · delay only · staircase 10/15/10 (peak 145)
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C01 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    RESERVE_DELAY_MS=25 STEP_START=10 STEP_INC=15 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

git checkout <ES-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C01 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    RESERVE_DELAY_MS=25 STEP_START=10 STEP_INC=15 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── C10 · payload only · staircase 5/5/10 (peak 50)
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C10 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=5 STEP_INC=5 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

git checkout <ES-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C10 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=5 STEP_INC=5 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── C11 · both · staircase 2/3/10 (peak 29)
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C11 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=2 STEP_INC=3 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

git checkout <ES-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C11 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=2 STEP_INC=3 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last
```

### 6.2 Compute `K` per cell

```bash
python3 k6/bench/compare.py --knee bench-results/*_capacity_C01_*
python3 k6/bench/compare.py --knee bench-results/*_capacity_C10_*
python3 k6/bench/compare.py --knee bench-results/*_capacity_C11_*
```

For each cell, `K = min(knee of the TO winner, knee of the ES winner)`. `K` for C00 comes
from the two winners' **W-base** knees in §7 Table A. Record all four in §7 Table C, then
derive:

| Test | Setting |
|---|---|
| soak | `RATE = round(0.6 × K)` |
| spike | `SPIKE_BASE = round(0.4 × K)`, `SPIKE_FACTOR=4` → peak `1.6 × K` |
| stress | `RATE = round(1.25 × K)` |

One `K` per cell keeps the two winners comparable within it. If one winner collapses at C10
or C11, the other will soak nearly idle there — that is the accepted price of a common rate.

### 6.3 C00 — reference (6 runs)

Substitute `<S00>` = `round(0.6 × K_C00)`, `<B00>` = `round(0.4 × K_C00)`,
`<X00>` = `round(1.25 × K_C00)`.

```bash
# soak
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=soak RUN_LABEL=C00 RATE=<S00> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=soak RUN_LABEL=C00 RATE=<S00> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# spike
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=spike RUN_LABEL=C00 SPIKE_BASE=<B00> SPIKE_FACTOR=4 \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=spike RUN_LABEL=C00 SPIKE_BASE=<B00> SPIKE_FACTOR=4 \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# stress
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=stress RUN_LABEL=C00 RATE=<X00> DURATION=10m DRAIN_TIMEOUT=1800 \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=stress RUN_LABEL=C00 RATE=<X00> DURATION=10m DRAIN_TIMEOUT=1800 \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
```

An undrained backlog on a `stress` run is the measurement, not a broken run — the `stress`
thresholds block disables the drain validity gate for exactly this reason. What must still
hold: every admitted order reaches a terminal state, and the API does not restart.

### 6.4 C01 — delay only (6 runs)

Substitute `<S01>`, `<B01>`, `<X01>` from `K_C01`.

```bash
# soak
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=soak RUN_LABEL=C01 RATE=<S01> RESERVE_DELAY_MS=25 \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=soak RUN_LABEL=C01 RATE=<S01> RESERVE_DELAY_MS=25 \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# spike
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=spike RUN_LABEL=C01 SPIKE_BASE=<B01> SPIKE_FACTOR=4 RESERVE_DELAY_MS=25 \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=spike RUN_LABEL=C01 SPIKE_BASE=<B01> SPIKE_FACTOR=4 RESERVE_DELAY_MS=25 \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# stress
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=stress RUN_LABEL=C01 RATE=<X01> DURATION=10m DRAIN_TIMEOUT=1800 \
    RESERVE_DELAY_MS=25 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=stress RUN_LABEL=C01 RATE=<X01> DURATION=10m DRAIN_TIMEOUT=1800 \
    RESERVE_DELAY_MS=25 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
```

### 6.5 C10 — payload only (6 runs)

Substitute `<S10>`, `<B10>`, `<X10>` from `K_C10`. Soak is **15 min**, not 45.
Check `df -h /` before each run.

```bash
# soak (15m)
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=soak RUN_LABEL=C10 RATE=<S10> SOAK_DURATION=15m PAYLOAD_BYTES=1048576 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=soak RUN_LABEL=C10 RATE=<S10> SOAK_DURATION=15m PAYLOAD_BYTES=1048576 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# spike
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=spike RUN_LABEL=C10 SPIKE_BASE=<B10> SPIKE_FACTOR=4 PAYLOAD_BYTES=1048576 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=spike RUN_LABEL=C10 SPIKE_BASE=<B10> SPIKE_FACTOR=4 PAYLOAD_BYTES=1048576 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# stress
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=stress RUN_LABEL=C10 RATE=<X10> DURATION=10m DRAIN_TIMEOUT=1800 \
    PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=stress RUN_LABEL=C10 RATE=<X10> DURATION=10m DRAIN_TIMEOUT=1800 \
    PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
```

### 6.6 C11 — both (6 runs)

Substitute `<S11>`, `<B11>`, `<X11>` from `K_C11`. Soak is **15 min**.
The heaviest cell in the campaign — check `df -h /` before every run.

```bash
# soak (15m)
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=soak RUN_LABEL=C11 RATE=<S11> SOAK_DURATION=15m \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=soak RUN_LABEL=C11 RATE=<S11> SOAK_DURATION=15m \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# spike
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=spike RUN_LABEL=C11 SPIKE_BASE=<B11> SPIKE_FACTOR=4 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=spike RUN_LABEL=C11 SPIKE_BASE=<B11> SPIKE_FACTOR=4 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last

# stress
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=stress RUN_LABEL=C11 RATE=<X11> DURATION=10m DRAIN_TIMEOUT=1800 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=stress RUN_LABEL=C11 RATE=<X11> DURATION=10m DRAIN_TIMEOUT=1800 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 ./k6/bench/bench.sh
archive_last
```

### 6.7 Workload strip at C11 (4 runs)

The one place the campaign asks whether contention and fan-out compound with the
aggregate-cost levers. Breakpoints only, at the most-stressed cell. Staircases start very
low — expect to apply the §2.1 bracketing rule here more than anywhere else.

```bash
# ── W-hot at C11 · staircase 2/2/10 (peak 20)
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C11-Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=2 STEP_INC=2 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C11-Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=2 STEP_INC=2 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last

# ── W-fan at C11 · staircase 1/1/10 (peak 10)
# 16 lines x 1 MiB per order. This is the heaviest run in the campaign; check df first.
git checkout <TO-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C11-Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=1 STEP_INC=1 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last
git checkout <ES-WIN> && docker compose down -v
env SCENARIO=capacity RUN_LABEL=C11-Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=1 STEP_INC=1 STEP_COUNT=10 ./k6/bench/bench.sh
archive_last
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
persisting only `OrderCreatedEvent`, so k6 sees admission latency — typically three orders of
magnitude below reality. Real latency is `order_e2e_time` in `dump.json`.

**No run is repeated in this campaign**, so there is no spread estimate and no error bars.
Differences under roughly 10% are reported as "not separated by this campaign", not as a
ranking. If a headline number surprises you, spot-repeat that single run before writing it up.

To view any archived run at full fidelity: open `the-dashboard` in Grafana, switch the
"Data source" dropdown to "Prometheus Replay", and set the time picker to that run's
`windows.full` from its `meta.json`. See [`bench-replay.md`](bench-replay.md).
