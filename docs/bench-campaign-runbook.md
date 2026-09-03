# Benchmark campaign runbook

Every command for the campaign, in execution order. 70 runs (27 + 9 + 34), ~54 h of machine
time including a ~10% re-run allowance. Phase-1 counts track the registry, which has changed three
times: seven variants from 2026-08-20, eight when `TO-2-push` landed, nine when `TO-2-fix-A` was
registered. A stale copy saying 21/7 or 24/8 predates one of those — and a copy saying 24/8 whose
set includes `ES-3` or `TO-2-opt` predates the retirements too, and means a different eight.

Ported from `TO-3`'s copy on 2026-08-06 and rewritten against `scripts/run-suite.sh`. The
original drove one branch at a time — `git checkout <branch> && docker compose down -v` then
`./scripts/bench_run.sh` — which is exactly the loop `main` now owns. Every per-branch block in
phase 1 collapses to a single command here.

---

## 0. Read this before the first command

### What is still blocked

The campaign design assumes `RESERVE_DELAY_MS` and `PAYLOAD_BYTES` are honoured on **every**
branch. As of 2026-08-06 both are, so §6 is unconstrained — any pair of winners produces valid
`C01`, `C10` and `C11` cells.

| Knob | Honoured on | Implemented as |
|---|---|---|
| `PAYLOAD_BYTES` | all nine | TO: `additional_bytes` column (V6). ES: aggregate state from the creation event. |
| `RESERVE_DELAY_MS` | all nine | TO: `reserve_delay_ms` column (V5, V2 on TO-3). ES: aggregate state, slept in the `@CommandHandler`. |

`run-suite.sh` still warns if a knob is set for a variant that lacks it, reading
`variants.env`'s capability column. Heed it if it ever fires: k6 sends both fields to every
branch, Spring ignores unknown JSON properties, and `meta.json` records what k6 was *told*
rather than what the server applied — so `compare.py` would print the requested value on a
row that never paid it, and nothing downstream could tell.

**One caveat that does still apply.** Both levers are paid under the row lock (TO) or the
aggregate lock (ES), so they cut the achievable rate hard. The §6.1 staircases already start
low for that reason; §2.1's bracketing rule matters more in phase 2 than anywhere else.

### Runs go through `scripts/run-suite.sh`

It replaces the checkout-and-teardown ritual, and preserves the Prometheus TSDB the way
`bench_run.sh` did — `prom_snapshot.sh` then `prom_archive.sh`, **on by default**.

`bench.sh` keeps no TSDB of its own. The raw series live in the `prometheus-data` volume, and
the `down -v` between variants destroys them. What survives unaided is `dump.json`'s ~20
extracted series, which feed the comparison tables — but nothing rebuilds a dashboard from
them. Every `mongodb_*` metric, journal volume, lock queue, checkpoints, GC pause, driver pool, Tomcat, and
on the TO family the outbox and order-timing panels exist only in the snapshot, so a run whose
snapshot was skipped cannot be looked at afterwards at all.

Two copies are made, both immune to `down -v`:

| Destination | What it is |
|---|---|
| `bench-results/<run_id>/prom-snapshot/` | an ordinary host directory, beside `dump.json` and `report.pdf` |
| `bench-replay-mongo` | a docker volume declared `external: true` |

`--no-archive-tsdb` skips the volume merge; `--no-snapshot-tsdb` skips both. `SNAPSHOT_TSDB=0`
and `ARCHIVE_TSDB=0` also work, matching `bench_run.sh`'s names.

### Do **not** pass `ALLOW_DIRTY=1`

It lets `bench.sh` start with an uncommitted change inside the image it is about to measure,
but `evaluate.py` still counts `git_dirty` as a **validity** check, so every such run reports
`INVALID` and is unusable. Commit in the variant's worktree instead.

`run-suite.sh` counts the dirty paths in **`.worktrees/<variant>/`**, not in `main` — `main`
has no application code, so a count taken there was unconditionally zero and the check was
vacuous. The pathspec is exactly what the variant `Dockerfile` COPYs (`src/`, `gradle/`, the
wrapper and build scripts, `Dockerfile` itself), so it answers "could an uncommitted edit be
inside the image?" and ignores everything that cannot be — including the `bench-results`
symlink the harness plants in each worktree.

Note the trap this replaces: when the primary checkout happens to sit on a variant branch,
`worktree_path` returns that checkout and `build-images.sh` builds from it, so a stray edit
there really does get baked into the benchmarked image.

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
`run-suite.sh` passes `SKIP_BUILD=1`. `docker volume create bench-replay-mongo` is no longer
needed either — `run-suite.sh` does it.

