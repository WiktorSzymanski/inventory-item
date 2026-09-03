# Bench replay — viewing archived runs in Grafana

The benchmark harness (`k6/bench/bench.sh`, see [`k6/README.md`](../k6/README.md)) keeps no
Prometheus TSDB by itself: every run's raw metrics die with the `docker-compose.bench.yml` volume
unless something copies them out before the next run resets it. Keeping a run means snapshotting
the live Prometheus's TSDB right after the run and merging its blocks into a long-lived archive
(`bench-replay-data`), which a second Prometheus (`prometheus-replay`, port **9091**) serves.

There is exactly one dashboard for looking at what is in there: **`bench-runs`**, a Run dropdown
over the archive, every run drawn on one axis starting at its own t0. The live dashboard
(`the-dashboard`) does not read the archive at all — see §6.

| | dashboard | datasource | what it shows |
|---|---|---|---|
| during a run | `the-dashboard` | `prometheus` (live) | the stack right now, 5s refresh |
| after a run | `bench-runs` | `prometheus-replay` (archive) | one archived run, picked from a dropdown |

**In a hurry?** §1 is the whole path, from a directory of runs to a dashboard you can browse.

---

## 1. Quickstart — a directory of runs to a browsable dashboard

Start to finish on a machine with no archive, given a directory of finished runs. Sections
2-5 explain each piece; this is the path.

**First, check the runs qualify.** A run needs **both** `meta.json` (it finished) and
`prom-snapshot/` (its TSDB was captured). Anything missing either is skipped silently —
`dump.json` alone cannot draw a dashboard.

```bash
python3 - ./bench-results <<'EOF'
import os, sys
root = sys.argv[1]
ok, skip = [], []
for dirpath, dirnames, filenames in os.walk(root):     # os.walk: see the note below
    dirnames.sort()
    snap = "prom-snapshot" in dirnames
    if snap:
        dirnames.remove("prom-snapshot")
    if "meta.json" not in filenames:
        continue
    dirnames[:] = []
    (ok if snap else skip).append(dirpath)
print(f"{len(ok)} loadable, {len(skip)} skipped")
for d in skip:
    print("  SKIP (no prom-snapshot):", d)
EOF
```

`os.walk` is not decoration, and neither is pruning `prom-snapshot/`. When `MAIN_ROOT` is not
the repo root, `ensure_results_link()` drops a **self-referential symlink** in the results
directory — `bench-results/bench-results -> <RESULTS_DIR>` — and a recursive glob (`**`)
follows it, so it finds every run a second time, or forever. `os.walk` does not follow
symlinks. Every TSDB block under `prom-snapshot/` also carries a `meta.json` of its own —
Prometheus' block descriptor, nothing to do with a run — which is why the walk stops at the
snapshot directory. `scan_runs()` does both, and additionally keys on `run_id`, so the SAME
run copied into two directories reaches the dropdown once (under the first path in sorted
order); anything you write yourself has to handle it.

Runs must also start before the anchor, **2027-01-01** — see §4 if yours do not.

**1. Wipe any existing archive** (skip if you are adding to one):

```bash
COMPOSE_PROJECT_NAME=iir docker compose -f docker-compose.replay.yml down
docker volume rm bench-replay-data
```

The `down` comes first because Docker will not remove a volume still attached to a container.
Losing the archive costs nothing as long as the `prom-snapshot/` directories are still on disk
— that is what it is rebuilt from.

**2. Load the runs.** This is the step that *creates* the volume:

```bash
RUNS_DIR=./bench-results \
  docker compose -f docker-compose.replay-load.yml up --abort-on-container-failure
```

`RUNS_DIR` is bind-mounted read-only and walked to **any** depth, so a campaign directory of
per-phase subdirectories — or a directory of those — is a valid single argument. Every run is
named in the dropdown by its path under `RUNS_DIR`, root directory first:
`Final-Bench - 3-Cache - ES-4_capacity_W-base_20260901T004957Z`. Three one-shot services run
in order: the dropdown rebuild, the block copy, the marker backfill (§5).
`prometheus-replay` must not be running — step 1 took it down.

> **Never `down -v` with `replay-load.yml`.** That file declares the archive volume
> non-external so it can create it, which also means `-v` would delete it. Plain `down` is
> fine.

**3. Start the viewer:**

```bash
COMPOSE_PROJECT_NAME=iir docker compose -f docker-compose.replay.yml up -d
```

No `docker volume create` needed — step 2 made it.

**4. Browse** at **http://localhost:3001/d/bench-runs/**. Pick a run from the **Run**
dropdown; every run is drawn on an axis starting at its own t0, so flipping between two
redraws them in place. **Leave the time picker alone.** `scripts/replay_url.sh <run-dir>`
prints a direct link to one run (`--open` launches a browser).

