# Bench replay — viewing archived runs in Grafana

The benchmark harness (`k6/bench/bench.sh`, see [`k6/README.md`](../k6/README.md)) keeps no
Prometheus TSDB by itself: every run's raw metrics die with the `docker-compose.bench.yml` volume
unless something copies them out before the next run resets it. What survives on its own is
`bench-results/<run_id>/dump.json` — pre-computed range series, per-step scalars, and run-level
summary numbers extracted while the run was live — plus `report.pdf` and `meta.json`. Sections 1-7
below are about turning that `dump.json` back into something viewable. Section 8 covers the better
option for any *future* run: keep the actual TSDB and get full panel parity, not the ~30 signals
`dump.json` can carry.

This is how you turn a `dump.json` back into something you can look at in Grafana, months after
the run happened, side by side with other runs. `scripts/replay_run.py` rebuilds a *viewable, not
identical* copy of each run into a dedicated archive Prometheus, and a generated dashboard
(`bench-replay`) queries it.

---

## 1. One-time setup

The archive Prometheus (`prometheus-replay`, host port **9091**) is deliberately not part of
`docker-compose.bench.yml` — `bench.sh` must never start, stop, or depend on it — and its data
volume is declared `external: true` so `docker compose down -v` cannot touch it. That matters:
the previous archive was destroyed by exactly that command.

External volumes are not auto-created by Compose, so on a machine that has never run this before,
create it first or `up` fails outright:

```bash
docker volume create bench-replay-data
COMPOSE_PROJECT_NAME=iir docker compose -f docker-compose.replay.yml up -d prometheus-replay
```

Both commands are idempotent — safe to run again on a machine that already has the archive.
`run-suite.sh` creates the volume itself, so in practice only the second line is ever needed.

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

`scripts/prom_archive.sh` and `scripts/replay_run.py` both use plain `docker stop` /
`docker start` on the `prometheus-replay` container name rather than Compose. Besides the
`IMAGE_TAG` problem, a Compose invocation only sees containers in *its own* project — so if
the archive was brought up under a different project name, `compose stop` would find nothing,
report success, and leave blocks being copied in underneath a live head block. The container
name is pinned by `docker-compose.replay.yml`, so matching on it cannot miss.

---

## 2. Everyday usage

Backfill one run:

```bash
python3 scripts/replay_run.py bench-results/TO-3_capacity_20260805T202853Z
```

Backfill every run that has a `dump.json`:

```bash
python3 scripts/replay_run.py --all
```

Both stop `prometheus-replay`, write TSDB blocks straight into its volume with
`promtool tsdb create-blocks-from openmetrics`, and start it back up — blocks must be older than
the running head block, which is why the container is stopped for the copy. Re-running on an
already-backfilled run is safe (it just writes another, overlapping-but-identical block); this is
the one script in the repo that is expected to stop and start a container as part of normal use.
Give the container a couple of seconds to come back up before querying it or reloading Grafana —
right after the script exits it can still be starting.

Each run prints a ready-to-open link when it finishes, e.g.:

```
  http://localhost:3000/d/bench-replay/?from=1767225600000&to=1767227349000&var-runs=TO-3_capacity_20260805T202853Z
```

To inspect the OpenMetrics output without touching Prometheus:

```bash
python3 scripts/replay_run.py --dry-run bench-results/TO-3_capacity_20260805T202853Z
```

### `--axis elapsed` vs `--axis wall`

```bash
python3 scripts/replay_run.py --axis wall bench-results/TO-3_capacity_20260805T202853Z
```

- **`elapsed`** (default) re-anchors every run's first sample to a fixed origin,
  `ANCHOR_EPOCH = 1767225600` (2026-01-01T00:00:00Z). Runs from different days then overlay on
  one chart, aligned by time-since-start — this is what `bench-replay`'s `$runs` multi-select is
  for.
- **`wall`** keeps the original timestamps, so the replay lands in Grafana at the wall-clock time
  the run actually happened — useful for lining a run up against its own `report.pdf` or
  `meta.json`.

Both axes can be backfilled for the same run without conflict — each sample carries an `axis`
label, so `elapsed` and `wall` data coexist as distinct series.