`run-suite.sh` rebuilds any variant whose image is behind its branch head, so the explicit
build above is only to get the cold-cache cost out of the way before a long campaign.

### After every run, check

- **`VERDICT`.** `INVALID` is never a result — re-run once; if it recurs for the same reason,
  the reason is the finding. The suite prints a per-variant table at the end.
- **`knee`**, on `capacity` runs — apply the bracketing rule in §2.1.
- **the `TSDB ->` line.** If it says the snapshot failed, the run's own artifacts are still
  valid, but it will never appear in the `bench-runs` dropdown — there is no TSDB to show.
- **the knob warning**, if you set `RESERVE_DELAY_MS` or `PAYLOAD_BYTES`.

`image_fresh` compares the image's `Created` against the HEAD of **the branch it was built
from**, which `run-suite.sh` resolves from `.worktrees/<variant>/` and passes to `bench.sh`.
Two things had to be true for that to be meaningful, and both are:

- `build-images.sh` stamps the commit SHA as a **`RUN`** layer, so a docs-only commit on the
  *variant* branch cannot leave the image dated before that branch's `HEAD`.
- The comparison is against the variant's `HEAD`, not `main`'s. It used to be `main`'s, which
  is a completely unrelated clock — one docs-only commit here marked all eight images stale
  and returned `INVALID` × 8 after a full campaign's worth of machine time.

It is still worth a glance when you pass `--no-build`: an image left behind its branch head
is exactly what the check exists to catch.

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

## 2. Phase 1 — breakpoints (27 runs, ~17 h)

Grouped **by workload point, not by variant**, so a mis-calibrated staircase surfaces on run
1 of 9 rather than run 27 of 27. Each block below is all nine variants; `run-suite.sh` walks
them in registry order (TO-1, TO-2, TO-2-fix-A, TO-2-push, TO-3, TO-4, then ES-1, ES-2, ES-4),
tearing the stack down between each.

**The set is nine as of 2026-08-23**, when `TO-2-fix-A` and `TO-2-push` were both registered.
It was seven between 2026-08-20 and then. A revision of this file planning 21 or 24 runs predates
one of those additions — and one planning 24 runs across a set containing `ES-3` or `TO-2-opt`
predates the retirements as well and is counting a different eight. Those two went on 2026-08-20;
see [`retired-variants.md`](retired-variants.md) for why and for what re-adding either would take.

**Three of the nine are arms off `TO-2`** — `TO-2` (seq cursor), `TO-2-fix-A` (xid8 watermark) and
`TO-2-push` (payload-carrying NOTIFY). Each is single-variable against `TO-2` but NOT against the
others, so §5's ranking must not treat them as three independent samples of one design.

**`ES-1`, `ES-2` and `ES-4` mean lock-free code as of 2026-08-20** — they adopted the trees of the
former `ES-*-NullLock` branches, which are gone. A run directory from before that date carries the
same name and `PessimisticLockFactory` code, so date any archived ES run before it enters a table
here.

`--continue-on-fail` is deliberate: one variant failing its staircase should not cost the
other six their runs. The end-of-suite table shows who failed.

### 2.1 The bracketing rule — apply after every block

- knee at the **last step**, or `require_knee` unsatisfied → double `STEP_INC`, re-run
- knee at **step 0** → halve `STEP_START`, re-run

**If you change a staircase, re-run every variant already measured at that point.** Knees read
off different staircases are not comparable, and the selection in §5 rests on comparing them.
Re-run a single variant with `--only <variant>`.

> Staircase knobs (`STEP_START`, `STEP_INC`, `STEP_COUNT`) are *calibration*: `points.env`
> supplies defaults and the shell overrides them silently, exactly so re-bracketing works.
> The identity knobs cannot be overridden — a conflicting value aborts the run rather than
> producing a mislabelled result.

### 2.2 W-base — staircase 40/40/10 (peak 400), ~35–50 min per run

```bash
env SCENARIO=capacity POINT=W-base DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --continue-on-fail
```

### 2.3 W-hot — staircase 20/20/12 (peak 240), ~40–55 min per run

```bash
env SCENARIO=capacity POINT=W-hot DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --continue-on-fail
```

### 2.4 W-fan — staircase 10/10/12 (peak 120), ~40–55 min per run

16 lines per order: one transaction on TO, sixteen sequential saga hops on ES. Expect the
lowest ES knees of phase 1 here.

```bash
env SCENARIO=capacity POINT=W-fan DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --continue-on-fail
```

---

## 3. Compute the common soak rate

```bash
python3 k6/bench/compare.py --knee bench-results/*_capacity_W-base_*
```

Record every knee in §7 Table A, then:

