# Benchmark campaign runbook

Every command for the campaign, in execution order. 66 runs (24 + 8 + 34), ~47 h of machine
time including a ~10% re-run allowance.

Ported from `TO-3`'s copy on 2026-08-06 and rewritten against `scripts/run-suite.sh`. The
original drove one branch at a time — `git checkout <branch> && docker compose down -v` then
`./scripts/bench_run.sh` — which is exactly the loop `main` now owns. Every eight-command
block in phase 1 collapses to a single command here.

---

## 0. Read this before the first command

### What is still blocked

The campaign design assumes `RESERVE_DELAY_MS` and `PAYLOAD_BYTES` are honoured on **all
eight** branches. They are not, and nothing at run time will tell you:

| Knob | Honoured on | Ignored on |
|---|---|---|
| `RESERVE_DELAY_MS` | `ES-4`, `TO-3` | `TO-1`, `TO-2`, `TO-4`, `ES-1`, `ES-2`, `ES-3` |
| `PAYLOAD_BYTES` | all four `ES-*`, `TO-3` | `TO-1`, `TO-2`, `TO-4` |

k6 sends both fields to every branch, Spring ignores unknown JSON properties, and `meta.json`
records what k6 was *told* rather than what the server applied — so `compare.py` prints the
requested value for all eight while only some slept or padded. `run-suite.sh` warns and names
the affected variants before the first run; do not ignore it.

**Consequence for §6.** Cells `C01`, `C10` and `C11` are only meaningful if the winners are
`ES-4` and `TO-3`. If phase 1 selects any other TO winner, its `C01`/`C10`/`C11` runs measure
the `C00` binary and the comparison is void. Either finish the outstanding phase-0
application work first, or record the winner constraint explicitly.

`PAYLOAD_BYTES` on `TO-1`/`TO-2`/`TO-4` is the subtler case: the field is accepted and rides
`InventoryCreatedEvent`, but those branches have no `additional_bytes` migration, so there is
no column on the row each reserve rewrites — the copy-on-write cost the lever exists to
measure never happens.

### Runs go through `scripts/run-suite.sh`

It replaces the checkout-and-teardown ritual, and preserves the Prometheus TSDB the way
`bench_run.sh` did — `prom_snapshot.sh` then `prom_archive.sh`, **on by default**.

`bench.sh` keeps no TSDB of its own. The raw series live in the `prometheus-data` volume, and
the `down -v` between variants destroys them. What survives unaided is `dump.json`'s ~20
extracted series — 20 of the merged dashboard's 56 panels. Every `pg_stat_*` metric, WAL size,
locks, checkpoints, GC pause, HikariCP, Tomcat, and on the TO family the outbox and
order-timing panels have no archived equivalent at any later effort, because they were never
extracted in the first place.

Two copies are made, both immune to `down -v`:

| Destination | What it is |
|---|---|
| `bench-results/<run_id>/prom-snapshot/` | an ordinary host directory, beside `dump.json` and `report.pdf` |
| `bench-replay-data` | a docker volume declared `external: true` |

`--no-archive-tsdb` skips the volume merge; `--no-snapshot-tsdb` skips both. `SNAPSHOT_TSDB=0`
and `ARCHIVE_TSDB=0` also work, matching `bench_run.sh`'s names.

### Do **not** pass `ALLOW_DIRTY=1`

It lets `bench.sh` start with uncommitted changes under `src/`, but `evaluate.py` still counts
`git_dirty` as a **validity** check, so every such run reports `INVALID` and is unusable.
Commit `src/` in the variant's worktree instead — `git_dirty` only counts changes under `src/`.

### Shell

Commands are bash form. In `fish` the `VAR=value command` prefix is not supported — start a
`bash` subshell, or prefix each command with `env`.

### Preflight, once per session

```bash
id -u                                  # must NOT be 0 — the harness refuses to run as root
test -w bench-results && echo writable # must print "writable"
df -h /                                # >=120 GB free before any PAYLOAD_BYTES=1048576 run
scripts/build-images.sh                # one image per branch; ~8 gradle builds on a cold cache
```