---

## 3. The metric model

Three generic metric families carry every run's original `dump.json` key in a label, so the
dashboard can query the same metric names across runs regardless of which keys any given run
happened to record:

| Family | Labels | Cardinality | Source |
|---|---|---|---|
| `replay_series` | `run_id, variant, scenario, axis, metric` | 5s samples over the run's full window | `dump.json["series"]` |
| `replay_step` | `run_id, variant, scenario, axis, metric, dim, step, target_rate` | one point per capacity step, at the plateau midpoint | `dump.json["per_step"]` |
| `replay_summary` | `run_id, variant, scenario, axis, key, dim, window` | one point per run, at the run's end | `dump.json["scalars"]`, `["derived"]`, `["load_window_scalars"]` |

The dump.json key travels in `metric=` (`replay_series`, `replay_step`) or `key=`
(`replay_summary`). Where a dump.json value is itself a dict (e.g. exceptions by type, e2e
latency by outcome) rather than a single number, the inner label lands in `dim=`.

Query examples against the archive datasource (uid `prometheus-replay`, port 9091):

```bash
curl -s 'http://localhost:9091/api/v1/label/run_id/values' | python3 -m json.tool
curl -s 'http://localhost:9091/api/v1/query?query=count(replay_series)&time=1767225700' | python3 -m json.tool
```

---

## 4. Dashboards — edit `spec.py`, never the JSON

`scripts/dashboards/spec.py` is the single source of truth for **both** Grafana dashboards; it is
the only file in this workflow a human edits. Panels declare `targets` (live PromQL, against the
scraping Prometheus) and `archived` (the `replay_*` equivalent, or `None` if `dump.json` cannot
carry that signal). Regenerate the committed JSON after any change:

```bash
python3 -m scripts.dashboards.build
```

This writes `monitoring/grafana/provisioning/dashboards/the-dashboard.json` (live dashboard,
datasource uid `prometheus`) and `bench-replay.json` (archive dashboard, datasource uid
`prometheus-replay`, with a multi-value `$runs` variable). Grafana's file provisioner picks up
the new JSON within `updateIntervalSeconds: 30` — no restart needed.

`the-dashboard` keeps that literal uid across regenerations because `k6/bench/bench.sh` renders
it by uid into every run's `report.pdf`; renaming it would silently break every future run's
report.

---

## 5. Coverage — what `dump.json` cannot show

The archive dashboard mirrors the live one's structure, but a `dump.json` is a set of
pre-computed extracts, not a TSDB — most live panels have no archived equivalent, at any effort.
The dashboard itself renders a "Not available for archived runs" panel listing every skipped
panel title.

**Available** (in at least one panel): throughput (offered vs. accepted vs. terminal), in-flight
orders, order e2e latency by outcome, `POST /inventory/orders` latency, publish lag by event
type, state load time by phase, state persist time by source, projection lag, business exceptions
by type, events processed by type, optimistic locking (retry rate, exhausted), aggregate cache
(hit/miss/catch-up), saga outcome, JVM heap, CPU, API container CPU/RSS, database size.

**Not available, for any archived run** — this is a `Panel(..., archived=None)` in `spec.py`
grouped by dashboard section; it is the exact same `skipped` set `build.py` renders into the
dashboard's own "Not available for archived runs" panel, not a hand-kept summary. Regenerate it
after any `spec.py` change with:

```bash
python3 -c "
from scripts.dashboards import spec
for section in spec.SECTIONS:
    skipped = [p.title for p in section.panels if not p.archived]
    if skipped:
        print(section.title + ':')
        for t in skipped:
            print('  - ' + t)
"
```

Current output (33 panels, regenerated 2026-08-06 after the dead-metric removal below):

- **HTTP:** HTTP error rate (4xx / 5xx)
- **Orders & domain:** Order processing time — p50 / p95 (TO family); Order queue wait — p50 / p95
  (TO family); Orders completed by outcome & reason (TO family); Outbox backlog (TO family); Outbox
  write time — p50 / p95 (TO family); Executor pools — threads & queue