### Runs spread across several directories

`replay-load.yml` mounts one `RUNS_DIR`, and its dropdown rebuild covers only that directory —
a second pass would drop the first from the dropdown. Load each, then rebuild the dropdown once
across all of them:

```bash
RUNS_DIR=./bench-results \
  docker compose -f docker-compose.replay-load.yml up --abort-on-container-failure
docker compose -f docker-compose.replay-load.yml down

RUNS_DIR=./bench-results-2/bench-results \
  docker compose -f docker-compose.replay-load.yml up --abort-on-container-failure
docker compose -f docker-compose.replay-load.yml down

python3 -m scripts.dashboards.build --runs bench-results bench-results-2/bench-results
```

Blocks and markers from both passes accumulate in the volume; only the dropdown needs the
host-side rebuild. Grafana picks the new JSON up within 30s — no restart.

### Checking it worked

```bash
# runs in the dropdown
python3 -c "import json;d=json.load(open('monitoring/grafana/provisioning/dashboards/bench-runs.json'));v=[x for x in d['templating']['list'] if x['name']=='run'][0];print(len(v['options']),'options')"

# blocks in the archive
docker run --rm -v bench-replay-data:/p alpine:3.20 sh -c 'ls /p | wc -l; du -sh /p'
```

| symptom | cause |
|---|---|
| every panel is a parse error | markers missing, so `$end` resolved empty — re-run step 2 (§4) |
| a run you expected is not in the dropdown | it has no `prom-snapshot/`; it was loaded in a different pass than the last dropdown rebuild; or a copy of it under an earlier path already claimed its `run_id` |
| `external volume "bench-replay-data" not found` | step 3 was run before step 2 |
| `WARNING: no blocks in …` during the load | that run's `prom-snapshot/` exists but is empty — a failed capture; it reaches the dropdown and draws empty panels |

**On axis length:** the window is sized to the *longest* run in the set and each panel is
clipped at its own end, so mixing 75-minute runs with 10-minute ones leaves the short ones
blank most of the way across. A tighter set gives a tighter axis.

---

## 2. One-time setup

The archive Prometheus (`prometheus-replay`, host port **9091**) is deliberately not part of
`docker-compose.bench.yml` — `bench.sh` must never start, stop, or depend on it — and its data
volume is declared `external: true` so `docker compose down -v` cannot touch it. That matters:
the previous archive was destroyed by exactly that command.

External volumes are not auto-created by Compose, so on a machine that has never run this before,
create it first or `up` fails outright:

```bash
docker volume create bench-replay-data
COMPOSE_PROJECT_NAME=iir docker compose -f docker-compose.replay.yml up -d
```

Both commands are idempotent — safe to run again on a machine that already has the archive.
`run-suite.sh` creates the volume itself, so in practice only the second line is ever needed. That
`up` starts both halves of the replay stack: `prometheus-replay` (`:9091`) and its own Grafana,
`grafana-replay`, on **`:3001`** — deliberately separate from the benchmark stack's Grafana on
`:3000`, so browsing the archive never depends on a benchmark stack being up.

**Two details in that command are load-bearing.**

`docker-compose.replay.yml` is used **alone**, not merged with `docker-compose.yml`. The
latter declares `image: ${IMAGE_TAG:?…}` with no default — deliberately, so a bare
`docker compose up` cannot silently benchmark whichever variant was last built — and Compose
interpolates the whole merged file before it looks at which service you asked for. So any
command naming both files fails outright with `required variable IMAGE_TAG is missing a
value`. Nothing about the archive Prometheus needs anything from `docker-compose.yml`; it
declares its own image, config mount and external volume.

`COMPOSE_PROJECT_NAME=iir` is what the merge used to provide implicitly. Compose's default
network is `<project>_default`, so matching the benchmark stack's project name (`iir`, pinned
by `scripts/lib.sh`) puts `prometheus-replay` on `iir_default`, where Grafana can resolve it
by name — which is what the provisioned `prometheus-replay` datasource requires.

`prometheus-replay` is deliberately outside the bench compose overlay, so running
`docker compose -f docker-compose.yml -f docker-compose.bench.yml up --remove-orphans` (which
treats any container not defined in that overlay as an orphan) will stop it. That looks alarming —
"the archive vanished" — but the external volume is never deleted by that command, so the data is
intact; bring the container back with the `up -d` line above, or a plain
`docker start prometheus-replay`.

`scripts/prom_archive.sh` and `scripts/run_markers.py` both use plain `docker stop` /
`docker start` on the `prometheus-replay` container name rather than Compose. Besides the
`IMAGE_TAG` problem, a Compose invocation only sees containers in *its own* project — so if
the archive was brought up under a different project name, `compose stop` would find nothing,
report success, and leave blocks being copied in underneath a live head block. The container
name is pinned by `docker-compose.replay.yml`, so matching on it cannot miss.