```
RATE = round(0.6 x the LOWEST knee across all nine W-base runs)
```

One rate for all nine. Comparing variants at different rates measures nothing, and this soak
is the headline head-to-head table. Write the number into Table A before running §4.

---

## 4. Phase 1 — soaks (9 runs, ~9 h)

W-base, 45 min each, all at the single `RATE` from §3. Substitute the computed number:

```bash
env SCENARIO=soak POINT=W-base RATE=<RATE> DRAIN_TIMEOUT=1800 \
    scripts/run-suite.sh --continue-on-fail
```

Read the results:

```bash
python3 k6/bench/compare.py bench-results/*_soak_W-base_*
python3 k6/bench/compare.py --cols saga bench-results/ES-*_soak_W-base_*
```

---

## 5. Selection — one winner per family

Fill §7 Table B, then, per family:

1. **Disqualify** any variant whose W-base soak verdict is not `PASS`. A variant that cannot
   hold the common rate for 45 minutes does not represent its family.
2. **Rank** the survivors by knee at W-base, W-hot and W-fan separately (1 = highest knee).
3. **Winner** = lowest mean of the three ranks.
4. **Tie** → lower `order_e2e` p95 (confirmed) in the W-base soak.

**The TO pool is not six independent designs.** `TO-2`, `TO-2-fix-A` and `TO-2-push` are three
arms of one branch, so this rule gives that design three chances at the family slot where `TO-1`,
`TO-3` and `TO-4` get one each. Rank them, but pick **at most one TO-2 arm** into the final
comparison: take the best-ranked arm, drop the other two, then apply steps 3-4 to what remains.
Otherwise "the TO winner" can mean "TO-2 won a lottery it entered three times", and Table B reads
as a design comparison when it is partly a within-design one.

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
would exceed the 5 min warmup cap at 1 MiB. That used to fail *silently* — the old
`shared-iterations` warmup stopped at `maxDuration` having delivered fewer iterations and
still exited 0, destroying the identical-starting-state property the fixed-iteration warmup
exists for. It is now a hard init-time abort, and a short delivery additionally trips a
`dropped_iterations` threshold, so the run cannot proceed on a truncated warmup. Confirm the
count in `<run>/warmup/k6.log` all the same.

**Every C cell must be given a `WARMUP_RATE`.** `points.env` sets one for the W points but
deliberately records `WARMUP_RATE=0` (uncalibrated) for `C01`/`C10`/`C11`, because a cell
that re-tunes the staircase has a capacity far below the W point it composes with — `C11`
peaks at 29/s against W-base's 595 — and silently inheriting W-base's 100/s would warm up at
more than 3x the cell's ceiling. A `0` aborts the run at k6 init; a shell-set value wins over
it without editing `points.env`. Pick roughly **half the slower winner's sustained rate** in
that cell, which after §6.1 you have from `tipping_point.py`. Before then, a conservative
first guess is ~25% of the staircase peak (C01 ~40/s, C10 ~15/s, C11 ~8/s); at 500 iterations
those are 13s, 34s and 63s of warmup.

### 6.1 Cell breakpoints (6 runs)

C00's breakpoint is **reused** from phase 1 — same config, same binary — so it is not re-run.
Staircases are first guesses; §2.1 applies, and within a cell both winners must end on the
*same* staircase.

```bash
# C01 - delay only - staircase 10/15/10 (peak 145)
env SCENARIO=capacity POINT=W-base,C01 WARMUP_RATE=<half slower winner's sustained> \
    DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# C10 - payload only - staircase 5/5/10 (peak 50)
env SCENARIO=capacity POINT=W-base,C10 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    WARMUP_RATE=<half slower winner's sustained> DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# C11 - both - staircase 2/3/10 (peak 29)
env SCENARIO=capacity POINT=W-base,C11 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    WARMUP_RATE=<half slower winner's sustained> DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail
```

### 6.2 Compute `K` per cell

```bash
python3 k6/bench/compare.py --knee bench-results/*_capacity_W-base-C01_*
python3 k6/bench/compare.py --knee bench-results/*_capacity_W-base-C10_*
python3 k6/bench/compare.py --knee bench-results/*_capacity_W-base-C11_*
```

`K = min(knee of the TO winner, knee of the ES winner)` per cell. `K_C00` comes from the two
winners' **W-base** knees in Table A. Record all four in Table C, then derive:

| Test | Setting |
|---|---|
| soak | `RATE = round(0.6 x K)` |
| spike | `SPIKE_PEAK = round(1.6 x K)` — the burst rate itself; the shape is idle → peak → idle |
| stress | `RATE = round(1.25 x K)` |