- **JVM:** Non-heap memory by pool; GC pause duration — avg / max; JVM threads; Loaded
  classes; Process uptime; System load vs CPU cores; Threads by state; In-flight HTTP requests; GC
  allocation & promotion rate; GC overhead; Live data after GC; Log events by level; Spring Data
  repository invocations (top 10)
- **Spring pools:** HikariCP connections; HikariCP — acquire & usage time; HikariCP — connection
  timeouts
- **PostgreSQL:** WAL size; Active connections by state; Transaction rate; Tuple operations rate;
  Buffer cache hit ratio; Live rows by table; Per-table write rate; Locks by mode; Checkpoint
  activity; Container network I/O

Five of the six "Orders & domain" gaps are TO-family outbox and order-timing panels — exactly the
axis this thesis compares TO against ES on — so an archived TO run cannot show outbox backlog,
outbox write time, order processing time, order queue wait, or the outcome/reason breakdown at
all. That data only ever existed in the live TSDB.

Two shape caveats that don't show up in the list above: the continuous panels (`replay_series`)
are real 5-second series, but the per-step panels (`replay_step`) are only ~10 points per run —
one per capacity step, not a continuous line — and the summary numbers (`replay_summary`) are a
single point per run.

---

## 6. Known quirks

**Full-dashboard render clips panels.** This Grafana build's `/render/d/<uid>` endpoint always
returns a fixed-A4-page PDF (mislabeled `Content-Type: image/png`) regardless of the
`width`/`height` you request, so a full-dashboard render only ever captures what fits on one A4
page starting from the top-left of the dashboard (nav chrome included) — everything
below/right of that is cut off, whole panel columns included. To capture one panel, use
`/render/d-solo/<uid>/?panelId=<id>` instead: it renders just that panel, sized to the requested
`width`/`height`, in the corner of the same A4 page (the rest of the page is blank, which is
harmless — the panel itself is intact and unclipped):

```bash
curl -s -o panel.pdf 'http://localhost:3000/render/d-solo/bench-replay/?panelId=2&width=800&height=400&from=1767225600000&to=1767229200000&var-runs=TO-3_capacity_20260805T202853Z'
file panel.pdf   # PDF document, page size ~A4 — panel content is intact in the top-left corner
```

**The run-summary table needs the visible time range to cover the run.** `replay_summary` holds
exactly one sample per run, timestamped at the run's own end. A bare instant selector
(`replay_summary{run_id=~"$runs"}`) only matches a sample within Prometheus's default 5-minute
staleness window before the query time, so it would go "No data" the moment the dashboard's `to`
is more than 5 minutes past the run — which is why the panel instead queries
`last_over_time(replay_summary{run_id=~"$runs"}[$__range])`, `$__range` being the dashboard's
visible span. That fixes the common case, but it is not unconditional: `$__range` is measured
back from `to`, so the lookback window is exactly `[from, to]` — if you pan or narrow the time
picker so `from` moves past the run's own end, the table goes back to "No data" even with
`last_over_time`. Verified directly against Grafana's query API: the default `bench-replay` range
(`ANCHOR_ISO` .. `+2h`) returns the table fine; shifting both `from` and `to` forward past the
run reproduces "No data" (empty frame, zero fields). Keep the time range's *start* at or before
the run's end.

---

## 7. Current archive state

22 runs are backfilled as of this writing — every `bench-results/*/` that has a `dump.json`; the
other directories under `bench-results/` are incomplete runs (no `dump.json`, e.g. an aborted or
still-running benchmark). Confirm the current count rather than trusting this number:

```bash
find bench-results -maxdepth 2 -name dump.json | wc -l
```

---

## 8. Per-run TSDB snapshots — full parity for future runs

`replay_run.py` (sections 1-7) can only ever show what `dump.json` extracted — 33 of the merged
dashboard's 53 panels (every `pg_stat_*` metric, WAL size, locks, checkpoints, GC pause, JVM
threads, HikariCP, container network I/O, non-heap memory, GC allocation/overhead,
log events, Spring Data repository invocations, and on the TO family the outbox backlog and
order-timing panels) have no archived equivalent at any effort, because that data was never
captured into `dump.json` in the first place. For a run you are about to execute (not one already
archived), there is a better option: snapshot the live Prometheus's actual TSDB right after the
run and merge its blocks into `bench-replay-data`. Every panel then works for that run, exactly as
it did live.