---

## 3. Capturing a run — snapshot, then archive

This is a separate, opt-in step you run *after* `bench.sh` finishes — never inside it. `bench.sh`
and everything under `k6/` must stay byte-identical across every variant branch (the
acceptance test is `git diff --stat ES-2 HEAD -- k6 docker-compose.bench.yml` returning empty), so
this can't be a step bolted onto the harness itself. `run-suite.sh` does it for you; by hand it is:

```bash
SCENARIO=steady DURATION=2m ./k6/bench/bench.sh          # produces bench-results/<run_id>/
./scripts/prom_snapshot.sh <run_id>                       # -> bench-results/<run_id>/prom-snapshot/
./scripts/prom_archive.sh bench-results/<run_id>/prom-snapshot
```

`prom_snapshot.sh` triggers the live Prometheus's admin API (`--web.enable-admin-api`, already on
via `docker-compose.bench.yml`) to flush the WAL and snapshot the on-disk TSDB, then `docker cp`s
it out of the `prometheus` container. When its argument names an existing
`bench-results/<run_id>` directory the snapshot lands inside that run's own directory, alongside
`dump.json` and `report.pdf`; otherwise it falls back to its original label-based behaviour under
`reports/prom-snapshot-<timestamp>[-label]/`.

`prom_archive.sh` then copies that snapshot's block directories into the `bench-replay-data`
volume with `cp -rn` (no-clobber) — never `rm -rf`, unlike `prom_restore.sh`. Benchmark runs never
overlap in time, so blocks from many runs coexist in one Prometheus and a run is selected in
Grafana purely by its time range; `prometheus-replay` is stopped for the copy (backfilled blocks
must be older than the running head block) and always restarted afterwards, even if the copy
fails. If it was not running to begin with there is no head block to overlap, so the copy simply
proceeds and nothing is started — `prom_archive.sh` archives blocks, it does not manage the stack.

**A run with no `prom-snapshot/` cannot be viewed.** `dump.json` survives on its own, but it is a
set of pre-computed extracts, not a TSDB — it feeds `compare.py` / `evaluate.py` and the run's
report tables, and nothing rebuilds a dashboard from it. `scan_runs()` (§4) skips such a run
outright rather than listing it in the dropdown and drawing empty panels.

### Disk cost

```
$ du -sh bench-results/TO-3_steady_20260806T001945Z/prom-snapshot/
30M
```

**This number is not "cost per 2-minute run."** A Prometheus TSDB snapshot always contains *every*
block currently retained by the live Prometheus, not only the run just finished — in this
measurement the two blocks covered 2026-08-05T20:28Z through 2026-08-06T00:23Z, i.e. everything
the live `prometheus` container had retained since it was last started, roughly 4 hours, of which
the actual measured run was 2 minutes. That is also why re-archiving after each run in a same-day
session is cheap even though each snapshot is "full": `cp -rn` skips every block byte-identical to
one already in `bench-replay-data`, so only the still-open head block (which changed since the
last archive) actually gets copied each time — the older, already-archived 2-hour blocks are a
no-op.

The practical budget, then, is roughly **per hour of live-Prometheus uptime between restarts**,
not per run: ~30M / ~4h ≈ 7-8 MB/hour at this scrape configuration, dominated by `postgres`,
`cadvisor`, and API JVM metrics at their default scrape intervals. Restarting `prometheus` before
a benchmarking session (fresh head block) and archiving right after each run keeps each
incremental archive step small; letting many runs accumulate in one long-lived `prometheus`
process before the first snapshot makes the *first* snapshot of that session proportionally
larger, one-time only.

---

## 4. `bench-runs` — one dropdown, one run, one axis

`monitoring/grafana/provisioning/dashboards/bench-runs.json` (uid `bench-runs`) carries a **"Run"
dropdown**, and picking a run redraws every panel over an axis that starts at that run's own t0.
Full fidelity: the real scraped TSDB, so the `pg_stat_*`, WAL, locks, checkpoint, GC-pause,
HikariCP, outbox and container panels all work, exactly as they did live.

```bash
python3 scripts/run_markers.py bench-results <other-results-dirs>          # once per new run
python3 -m scripts.dashboards.build --runs bench-results <other-results-dirs>
```

Open it at `http://localhost:3001/d/bench-runs/` with the replay stack up (§2). **Leave the
time picker alone** — it is not how the run is selected, and moving it shows a different, mostly
empty slice of the archive. A "Selected run" header panel names the current pick, since the axis
itself gives no clue which run is on screen.

`scripts/replay_url.sh` prints the URL for a given run (newest by default, `--open` to launch a
browser), and warns if that run is not among the dropdown's baked-in options.

### The top panel — publish lag with the event types pooled