One `K` per cell keeps the two winners comparable within it. If one collapses at C10 or C11,
the other soaks nearly idle there — the accepted price of a common rate.

### 6.3–6.6 The four cells (24 runs)

Three tests per cell. `<Sxx>` = `round(0.6 x K)`, `<Bxx>` = `round(1.6 x K)`,
`<Xxx>` = `round(1.25 x K)`. `POINT=W-base,<CELL>` supplies `DISTINCT_ITEMS`,
`ITEMS_PER_ORDER`, `PAYLOAD_BYTES` and `RESERVE_DELAY_MS` together; `CELL_ARGS` is only what
a point cannot carry:

| Cell | `CELL_ARGS` |
|---|---|
| C00 | *(none — inherits W-base's `WARMUP_RATE=100`)* |
| C01 | `WARMUP_RATE=<half slower winner's sustained>` |
| C10 | `WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m WARMUP_RATE=<half slower winner's sustained>` |
| C11 | `WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m WARMUP_RATE=<half slower winner's sustained>` |

Soak is 45 min at C00 and C01, and **15 min** at C10 and C11 (add `SOAK_DURATION=15m`).

```bash
# soak
env SCENARIO=soak POINT=W-base,<CELL> RATE=<Sxx> \
    <CELL_ARGS> DRAIN_TIMEOUT=1800 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# spike
env SCENARIO=spike POINT=W-base,<CELL> SPIKE_PEAK=<Bxx> \
    <CELL_ARGS> DRAIN_TIMEOUT=1800 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# stress
env SCENARIO=stress POINT=W-base,<CELL> RATE=<Xxx> DURATION=10m \
    <CELL_ARGS> DRAIN_TIMEOUT=1800 \
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

Both staircases below override C11's own 2/3/10 default — the calibration knobs are for
exactly this, per the note in §2.1. `WARMUP_RATE` is a calibration knob too and must be
re-stated here: composing `W-hot,C11` resolves it to C11's uncalibrated `0`, which aborts.

```bash
# W-hot at C11 - staircase 2/2/10 (peak 20)
env SCENARIO=capacity POINT=W-hot,C11 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    WARMUP_RATE=<half slower winner's sustained> \
    STEP_START=2 STEP_INC=2 STEP_COUNT=10 DRAIN_TIMEOUT=3600 \
    scripts/run-suite.sh --only <TO-WIN>,<ES-WIN> --continue-on-fail

# W-fan at C11 - staircase 1/1/10 (peak 10)
# 16 lines x 1 MiB per order - the heaviest run in the campaign. Check df -h / first.
env SCENARIO=capacity POINT=W-fan,C11 \
    WARMUP_ITERATIONS=500 WARMUP_MAX_DURATION=20m \
    WARMUP_RATE=<half slower winner's sustained> \
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
| ES-4 | | | | | | |

**`<TO-WIN>` =** ______   **`<ES-WIN>` =** ______

### Table C — phase 2 per-cell rates

| Cell | knee TO-WIN | knee ES-WIN | `K` | soak `0.6K` | spike peak `1.6K` | stress `1.25K` |
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
python3 k6/bench/compare.py bench-results/*_soak_W-base_*            # phase 1 head-to-head
python3 k6/bench/compare.py --cols saga bench-results/ES-*           # ES saga internals
python3 k6/bench/compare.py --cols resource --baseline <run> <run>   # resource deltas
```

**End-to-end latency is not in the k6 output.** `POST /inventory/orders` returns 202 after
persisting only `OrderCreatedEvent`, so k6 sees admission latency — typically three orders of
magnitude below reality. Real latency is `order_e2e_time` in `dump.json`.

**No run is repeated**, so there is no spread estimate and no error bars. Differences under
roughly 10% are reported as "not separated by this campaign", not as a ranking. If a headline
number surprises you, spot-repeat that single run with `--only <variant>` before writing it up.

### Viewing any archived run at full fidelity

Because the TSDB is snapshotted on every run, every panel works months later:

1. Bring up the replay stack:
   `COMPOSE_PROJECT_NAME=iir docker compose -f docker-compose.replay.yml up -d`
2. Open **`bench-runs`** at `http://localhost:3001/d/bench-runs/`.
3. Pick the run from the **Run** dropdown, and leave the time picker alone.

If the dropdown does not list it, rebuild:
`python3 -m scripts.dashboards.build --runs bench-results`. If a snapshot was skipped for that
run, no rebuild will help — there is nothing archived to show. See
[`bench-replay.md`](bench-replay.md) for the whole path.

To re-merge a host-side snapshot that failed to archive at the time, from that variant's
worktree:

```bash
./scripts/prom_archive.sh bench-results/<run_id>/prom-snapshot
```
