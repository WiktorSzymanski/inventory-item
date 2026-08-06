# Bench replay — viewing archived runs in Grafana

The benchmark harness (`k6/bench/bench.sh`, see [`k6/README.md`](../k6/README.md)) keeps no
Prometheus TSDB: every run's raw metrics die with the `docker-compose.bench.yml` volume. What
survives is `bench-results/<run_id>/dump.json` — pre-computed range series, per-step scalars, and
run-level summary numbers extracted while the run was live — plus `report.pdf` and `meta.json`.

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
docker compose -f docker-compose.yml -f docker-compose.replay.yml up -d prometheus-replay
```

Both commands are idempotent — safe to run again on a machine that already has the archive.

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

Current output:

- **HTTP:** HTTP error rate (4xx / 5xx)
- **Orders & domain:** Order processing time — p50 / p95 (TO family); Order queue wait — p50 / p95
  (TO family); Orders completed by outcome & reason (TO family); Outbox backlog (TO family);
  Outbox write time — p50 / p95 (TO family); Order worker — queue depth & active threads (TO
  family)
- **JVM:** Non-heap memory by pool; GC pause duration — p50 / p95 / p99; JVM threads; Loaded
  classes; Process uptime
- **Spring pools:** HikariCP connections; Tomcat HTTP threads
- **PostgreSQL:** WAL size; Active connections by state; Transaction rate; Tuple operations rate;
  Buffer cache hit ratio; Live rows by table; Per-table write rate; Locks by mode; Checkpoint
  activity

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

21 runs are backfilled as of this writing — every `bench-results/*/` that has a `dump.json`; the
other directories under `bench-results/` are incomplete runs (no `dump.json`, e.g. an aborted or
still-running benchmark). Confirm the current count rather than trusting this number:

```bash
find bench-results -maxdepth 2 -name dump.json | wc -l
```