**"Publish lag, all event types — p50 / p95 / p99"** sits above every section, and is the one
panel this dashboard adds to the live set. It is the same `publish_lag_seconds_bucket` the
"Publish lag by event type" panel further down draws, with `eventType` left out of the `by`
clause, so all types' buckets are summed into one histogram *before* the quantile is taken:

```promql
histogram_quantile(0.95, sum(rate(publish_lag_seconds_bucket{job="$job"}[1m])) by (le))
```

That is not something the per-type panel can be read as. Quantiles do not aggregate: the p95 of
each event type separately tells you nothing about the p95 across all of them, and the highest of
the three curves is not it either — a type carrying 2 % of the traffic can own the worst curve and
move the pooled p95 not at all. `k6/bench/dump.py` records `publish_lag_p50/95/99` into
`dump.json` split by `eventType` and only so, so the pooled view exists nowhere else.

It is an ordinary curve in every other respect — `offset $run` reaches the selected run and `CLIP`
stops it at that run's end, exactly like the panels below it. That also means it is **per-minute**:
`histogram_quantile` of a `rate(...[1m])` is a quantile *of that minute*, so neither its peak nor
its eyeballed average is the run's percentile. A run-wide percentile is `histogram_quantile` over
the bucket-vector *difference* between the run's two endpoints; no panel shows that number, and
`dump.json`'s per-type figures are the nearest thing to it that is written down.

Nothing in the expression is replay-specific — no `$run`, no `$end`. The panel is declared in
`scripts/dashboards/runs.py` rather than `spec.py` because the archive browser is where a
finished run's publish lag gets read; moving it into `spec.py` would give the live dashboard the
same curve.

### The second panel — how many orders the run finished

**"Orders completed by outcome — running total"** answers *how many*, which no other orders panel
on this dashboard does. Everything else there is a rate — orders/s at each instant — and a rate
curve does not carry a count. This is the same `orders_completed_total` the "Orders completed by
outcome & reason" panel draws, undifferentiated and with `reason` collapsed:

```promql
sum by (outcome) (orders_completed_total{job="$job"})
```

Read the run's total off the **legend table's `Last *` column**, one row per outcome. The curve
itself shows *where* in the run the orders accrued: a flat stretch is a stall, a knee is the point
throughput changed.

**Why the raw counter, and not `counter - counter @ start()`.** Subtracting the value at t0 is the
obvious way to leave the warm-up out of the total, and it is wrong here. Prometheus carries a
series' last sample forward for five minutes, and `run-suite.sh` restarts the stack back-to-back,
so a series with no sample yet in *this* run resolves to the *previous* run's final value for the
first minutes past t0. In the 12-run phase-1 archive that hits `rejected` on 2 of the 6 TO runs —
`TO-3 · W-base · 0828-1652` opens at 238 140 and `TO-2-fix-A · W-base · 0828-2206` at 43 400,
because no order had been rejected yet when measured load started. Subtract that baseline and the
series is negative for the rest of the run; a negative series is not drawn on a `min: 0` axis, so
101 767 rejections would read as "no rejections" with nothing on screen to say why. The raw counter
fails the other way: the carry-over shows as a plateau-then-cliff in the first minutes, which is
visible and self-explanatory, and every point after the reset — the `Last *` total included — is
this run's own count.

Two things the totals are therefore not:

- **Warm-up is included.** `bench.sh`'s paced warm-up lands ~5 000 confirmed orders before t0, so
  `confirmed` starts at ~5 001 rather than 0. Against a full run's 200 k – 1.7 M that is under 3 %,
  and it is a constant rather than a variant difference.
- **`rejected` may open on the previous run's value**, on a run whose first rejection came minutes
  after t0. See above.

**TO family only.** `orders.completed` is registered in `InventoryService`, which the ES branches
do not have — their terminal outcomes arrive through the projection — so ES runs render this panel
empty, like every other TO-family panel here. The cross-family count is
`order_e2e_time_seconds_count`, which "Offered vs accepted vs terminal" already draws as a rate.

Replay-only, like the pooled publish lag and for the same kind of reason: on the live dashboard the
same expression would draw the counter since JVM start, which is a different quantity and not one
anyone watches climb.

### Clipping: why `run_markers.py` is not optional

The axis is sized for the *longest* archived run, and the campaign ran back-to-back — the smallest
start-to-next-start gap is 62.9 minutes against runs of up to 75.1. Without clipping, everything
past the selected run's own end shows **the run that followed it**: ~13 minutes of a stranger's
warm-up on a 60-minute run, and on the 10-minute `TO-3 W-fan` run from `bench-results/`, three
consecutive runs drawn as one line. It reads as the runs having been merged together, which is the
one thing this dashboard exists to avoid.