No `JAVA_HOME` is needed anywhere: the `Dockerfile` runs gradle inside the build, and
`run-suite.sh` passes `SKIP_BUILD=1`. `docker volume create bench-replay-data` is no longer
needed either — `run-suite.sh` does it.

`run-suite.sh` rebuilds any variant whose image is behind its branch head, so the explicit
build above is only to get the cold-cache cost out of the way before a long campaign.

### After every run, check

- **`VERDICT`.** `INVALID` is never a result — re-run once; if it recurs for the same reason,
  the reason is the finding. The suite prints a per-variant table at the end.
- **`knee`**, on `capacity` runs — apply the bracketing rule in §2.1.
- **the `TSDB ->` line.** If it says the snapshot failed, the run's own artifacts are still
  valid but it will only ever replay from `dump.json`.
- **the knob warning**, if you set `RESERVE_DELAY_MS` or `PAYLOAD_BYTES`.

`image_fresh` no longer needs watching: `build-images.sh` stamps the commit SHA as a `RUN`
layer, so a docs-only commit can no longer leave the image dated before `HEAD`.

---

## 1. Workload points

| Point | `DISTINCT_ITEMS` | `ITEMS_PER_ORDER` | Probes |
|---|---|---|---|
| **W-base** | 100 | 4 | reference — low contention, moderate fan-out |
| **W-hot** | 8 | 4 | contention |
| **W-fan** | 100 | 16 | fan-out |

`PAYLOAD_BYTES=0` and `RESERVE_DELAY_MS=0` throughout phase 1 — both are harness defaults, so
they are not passed.

`DRAIN_TIMEOUT=3600` on breakpoints, `1800` elsewhere. The default is 900 s, which several
past capacity runs exceeded — and a drain timeout is an automatic `INVALID`.

---

## 2. Phase 1 — breakpoints (24 runs, ~15 h)

Grouped **by workload point, not by variant**, so a mis-calibrated staircase surfaces on run
1 of 8 rather than run 24 of 24. Each block below is all eight variants; `run-suite.sh` walks
them in registry order (TO-1..TO-4 then ES-1..ES-4), tearing the stack down between each.

`--continue-on-fail` is deliberate: one variant failing its staircase should not cost the
other seven their runs. The end-of-suite table shows who failed.

### 2.1 The bracketing rule — apply after every block

- knee at the **last step**, or `require_knee` unsatisfied → double `STEP_INC`, re-run
- knee at **step 0** → halve `STEP_START`, re-run

**If you change a staircase, re-run every variant already measured at that point.** Knees read
off different staircases are not comparable, and the selection in §5 rests on comparing them.
Re-run a single variant with `--only <variant>`.

### 2.2 W-base — staircase 40/40/10 (peak 400), ~35–50 min per run

```bash
env SCENARIO=capacity RUN_LABEL=Wbase DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    STEP_START=40 STEP_INC=40 STEP_COUNT=10 DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --continue-on-fail
```

### 2.3 W-hot — staircase 20/20/12 (peak 240), ~40–55 min per run

```bash
env SCENARIO=capacity RUN_LABEL=Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    STEP_START=20 STEP_INC=20 STEP_COUNT=12 DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --continue-on-fail
```

### 2.4 W-fan — staircase 10/10/12 (peak 120), ~40–55 min per run

16 lines per order: one transaction on TO, sixteen sequential saga hops on ES. Expect the
lowest ES knees of phase 1 here.

```bash
env SCENARIO=capacity RUN_LABEL=Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    STEP_START=10 STEP_INC=10 STEP_COUNT=12 DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --continue-on-fail
```

---

## 3. Compute the common soak rate

```bash
python3 scripts/compare.py --knee bench-results/*_capacity_Wbase_*
```

Record every knee in §7 Table A, then:

```
RATE = round(0.6 x the LOWEST knee across all eight W-base runs)
```

One rate for all eight. Comparing variants at different rates measures nothing, and this soak
is the headline head-to-head table. Write the number into Table A before running §4.