This is a separate, opt-in step you run *after* `bench.sh` finishes — never inside it. `bench.sh`
and everything under `k6/` must stay byte-identical across all eight variant branches (the
acceptance test is `git diff --stat ES-2 HEAD -- k6 docker-compose.bench.yml` returning empty), so
this can't be a step bolted onto the harness itself.

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
fails.

### Viewing a snapshotted run: the "Data source" dropdown on `the-dashboard`

Once a run is archived, the way to actually look at it — at full fidelity, all 53 panels, not just
the 20 `dump.json` can carry — is `the-dashboard` itself, not `bench-replay`. `the-dashboard`
carries a **"Data source" template variable** (`$ds`) that every panel and target is wired to,
instead of a fixed datasource. It defaults to the live Prometheus (normal day-to-day use, no
behaviour change), so:

1. Open `the-dashboard` in Grafana.
2. Switch the "Data source" dropdown (top of the dashboard) from "Prometheus" to "Prometheus
   Replay".
3. Set the time picker to the archived run's window (`meta.json`'s `windows.load` /
   `windows.full`, or the range `prom_snapshot.sh` / `prom_archive.sh` printed).

Every panel — including the 33 that `bench-replay` can never show, like `pg_stat_*`, WAL size,
locks, checkpoints, GC pause, HikariCP, and the TO family's outbox/order-timing panels — now
queries the archive instead of live Prometheus and renders that run's real data. `bench-replay`
still exists for its own purpose: overlaying several runs' `dump.json` extracts on one
elapsed-time axis, which `the-dashboard` cannot do. `bench-replay` itself has no such dropdown and
stays pinned to `prometheus-replay` — it only ever holds replayed data, so a switch would be
meaningless there.

**Verified end to end** (2026-08-06, run `TO-3_steady_20260806T001945Z`, `SCENARIO=steady
DURATION=2m`): after snapshot + archive, `pg_stat_activity_count{datname="inventory"}` and
`pg_stat_database_xact_commit{datname="inventory"}` both returned real data for the run's own
`[t_settle_end, t_load_end]` window when queried against the `prometheus-replay` datasource (port
9091) — through a direct Prometheus API query, Grafana's `/api/ds/query`, and rendering
`the-dashboard`'s own "Active connections by state" panel (id 39) via
`/render/d-solo/the-dashboard/?panelId=39&var-ds=prometheus-replay&...` over the run's window,
which returned a valid, non-trivial PDF. Those are two of the 33 panels that no `dump.json`-based
archive can ever show — and this time the check went through the dashboard's own datasource
switch, not only the Prometheus API directly.

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

## 9. Checking that every panel actually resolves

The unit tests around `scripts/dashboards/` are *structural*: they prove expressions are unique,
panels tile the grid, variables are declared, and no metric **name** was dropped in the merge of
the five original dashboards. None of that notices a panel querying a metric the application never
publishes — the name is carried over faithfully, the panel renders, and it is simply always empty.

`scripts/verify_dashboard_metrics.py` closes that gap by asking Prometheus:

```bash
python3 scripts/verify_dashboard_metrics.py                        # live dashboard vs :9090
python3 scripts/verify_dashboard_metrics.py --dashboard archived   # replay dashboard vs :9091
python3 scripts/verify_dashboard_metrics.py --var job=inventory-es --var dbc=postgres-es
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
  exist. Run it under load, or point it at the replay archive, which holds a full TSDB snapshot of
  a completed run.

### The 2026-08-06 sweep, and what it removed

First run of this check against a live TO stack: **89 of 99** live targets returned data, 9 were
ES-family, 1 (`HTTP error rate`) was empty only because an idle stack throws no 4xx/5xx. The
archived dashboard scored **38 of 39**. Before that sweep, four panels queried metrics that **no
branch has ever produced**, and they were deleted:

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
two would mean changing the application under measurement across all eight variant branches
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