Clipping needs each run's end expressed in anchor time, and no Grafana variable can compute it: a
PromQL offset must be a literal duration, so the dropdown's value is already spoken for.
`scripts/run_markers.py` publishes the missing number as data instead —

```
bench_run_marker{run, run_id, variant, point, offset, end_at} 1
```

— one series per run, sampled every 5 minutes across the anchor window (plus 30 minutes of padding
on each side, because Grafana resolves `label_values()` over whatever range is on screen). The
dashboard chains a hidden `$end` variable off the selected run's offset,
`label_values(bench_run_marker{offset="$run"}, end_at)`, and every target is wrapped as

```promql
(<expr with per-selector offsets>) and on() (vector(time()) < vector($end))
```

`and on()` gates the left side on the right side existing while matching on no labels, so series,
legends and grouping are untouched; the right side is a bare true/false on the evaluation instant.
The wrap is applied once per target, not per selector — it is a property of the query's evaluation
time, not of any one metric.

If the markers are missing, `$end` resolves empty and every query ends in `vector()` — a parse
error on every panel. That is the intended failure: the alternative is silently showing the next
run's data as though it belonged to this one. 870 samples for 30 runs; the cost is nil.

### How it re-anchors without touching the data

Nothing is re-ingested: every run stays exactly where `prom_archive.sh` put it, at its real
wall-clock time. What moves is the query. The dashboard's time range is pinned to a fixed anchor
window (**2027-01-01T00:00:00Z**, spanning the longest run rounded up to 10 minutes), every
selector is rewritten as `metric{...}[1m] offset $run`, and the `run` variable's *value* is that
run's distance from the anchor — a PromQL duration such as `12218040s` — while its *label* is the
run's name. Selecting a run subtracts its own age and pulls its window into the anchor's:

```
time range   [2027-01-01T00:00, +80m]        (never moves)
TO-1 W-base  offset 12218040s -> 2026-08-12T14:06Z
ES-4 W-hot   offset 12129180s -> 2026-08-13T14:47Z
```

The anchor sits *after* the campaign because reaching forward in time needs a **negative** offset
and Prometheus rejects those without `--enable-feature=promql-negative-offset`. `scan_runs()`
refuses to build a dashboard containing a run that starts after the anchor rather than emit
queries that 400; if the campaign ever runs past 2027-01-01, move `ANCHOR_EPOCH` in
`scripts/dashboards/runs.py` (and `ANCHOR_RUNS` in `verify_dashboard_metrics.py`, which
`test_runs.AnchorsAgree` keeps in step). Moving it is cheap and safe — nothing on disk is
re-ingested, and the offsets, the marker series and the time range are all derived from it and
regenerated together by the next load. It was already moved once, from 2026-09-01, when the
campaign's `steady` runs landed on that day.

Three consequences worth knowing:

- **One run at a time, by construction.** A query carries one offset, so two runs cannot appear as
  separate lines in one panel. Toggling the dropdown between two runs is the substitute, and it
  works because the axis does not move: the panels stay in place and diff against each other by
  eye. For the numbers rather than the curves, compare the runs' `dump.json` extracts with
  `k6/bench/compare.py`.
- **`$job` / `$db` / `$dbc` / `$apic` are constants here, not query variables.** A query variable
  resolves against the dashboard's time range, and this range holds no data at all, so
  `label_values()` would come back empty and every panel would query `{job=""}` and render blank.
- **`$job` is the alternation of every scrape job in the run set**, e.g.
  `inventory|inventory-mgmt`, and every selector matches it with `job=~` rather than `job=`.
  `monitoring/prometheus/prometheus.yml` declares two application jobs — `inventory`, and
  `inventory-mgmt` for variants that give actuator its own `management.server.port` — and which
  one a run landed under is a property of the *variant*, recorded per run in `meta.json` as
  `prom_job`. `runs.py` builds the constant from the run set, so a third job name arrives with
  the run that uses it.

  This is not hypothetical tidiness. With `$job` pinned to the literal `inventory`, selecting
  either `TO-2-fix-A` run left **90 of its 91** `$job`-filtered targets returning nothing — the
  whole dashboard blank, which reads as a missing TSDB snapshot rather than a one-word label
  mismatch, and those two runs are the A/B evidence for the watermark cursor. Over-matching would
  need two API jobs carrying the same metric at the same instant, i.e. two API containers scraped
  at once; the stack runs one, and the non-selected job's target is down (`up == 0`, no
  application series at all). Verified across the 12-run phase-1 archive: 1 092 target/run pairs
  return byte-identical results under the alternation and under each run's own job name.
- **Runs shorter than the window leave empty space on the right.** The range spans the longest run,
  and clipping stops each panel at its own end, so a 26-minute run simply goes blank a third of the
  way across instead of continuing into the next run.

### Which runs appear