---

## 4. Phase 1 — soaks (8 runs, ~8 h)

W-base, 45 min each, all at the single `RATE` from §3. Substitute the computed number:

```bash
env SCENARIO=soak RUN_LABEL=Wbase RATE=<RATE> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    DRAIN_TIMEOUT=1800 scripts/run-suite.sh --continue-on-fail
```

Read the results:

```bash
python3 scripts/compare.py bench-results/*_soak_Wbase_*
python3 scripts/compare.py --cols saga bench-results/ES-*_soak_Wbase_*
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

**Before starting §6, re-read "What is still blocked" in §0.** If `<TO-WIN>` is not `TO-3`,
its `C01`/`C10`/`C11` runs measure the `C00` binary.

---

## 6. Phase 2 — the two winners (34 runs, ~20 h)

Workload stays at W-base except §6.7. Every command is `--only <TO-WIN>,<ES-WIN>`.

| Cell | `PAYLOAD_BYTES` | `RESERVE_DELAY_MS` |
|---|---|---|
| **C00** | 0 | 0 |
| **C01** | 0 | 25 |
| **C10** | 1048576 | 0 |
| **C11** | 1048576 | 25 |

**Before every `P=1 MiB` run**, confirm `df -h /` shows >=120 GB free; abort if free space
falls below 40 GB mid-run. TO's `additional_bytes` column is TOASTed and rewritten on every
reserve, and `TRUNCATE` reclaims only *between* runs, never within one. Do not tune autovacuum
to suppress the bloat — it is part of what the lever measures. (Note the measured caveat: 1 MiB
of repeated bytes TOASTs to roughly 12 kB, so the disk-bloat premise is far weaker than the
original design assumed.)

All `P=1 MiB` runs carry `WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m`. The default 5000
would exceed the 5 min warmup cap at 1 MiB and silently deliver *fewer* iterations, destroying
the identical-starting-state property the fixed-iteration warmup exists for. Confirm the full
count in `<run>/warmup/k6.log`.

### 6.1 Cell breakpoints (6 runs)

C00's breakpoint is **reused** from phase 1 — same config, same binary — so it is not re-run.
Staircases are first guesses; §2.1 applies, and within a cell both winners must end on the
*same* staircase.

```bash
# C01 - delay only - staircase 10/15/10 (peak 145)
env SCENARIO=capacity RUN_LABEL=C01 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    RESERVE_DELAY_MS=25 STEP_START=10 STEP_INC=15 STEP_COUNT=10 DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# C10 - payload only - staircase 5/5/10 (peak 50)
env SCENARIO=capacity RUN_LABEL=C10 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=5 STEP_INC=5 STEP_COUNT=10 DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# C11 - both - staircase 2/3/10 (peak 29)
env SCENARIO=capacity RUN_LABEL=C11 DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=2 STEP_INC=3 STEP_COUNT=10 DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail
```

### 6.2 Compute `K` per cell

```bash
python3 scripts/compare.py --knee bench-results/*_capacity_C01_*
python3 scripts/compare.py --knee bench-results/*_capacity_C10_*
python3 scripts/compare.py --knee bench-results/*_capacity_C11_*
```

`K = min(knee of the TO winner, knee of the ES winner)` per cell. `K_C00` comes from the two
winners' **W-base** knees in Table A. Record all four in Table C, then derive:

| Test | Setting |
|---|---|
| soak | `RATE = round(0.6 x K)` |
| spike | `SPIKE_BASE = round(0.4 x K)`, `SPIKE_FACTOR=4` → peak `1.6 x K` |
| stress | `RATE = round(1.25 x K)` |

One `K` per cell keeps the two winners comparable within it. If one collapses at C10 or C11,
the other soaks nearly idle there — the accepted price of a common rate.

### 6.3–6.6 The four cells (24 runs)

Three tests per cell. `<Sxx>` = `round(0.6 x K)`, `<Bxx>` = `round(0.4 x K)`,
`<Xxx>` = `round(1.25 x K)`. `CELL_ARGS` is the cell's knob pair:

| Cell | `CELL_ARGS` |
|---|---|
| C00 | *(none)* |
| C01 | `RESERVE_DELAY_MS=25` |
| C10 | `PAYLOAD_BYTES=1048576 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m` |
| C11 | `PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m` |

Soak is 45 min at C00 and C01, and **15 min** at C10 and C11 (add `SOAK_DURATION=15m`).

```bash
# soak
env SCENARIO=soak RUN_LABEL=<CELL> RATE=<Sxx> DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 \
    <CELL_ARGS> DRAIN_TIMEOUT=1800 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# spike
env SCENARIO=spike RUN_LABEL=<CELL> SPIKE_BASE=<Bxx> SPIKE_FACTOR=4 \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 <CELL_ARGS> DRAIN_TIMEOUT=1800 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# stress
env SCENARIO=stress RUN_LABEL=<CELL> RATE=<Xxx> DURATION=10m \
    DISTINCT_ITEMS=100 ITEMS_PER_ORDER=4 <CELL_ARGS> DRAIN_TIMEOUT=1800 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail
```

An undrained backlog on a `stress` run is the measurement, not a broken run — the `stress`
thresholds block sets `require_backlog_drained: false`, and it is the only scenario that does.
The check still appears in `verdict.json`, as kind `info`, so the observation is recorded
without affecting the verdict. What must still hold: every admitted order reaches a terminal
state, and the API does not restart.

### 6.7 Workload strip at C11 (4 runs)

The one place the campaign asks whether contention and fan-out compound with the aggregate-cost
levers. Breakpoints only, at the most-stressed cell. Staircases start very low — expect to apply
§2.1 here more than anywhere else.

```bash
# W-hot at C11 - staircase 2/2/10 (peak 20)
env SCENARIO=capacity RUN_LABEL=C11-Whot DISTINCT_ITEMS=8 ITEMS_PER_ORDER=4 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=2 STEP_INC=2 STEP_COUNT=10 DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# W-fan at C11 - staircase 1/1/10 (peak 10)
# 16 lines x 1 MiB per order - the heaviest run in the campaign. Check df -h / first.
env SCENARIO=capacity RUN_LABEL=C11-Wfan DISTINCT_ITEMS=100 ITEMS_PER_ORDER=16 \
    PAYLOAD_BYTES=1048576 RESERVE_DELAY_MS=25 WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    STEP_START=1 STEP_INC=1 STEP_COUNT=10 DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail
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

**Lowest W-base knee:** ______  →  **`RATE` = 0.6 x that, rounded:** ______

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
python3 scripts/compare.py --knee bench-results/*_capacity_*        # staircases and knees
python3 scripts/compare.py bench-results/*_soak_Wbase_*             # phase 1 head-to-head
python3 scripts/compare.py --cols saga bench-results/ES-*           # ES saga internals
python3 scripts/compare.py --cols resource --baseline <run> <run>   # resource deltas
```

**End-to-end latency is not in the k6 output.** `POST /inventory/orders` returns 202 after
persisting only `OrderCreatedEvent`, so k6 sees admission latency — typically three orders of
magnitude below reality. Real latency is `order_e2e_time` in `dump.json`.

**No run is repeated**, so there is no spread estimate and no error bars. Differences under
roughly 10% are reported as "not separated by this campaign", not as a ranking. If a headline
number surprises you, spot-repeat that single run with `--only <variant>` before writing it up.

### Viewing any archived run at full fidelity

Because the TSDB is snapshotted on every run, every panel works months later:

1. Open `the-dashboard` in Grafana.
2. Switch the **"Data source"** dropdown from "Prometheus" to **"Prometheus Replay"**.
3. Set the time picker to that run's `windows.full` from its `meta.json`.

If a snapshot was ever skipped, that run falls back to `dump.json` — ~20 of 56 panels.

To re-merge a host-side snapshot that failed to archive at the time, from that variant's
worktree:

```bash
./scripts/prom_archive.sh bench-results/<run_id>/prom-snapshot
```