`scan_runs()` lists a run only when it has **both** `meta.json` (it finished) and `prom-snapshot/`
(its TSDB was captured). A run without a snapshot would otherwise fill every panel with "No data"
and no indication that the dashboard is fine and the data is simply not there.
Directories are walked to any depth, so a campaign directory of per-phase subdirectories — or a
directory of those — works as a single argument.

Each option is labelled by the run's **path** under the scanned directory, that directory's own
name first: `Final-Bench - 2-ES-snapshot - ES-1_capacity_W-base_20260831T221657Z`. The directory
names are how the runs were grouped in the first place (`2-ES-snapshot` vs `3-Cache` is the
comparison being made), the run's own directory name already carries variant, point and timestamp,
and the same `run_id` can sit under two phases — the path is what tells those apart on screen.
Options are therefore ordered by path, so the dropdown reads as the tree it came from. Both `,`
and `:` are replaced with `-` in each component: Grafana parses a custom variable's option list as
`label : value, label : value`, and either character in a directory name would silently split one
option into two broken ones.

`--root-label NAME` (on both `build.py --runs` and `run_markers.py`, positionally per directory)
replaces the first component. It exists for `docker-compose.replay-load.yml`, which bind-mounts the
selected directory at `/runs` — `runs` is not what you called it, so the compose file passes
`RUNS_DIR` in as a variable as well as a mount and hands the basename over. On the command line the
default (the directory's own basename) is already right.

A bare `python3 -m scripts.dashboards.build` deliberately leaves `bench-runs.json` untouched: its
dropdown is a list baked into the JSON, and regenerating it from a default location would quietly
replace a campaign's worth of options with whatever happened to be in `bench-results/`. The only
symptom would be a shorter dropdown.

### Verified end to end

Clipping, 2026-08-14: rendering `TO-3 W-fan` (10.0 minutes long) before the fix drew three
consecutive runs across 45 minutes of the axis; after it, the same URL draws that run's own 10
minutes and nothing else. The long case is intact in the other direction — `TO-1 W-base` (73.4
minutes: 60 of load, 13 of drain) still returns data at t0+73min and stops at t0+74min, so the
drain tail that `order_e2e_time` lands in is not truncated.

2026-08-14, 30 runs (26 from the breakpoint campaign plus 4 earlier ones):

- `verify_dashboard_metrics.py --dashboard runs --run "<label>"` — **0 ERROR** on all 99 targets,
  which is what proves the offset rewrite produces valid PromQL for every expression in `spec.py`;
  84/99 return data on a TO run, 81/99 on an ES run, and the difference is exactly the family split
  (outbox/order-timing/executor/Spring-Data empty on ES; projection lag, saga, aggregate cache,
  events-processed empty on TO).
- All 30 dropdown options return application data at their own t0+10min.
- Grafana's own interpolation was checked by rendering the "Offered vs accepted vs terminal" panel
  through `/render/d-solo/bench-runs/?var-run=...` for `TO-1 W-base` and `ES-4 W-hot`: both drew
  the full capacity ramp starting at 00:00 on an identical axis, saturating at ~300 and ~200 req/s
  respectively. (The replay Grafana on :3001 has no image renderer attached — that check ran
  against a throwaway Grafana + `grafana-image-renderer` pair on the same network, since
  `docker-compose.yml`'s renderer belongs to the benchmark stack.)

Two gaps are properties of the captured data, not of this dashboard, and they apply to every
archived run: **HTTP error rate** is empty because these runs threw no 4xx/5xx, and the five
**container** panels (CPU, memory rss/working set, network rx/tx) are empty because cadvisor
emitted no `name` label in these runs — only `id`, with just two docker-scoped series. `$dbc` and
`$apic` therefore match nothing. Verify with
`count(container_memory_rss{name!=""})` over any run's window before reading anything into it.

---

## 5. `docker-compose.replay-load.yml` — rebuild the archive from a directory of runs

Sections 2-4 leave you running four things by hand: create the volume, `prom_archive.sh`
once per run, `run_markers.py`, `build.py --runs`. `docker-compose.replay-load.yml` is those
steps as one command, pointed at whichever results directory you name:

```bash
RUNS_DIR=./bench-results-2/bench-results/breakpoint \
  docker compose -f docker-compose.replay-load.yml up --abort-on-container-failure
COMPOSE_PROJECT_NAME=iir docker compose -f docker-compose.replay.yml up -d
```

`RUNS_DIR` defaults to `./bench-results`. It is bind-mounted read-only at `/runs` and walked to
**any** depth, so a campaign directory of per-phase subdirectories — or a directory of those — is a
valid single argument, and each run is named in the dropdown by its path under it. Unlike §2, **the volume does not have to exist first**: this file declares
`bench-replay-data` non-external precisely so `up` creates it on a machine that has never had an
archive — which is also the one hazard worth naming: `down -v` *with this file* would delete the
archive. Nothing here needs tearing down; every service exits on its own.

**`prometheus-replay` must not be running.** Blocks written underneath a live head block corrupt
the archive. `prom_archive.sh` and `run_markers.py` avoid that with `docker stop`, which needs the
socket; this stack has no socket and cannot see a container it does not own, so the ordering is
yours to keep: load first, then start the viewer, or `docker stop prometheus-replay` before you
load.

Three one-shot services, chained on `service_completed_successfully`, on stock images:

| service | image | does |
|---|---|---|
| `replay-scan` | `python:3.12-slim` | `run_markers.py --dry-run` into a scratch volume, then `build.py --runs` — which rewrites `bench-runs.json`, so the dropdown and the volume are built from the same directory |
| `replay-archive` | `alpine` | `cp -rn` every `prom-snapshot/*/` block into the volume, then `chown -R 65534:65534` |
| `replay-markers` | `prom/prometheus` | `promtool tsdb create-blocks-from openmetrics` for `bench_run_marker`, as `nobody` so its blocks need no second chown |

Only `scripts/` and the provisioning `dashboards/` directory are mounted from the repo (`build.py`
derives its output path from its own `__file__`, so `/repo/scripts` is what pins `REPO_ROOT`), and
generated JSON is chowned back to `${HOST_UID:-1000}:${HOST_GID:-1000}` — override those if your
uid is not 1000. Re-running is safe in both halves: `cp -rn` skips blocks already archived, and
promtool rewrites the markers with the same values.

Two things it reports that are easy to miss otherwise: `WARNING: no blocks in …` for a run whose
snapshot directory exists but is empty (`scan_runs()` checks only that the directory exists, so
such a run reaches the dropdown and draws empty panels), and the block table promtool prints
for the markers it wrote.

Verified 2026-08-15 against `bench-results/` on a throwaway volume: 7 runs scanned, 8 blocks
archived from 9 snapshots (one empty, warned), markers backfilled, and with a Prometheus mounted
on the result `count(count by (__name__)({__name__=~".+"} offset 1697395s))` returned **616**
metric names at the anchor while the clipped form of a panel query returned data at t0+10min and
nothing past that run's `end_at`.

---

## 6. Dashboards — edit `spec.py`, never the JSON

`scripts/dashboards/spec.py` is the single source of truth for **both** Grafana dashboards, and
the only file in this workflow a human edits. Each panel declares one set of `targets` — live
PromQL against the scraping Prometheus. `bench-runs` is generated from that same set, rewritten
by `scripts/dashboards/runs.py` (offset per selector, clip per target), so there is no second
declaration per panel to keep in step.

```bash
python3 -m scripts.dashboards.build                       # the-dashboard
python3 -m scripts.dashboards.build --runs bench-results  # ... and bench-runs
```

Grafana's file provisioner picks up the new JSON within `updateIntervalSeconds: 30` — no restart
needed.

`the-dashboard` keeps its literal uid across regenerations because `k6/bench/bench.sh` renders
it by uid into every run's `report.pdf`; renaming it would silently break every future run's
report.

**`the-dashboard` cannot reach the archive, on purpose.** It used to carry a `$ds` datasource
picker so it could double as an archived-run viewer — switch to "Prometheus Replay", type the
run's window into the time picker. That is `bench-runs`' job now, and the picker had two failure
modes worth being rid of: a dashboard left on the archive shows stale data during the *next* live
run, and one left on live Prometheus after `run-suite.sh`'s `down -v` renders every
variable-filtered panel "No data" while the unfiltered ones look fine — which reads as a corrupt
archive rather than an unresolved variable. Panels, targets and query variables are now all
hard-pinned to `prometheus`, and `test_build.GeneratedDashboards.test_live_dashboard_is_pinned_to_the_live_prometheus`
fails if that regresses.

---

## 7. Checking that every panel actually resolves

The unit tests around `scripts/dashboards/` are *structural*: they prove expressions are unique,
panels tile the grid, variables are declared, and no metric **name** was dropped in the merge of
the five original dashboards. None of that notices a panel querying a metric the application never
publishes — the name is carried over faithfully, the panel renders, and it is simply always empty.

`scripts/verify_dashboard_metrics.py` closes that gap by asking Prometheus:

```bash
python3 scripts/verify_dashboard_metrics.py                     # live dashboard vs :9090
python3 scripts/verify_dashboard_metrics.py --dashboard runs    # bench-runs vs :9091
python3 scripts/verify_dashboard_metrics.py --dashboard runs --run TO-3
```

Each target lands in one of three buckets — `OK` (returns series), `EMPTY` (metric names exist,
selector matches nothing right now), `MISSING` (Prometheus has never seen the name). Exit status is
non-zero when anything is `MISSING` or errors.

Two things to keep in mind when reading the output:

- **A stack is one family, never both.** ES-family metrics are `MISSING` on a TO stack and vice
  versa; that is correct, not a defect. Run it against a stack of each family, or corroborate with
  branch source, before calling a panel dead.
- **An idle stack proves nothing about counters.** Micrometer registers many meters lazily, on
  first increment, so a counter that has not fired yet is indistinguishable from one that does not
  exist. Run it under load, or point it at the archive, which holds a full TSDB snapshot of
  a completed run.
- **Template constants come from the dashboard JSON, not from this script.** `dashboard_constants()`
  reads every `constant` variable out of the file being checked, so `$job`, `$db`, `$dbc` and
  `$apic` cannot drift from what Grafana interpolates; `--var NAME=VALUE` still overrides. A second
  copy of those values had gone stale twice — `DEFAULT_VARS` held `job: inventory-to` and
  `dbc: postgres-to` long after the stack dropped the family suffixes, and a hardcoded
  `job: inventory` reported **27/117** targets resolving on a `TO-2-fix-A` run where the dashboard
  itself renders **104/117**. A stale constant here reports a bug in the dashboard that is really
  a bug in this script, which is the most expensive kind of wrong answer it can give.

### The 2026-08-06 sweep, and what it removed

First run of this check against a live TO stack: **89 of 99** live targets returned data, 9 were
ES-family, 1 (`HTTP error rate`) was empty only because an idle stack throws no 4xx/5xx.
Before that sweep, four panels queried metrics that **no branch has ever produced**, and they were
deleted:

| Panel | Metric | Root cause |
|---|---|---|
| Aggregate state fetch latency (ES family) | `data_state_fetch_ms_seconds_bucket` | no branch registers this meter; the name is also malformed (`_ms_seconds`) |
| GC pause duration (p50/p95/p99 targets only) | `jvm_gc_pause_seconds_bucket` | `jvm.gc.pause` is on no branch's `percentiles-histogram` list, so Micrometer emits only count/sum/max |
| Tomcat HTTP threads | `tomcat_threads_*` | needs `server.tomcat.mbeanregistry.enabled=true`, which no branch sets; only `tomcat_sessions_*` are exposed |
| R2DBC connection pool (ES family) | `r2dbc_pool_*` | no branch uses R2DBC |

All four arrived through the merge of `jvm-spring-dashboard.json` and `inventory-es-dashboard.json`
— legacy dashboards written against an older implementation. The merge test enforced that no name
was *lost*, which is exactly how dead names got carried *forward*. `GC pause duration` kept its
working avg/max targets and was retitled; the other three panels are gone. Recovering the middle
two would mean changing the application under measurement across every variant branch
mid-campaign, so they were removed rather than enabled.
`MergeCoverage.test_never_collected_metrics_are_not_reintroduced` now fails if any of the nine
names comes back.

The ES-family metrics that this check reports `MISSING` on a TO stack were confirmed present a
different way, since an ES stack could not be built in that session: each meter is registered in
`ES-1..ES-4` source (`es.events.processed`, `projection.lag`, `order.projection.lag`,
`saga.completed`, `saga.command.failed`; `inventory.opt.cache.*` and `inventory.opt.catchup` on
ES-4 only), and all 16 archived ES runs carry populated `projection_lag_*`, `publish_lag_*` and
`state_load_*` quantiles in `dump.json`, while all 8 archived TO runs carry `projection_lag = None`
— the family split the dashboard encodes is the one the data shows.

One caveat found and cleared along the way: `ES-1`, `ES-2` and `ES-3` have no
`percentiles-histogram` block in `application.yaml` at all, which looks like it should leave every
latency quantile empty on those branches. It does not — they configure histograms programmatically
via `MeterFilter` instead, and their archived runs' quantiles are populated.

---

## 8. Known quirks

**Full-dashboard render clips panels.** This Grafana build's `/render/d/<uid>` endpoint always
returns a fixed-A4-page PDF (mislabeled `Content-Type: image/png`) regardless of the
`width`/`height` you request, so a full-dashboard render only ever captures what fits on one A4
page starting from the top-left of the dashboard (nav chrome included) — everything
below/right of that is cut off, whole panel columns included. To capture one panel, use
`/render/d-solo/<uid>/?panelId=<id>` instead: it renders just that panel, sized to the requested
`width`/`height`, in the corner of the same A4 page (the rest of the page is blank, which is
harmless — the panel itself is intact and unclipped):

```bash
curl -s -o panel.pdf 'http://localhost:3001/render/d-solo/bench-runs/?panelId=2&width=800&height=400&var-run=1677219s'
file panel.pdf   # PDF document, page size ~A4 — panel content is intact in the top-left corner
```
