# Unified Dashboard & Multi-Run Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three overlapping Grafana dashboards with one generated, deduplicated dashboard, and make every archived run in `bench-results/` loadable into a mirror of that dashboard — several runs at once, overlaid on a common elapsed-time axis.

**Architecture:** A single Python panel spec (`scripts/dashboards/spec.py`) becomes the one source of truth for both dashboards. `build.py` emits two JSON files from it: the live dashboard (Prometheus counters, uid `the-dashboard`) and the archived-runs dashboard (uid `bench-replay`), which reads three generic metrics — `replay_series`, `replay_step`, `replay_summary` — backfilled from `dump.json` by `scripts/replay_run.py`. Archived data lives in a separate long-retention Prometheus (`prometheus-replay`) on an external volume, so `docker compose down -v` can never delete the thesis archive.

**Tech Stack:** Python 3 stdlib only, Grafana 11.0.0 (schemaVersion 39), Prometheus 2.52.0, `promtool tsdb create-blocks-from openmetrics`, Docker Compose.

## Global Constraints

- **Python 3 standard library only.** No pip installs. The harness is deliberately dependency-free so runs cannot drift between variant branches.
- **Never modify `k6/**` or `docker-compose.bench.yml`.** They must stay byte-identical on every variant branch; the acceptance test is `git diff --stat ES-2 <branch> -- k6 docker-compose.bench.yml` returning empty. This plan therefore touches `scripts/`, `monitoring/`, `docker-compose.yml` and `docs/` only. In particular `k6/bench/dump.py` is **not** modified — the archived dashboard consumes `dump.json` exactly as it is written today.
- **Dashboard uid `the-dashboard` must not change.** `k6/bench/bench.sh:281` renders `$REPORTER_URL/api/v5/report/the-dashboard` into every run's `report.pdf`, and bench.sh cannot be edited (previous constraint).
- **Never hand-edit generated JSON.** `monitoring/grafana/provisioning/dashboards/the-dashboard.json` and `bench-replay.json` are build artefacts of `scripts/dashboards/build.py`. Edit `spec.py`, rebuild, commit both.
- **No label pinned to one variant family.** The current TO-3 dashboard hardcodes `job="inventory-to"` and `name="postgres-to"`, which makes it render empty on every ES branch. All such pins become dashboard variables (`$job`, `$db`, `$dbc`, `$apic`).
- **Commit locally only; never push.** The user pushes.
- **Anchor epoch for the elapsed axis: `1767225600`** (2026-01-01T00:00:00Z). Every archived run's samples are rewritten to `ANCHOR + (t - window_full_start)` so runs from different days overlay.
- Run tests with `python3 -m unittest discover -s scripts/tests -v` from the repo root.

---

## Current state (measured, 2026-08-05)

| Dashboard | File | uid | Panels | Targets | Distinct metrics |
|---|---|---|---|---|---|
| Metrics to compare | `the-dashboard.json` | `the-dashboard` | 20 (TO-3) / 18 (ES-4) | 42 | 28 |
| PostgreSQL Metrics | `postgres-dashboard.json` | `postgres-dashboard` | 12 | 21 | 21 |
| JVM & Spring — Overview | `jvm-spring-dashboard.json` (**ES-4 branch only**) | `jvm-spring` | 12 | 21 | 5 |

Duplicates to collapse (the "no duplicate metrics" requirement):

| Metric | Appears in | Resolution |
|---|---|---|
| `process_cpu_usage`, `system_cpu_usage` | compare "CPU Usage", jvm "CPU Usage" | one panel, JVM section |
| `jvm_memory_used_bytes{area="heap"}`, `jvm_memory_max_bytes` | compare "JVM Memory Usage", jvm "Heap Memory", jvm "Heap Used %" | one "Heap" timeseries + keep the gauge (different form, same metric — allowed, see `DUP_EXEMPT`) |
| `jvm_memory_used_bytes{area="nonheap"}` | compare "JVM Memory Usage", jvm "Non-Heap Memory" | one panel, broken out `by (id)` (the jvm version, strictly richer) |
| `http_server_requests_seconds_count` rate | compare "Request Throughput", jvm "HTTP Request Rate" | one panel, unfiltered, `by (uri, method)` |
| `http_server_requests_seconds_bucket` | compare "Request Latency p50/p80/p95", jvm "HTTP Latency" | one panel, p50/p95/p99 `by (le, uri, method)` |
| `pg_database_size_bytes` | compare "Database Storage", pg "Database Size" | one panel, PostgreSQL section |

Family-specific panels are kept as a **superset**: TO-only panels (outbox backlog, outbox write time, order worker executor, HikariCP, Tomcat, order processing time, order queue wait, orders completed) and ES-only panels (saga outcome, projection lag, aggregate cache hit ratio, in-flight orders, offered vs achieved, order outcome mix) both live in the merged dashboard. On the other family they render empty, which is correct and visible.

## Archived-run coverage (what `dump.json` can and cannot mirror)

`dump.json` holds: 10 range series at 5s over `window_full`; ~45 scalars per capacity step (10 steps, each a ~63s plateau window); run-level `scalars`, `load_window_scalars` and `derived`. Nothing else about the run survives.

| Merged dashboard section | Archived equivalent | Resolution |
|---|---|---|
| Throughput / offered vs achieved | `rate_accepted`, `rate_terminal`, `k6_offered`, `target_rate` | 5s |
| In-flight | `inflight` | 5s |
| Order e2e latency by outcome | `e2e_p50/p95/p99` (dim = outcome) | per step |
| HTTP POST /inventory/orders latency | `http_order_p50/p95/p99` | per step |
| Publish lag by eventType | `publish_lag_p50/p95/p99` (dim = eventType) | per step |
| State load by phase / persist by source | `state_load_p*`, `state_persist_p*` | per step |
| Projection lag / order projection lag | `projection_lag_p*`, `order_proj_lag_p*` | per step |
| Exceptions by type | `exceptions` (dim = type) | per step |
| Events processed by type | `events_processed` (dim = eventType) | per step |
| Optimistic locking | `opt_retry`, `opt_exhausted`, `append_success`, `conflict_rate` | per step + 5s |
| Cache hit ratio | `cache_hit`, `cache_miss`, `catchup` | per step |
| Saga outcome | `saga_completed`, `saga_cmd_failed`, `saga_lifetime_p*` | per step |
| JVM heap / CPU | `heap` (5s), `heap_max_bytes`, `cpu` (5s), `cpu_avg/max`, `sys_cpu_avg` | 5s + per step |
| API container CPU / RSS | `container_cpu`, `container_rss` | per step |
| Database size | `db_size` (5s), `db_size_start/end` | 5s + per step |
| **HTTP by uri/method/status** | **none** — only POST /inventory/orders 202-vs-non-202 | — |
| **GC pause, threads, loaded classes, non-heap** | **none** | — |
| **HikariCP, Tomcat, executor queue** | **none** | — |
| **All 21 `pg_stat_*` metrics, WAL size, locks, checkpoints** | **none** | — |

The builder renders a "Not available for archived runs" text panel listing exactly the skipped panel titles, so a reader is never left guessing whether a blank panel means "no data" or "not captured".

## File Structure

**Create:**
- `scripts/dashboards/__init__.py` — empty, makes the package importable.
- `scripts/dashboards/spec.py` — sections, panels, targets. One entry per metric. Holds both the live expression and the archived expression for every target.
- `scripts/dashboards/build.py` — turns the spec into two Grafana JSON files. CLI: `python3 -m scripts.dashboards.build`.
- `scripts/tests/test_spec.py` — spec invariants (no duplicate live expr, units present, archived keys exist in a real `dump.json`).
- `scripts/tests/test_build.py` — generated JSON invariants (unique ids, declared variables, idempotent build).
- `scripts/tests/test_replay_run.py` — OpenMetrics generation from a fixture `dump.json`.
- `scripts/tests/fixtures/mini-dump.json` — 2-step, 3-series miniature run used by the tests above.
- `docker-compose.replay.yml` — `prometheus-replay` service on an external volume.
- `monitoring/prometheus/prometheus-replay.yml` — scrape-free config for that instance.

**Modify:**
- `scripts/replay_run.py` — emit `replay_series` / `replay_step` / `replay_summary`, elapsed anchoring, `--axis`, target the replay Prometheus.
- `monitoring/grafana/provisioning/datasources/prometheus.yml` — add the `prometheus-replay` datasource.
- `monitoring/grafana/provisioning/dashboards/the-dashboard.json` — becomes a generated artefact.

**Delete:**
- `monitoring/grafana/provisioning/dashboards/postgres-dashboard.json`
- `monitoring/grafana/provisioning/dashboards/jvm-spring-dashboard.json` (ES-4 branch)
- `monitoring/grafana/provisioning/dashboards/replay-dashboard.json` (superseded by the generated `bench-replay.json`)

---

### Task 1: Spec + builder core, with the HTTP and Orders sections

**Files:**
- Create: `scripts/dashboards/__init__.py`, `scripts/dashboards/spec.py`, `scripts/dashboards/build.py`
- Create: `scripts/tests/fixtures/mini-dump.json`, `scripts/tests/test_spec.py`, `scripts/tests/test_build.py`

**Interfaces:**
- Produces: `spec.SECTIONS: list[Section]`, `spec.Section(title, panels)`, `spec.Panel(title, unit, w, h, targets, archived, note)`, `spec.Target(legend, expr)`; `build.build_live() -> dict`, `build.build_archived() -> dict`, `build.main()`.
- Consumes: nothing.

- [ ] **Step 1: Write the fixture**

`scripts/tests/fixtures/mini-dump.json` — a miniature of the real schema (verified against `bench-results/ES-4_capacity_20260805T154022Z/dump.json`):

```json
{
  "schema": 1,
  "run_id": "TEST-1_capacity_20260101T000000Z",
  "variant": "TEST-1",
  "scenario": "capacity",
  "windows": { "load": [1000, 1200], "full": [1000, 1400] },
  "scalars": { "orders_accepted": 400.0, "e2e_p95": { "confirmed": 1.5 }, "opt_retry": null },
  "derived": { "achieved_rps": 2.0, "backlog_at_stop": 7, "drained": false },
  "per_step": [
    { "index": 0, "target_rate": 20, "window": [1010, 1070],
      "scalars": { "cpu_avg": 0.4, "e2e_p95": { "confirmed": 1.1 }, "exceptions": { "NotFoundException": 0.0 }, "opt_retry": null },
      "derived": { "achieved_rps": 19.0, "window_seconds": 60 } },
    { "index": 1, "target_rate": 40, "window": [1130, 1190],
      "scalars": { "cpu_avg": 0.5, "e2e_p95": { "confirmed": 1.9 }, "exceptions": { "NotFoundException": 2.0 }, "opt_retry": null },
      "derived": { "achieved_rps": 38.0, "window_seconds": 60 } }
  ],
  "series": {
    "rate_accepted": [[1000, 0.0], [1005, 20.0]],
    "inflight": [[1000, 0.0], [1005, 5.0]],
    "cpu": [[1000, 0.3], [1005, 0.45]]
  }
}
```

- [ ] **Step 2: Write the failing spec test**

`scripts/tests/test_spec.py`:

```python
import json
import os
import unittest

from scripts.dashboards import spec

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "mini-dump.json")


class SpecInvariants(unittest.TestCase):
    def all_panels(self):
        return [p for section in spec.SECTIONS for p in section.panels]

    def test_no_duplicate_live_expression(self):
        """The whole point of the merge: one metric, queried in one place."""
        seen = {}
        for panel in self.all_panels():
            for target in panel.targets:
                key = " ".join(target.expr.split())
                if key in spec.DUP_EXEMPT:
                    continue
                self.assertNotIn(
                    key, seen,
                    f"expression duplicated in {panel.title!r} and {seen.get(key)!r}: {key}")
                seen[key] = panel.title

    def test_every_panel_has_a_unit_and_title(self):
        for panel in self.all_panels():
            self.assertTrue(panel.title, "panel without a title")
            self.assertTrue(panel.unit, f"{panel.title}: no unit")

    def test_panel_widths_fill_whole_rows(self):
        for section in spec.SECTIONS:
            width = sum(p.w for p in section.panels)
            self.assertEqual(width % 24, 0, f"{section.title}: widths sum to {width}, not a multiple of 24")

    def test_archived_targets_reference_real_dump_keys(self):
        """Every replay_step metric= / replay_series metric= must exist in a real dump.json."""
        with open(FIXTURE) as fh:
            dump = json.load(fh)
        step_keys = set(dump["per_step"][0]["scalars"]) | set(dump["per_step"][0]["derived"])
        series_keys = set(dump["series"])
        summary_keys = set(dump["scalars"]) | set(dump["derived"])
        known = {"replay_step": step_keys, "replay_series": series_keys, "replay_summary": summary_keys}
        for panel in self.all_panels():
            for target in panel.archived or []:
                for family, keys in known.items():
                    if not target.expr.startswith(family + "{"):
                        continue
                    for referenced in spec.metric_labels(target.expr):
                        self.assertIn(referenced, keys | spec.FIXTURE_GAPS,
                                      f"{panel.title}: {family} metric={referenced!r} is not a dump.json key")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `python3 -m unittest scripts.tests.test_spec -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'scripts.dashboards'`.

- [ ] **Step 4: Write `scripts/dashboards/__init__.py` and the spec skeleton with the first two sections**

`scripts/dashboards/__init__.py` — empty file. Also create an empty `scripts/__init__.py` and `scripts/tests/__init__.py` so `python3 -m unittest scripts.tests.test_spec` resolves.

`scripts/dashboards/spec.py`:

```python
"""Single source of truth for both Grafana dashboards.

Every metric appears exactly once. `targets` is what the live dashboard queries against a
Prometheus that is scraping the stack; `archived` is the equivalent against the three generic
metrics scripts/replay_run.py backfills from a run's dump.json:

    replay_series {run_id, variant, scenario, metric}              -- 5s, whole window
    replay_step   {run_id, variant, scenario, metric, dim, step}   -- one point per capacity step
    replay_summary{run_id, variant, scenario, key, dim}            -- one point per run

`archived=None` means the signal is not in dump.json at all; build.py lists those panels in a
"not available" note rather than rendering an empty panel.
"""
import re
from dataclasses import dataclass, field


@dataclass
class Target:
    legend: str
    expr: str


@dataclass
class Panel:
    title: str
    unit: str
    targets: list
    archived: list = None
    w: int = 8
    h: int = 8
    description: str = ""
    type: str = "timeseries"
    max: float = None


@dataclass
class Section:
    title: str
    panels: list = field(default_factory=list)


# Expressions allowed to repeat because the second use is a different visual form of the same
# number (a gauge of heap-used-over-max next to the heap timeseries), not a duplicated panel.
DUP_EXEMPT = set()

# dump.json keys the miniature fixture does not carry, but real runs do. Keeping this list
# explicit means a typo in spec.py still fails the test.
FIXTURE_GAPS = {
    "rate_terminal", "e2e_p95_1m", "heap", "db_size", "conflict_rate", "k6_offered",
    "target_rate", "orders_non202", "reads_total", "e2e_count", "e2e_sum", "append_success",
    "opt_exhausted", "cache_hit", "cache_miss", "catchup", "events_processed",
    "saga_completed", "saga_cmd_failed", "e2e_p50", "e2e_p99", "http_order_p50",
    "http_order_p95", "http_order_p99", "projection_lag_p50", "projection_lag_p95",
    "projection_lag_p99", "order_proj_lag_p50", "order_proj_lag_p95", "order_proj_lag_p99",
    "state_load_p50", "state_load_p95", "state_load_p99", "state_persist_p50",
    "state_persist_p95", "state_persist_p99", "publish_lag_p50", "publish_lag_p95",
    "publish_lag_p99", "saga_lifetime_p50", "saga_lifetime_p95", "saga_lifetime_p99",
    "cpu_max", "sys_cpu_avg", "heap_max_bytes", "heap_end_bytes", "db_size_start",
    "db_size_end", "container_cpu", "container_rss", "inflight_start", "inflight_end",
    "inflight_max", "completion_ratio", "rejected_ratio", "non202_ratio", "conflict_ratio",
    "cache_hit_ratio", "db_growth_bytes", "db_bytes_per_order", "drain_seconds",
    "drain_service_rate", "e2e_mean_confirmed", "achieved_rps_load_window",
    "cpu_avg_load_window", "window_seconds", "orders_accepted",
}


def metric_labels(expr):
    """Every metric="..." value referenced by a replay_* expression."""
    return re.findall(r'metric="([^"]+)"', expr) + re.findall(r'key="([^"]+)"', expr)


def _q(quantile, metric, by, job=True):
    selector = '{job="$job"}' if job else "{}"
    return (f'histogram_quantile({quantile}, sum(rate({metric}{selector}[1m])) by ({by}))')


SECTIONS = [
    Section("HTTP", [
        Panel(
            title="Request rate by endpoint & method",
            unit="reqps", w=12,
            description="sum(rate(http_server_requests_seconds_count)) by (uri, method). Replaces the "
                        "separate per-family throughput panels the three old dashboards each had.",
            targets=[Target("{{method}} {{uri}}",
                            'sum(rate(http_server_requests_seconds_count{job="$job"}[1m])) by (uri, method)')],
            archived=[Target("{{run_id}} accepted (202)", 'replay_series{run_id=~"$runs",metric="rate_accepted"}')],
        ),
        Panel(
            title="POST /inventory/orders by status",
            unit="reqps", w=12,
            description="Admission outcome. On archived runs only the 202/non-202 split survives.",
            targets=[Target("{{status}}",
                            'sum(rate(http_server_requests_seconds_count{job="$job",method="POST",uri="/inventory/orders"}[1m])) by (status)')],
            archived=[Target("{{run_id}} accepted", 'replay_step{run_id=~"$runs",metric="orders_accepted"}'),
                      Target("{{run_id}} non-202", 'replay_step{run_id=~"$runs",metric="orders_non202"}')],
        ),
        Panel(
            title="Request latency — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{method}} {{uri}} p50", _q(0.50, "http_server_requests_seconds_bucket", "le, uri, method")),
                     Target("{{method}} {{uri}} p95", _q(0.95, "http_server_requests_seconds_bucket", "le, uri, method")),
                     Target("{{method}} {{uri}} p99", _q(0.99, "http_server_requests_seconds_bucket", "le, uri, method"))],
            archived=[Target("{{run_id}} POST orders p50", 'replay_step{run_id=~"$runs",metric="http_order_p50"}'),
                      Target("{{run_id}} POST orders p95", 'replay_step{run_id=~"$runs",metric="http_order_p95"}'),
                      Target("{{run_id}} POST orders p99", 'replay_step{run_id=~"$runs",metric="http_order_p99"}')],
        ),
        Panel(
            title="HTTP error rate (4xx / 5xx)",
            unit="reqps", w=12,
            targets=[Target("{{status}} {{uri}}",
                            'sum(rate(http_server_requests_seconds_count{job="$job",status=~"[45].."}[1m])) by (uri, status)')],
            archived=None,
        ),
    ]),
    Section("Orders & domain", [
        Panel(
            title="Offered vs accepted vs terminal",
            unit="reqps", w=24, h=9,
            description="One axis, orders/s. Dashed = asked of the system, solid = done by it.",
            targets=[Target("accepted (202)",
                            'sum(rate(http_server_requests_seconds_count{job="$job",method="POST",uri="/inventory/orders",status="202"}[1m]))'),
                     Target("terminal", 'sum(rate(order_e2e_time_seconds_count{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} accepted", 'replay_series{run_id=~"$runs",metric="rate_accepted"}'),
                      Target("{{run_id}} terminal", 'replay_series{run_id=~"$runs",metric="rate_terminal"}'),
                      Target("{{run_id}} k6 offered", 'replay_series{run_id=~"$runs",metric="k6_offered"}'),
                      Target("{{run_id}} step target", 'replay_series{run_id=~"$runs",metric="target_rate"}')],
        ),
        Panel(
            title="In-flight orders (saturation)",
            unit="short", w=12,
            description="Admitted minus terminal. Monotonic growth on a constant-rate plateau is saturation.",
            targets=[Target("in-flight",
                            '(sum(http_server_requests_seconds_count{job="$job",method="POST",uri="/inventory/orders",status="202"}) or vector(0))'
                            ' - (sum(order_e2e_time_seconds_count{job="$job"}) or vector(0))')],
            archived=[Target("{{run_id}}", 'replay_series{run_id=~"$runs",metric="inflight"}')],
        ),
        Panel(
            title="Order e2e latency by outcome — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{outcome}} p50", _q(0.50, "order_e2e_time_seconds_bucket", "le, outcome")),
                     Target("{{outcome}} p95", _q(0.95, "order_e2e_time_seconds_bucket", "le, outcome")),
                     Target("{{outcome}} p99", _q(0.99, "order_e2e_time_seconds_bucket", "le, outcome"))],
            archived=[Target("{{run_id}} {{dim}} p50", 'replay_step{run_id=~"$runs",metric="e2e_p50"}'),
                      Target("{{run_id}} {{dim}} p95", 'replay_step{run_id=~"$runs",metric="e2e_p95"}'),
                      Target("{{run_id}} {{dim}} p99", 'replay_step{run_id=~"$runs",metric="e2e_p99"}')],
        ),
        Panel(
            title="Business exception rate by type",
            unit="ops", w=12,
            targets=[Target("{{type}}", 'sum by (type) (rate(inventory_exception_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} {{dim}}", 'replay_step{run_id=~"$runs",metric="exceptions"}')],
        ),
        Panel(
            title="Optimistic locking — append success vs conflict",
            unit="ops", w=12,
            targets=[Target("append success", 'sum(rate(inventory_append_success_total{job="$job"}[1m]))'),
                     Target("retry", 'sum(rate(inventory_optimistic_retry_total{job="$job"}[1m]))'),
                     Target("exhausted", 'sum(rate(inventory_optimistic_exhausted_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} retry rate", 'replay_series{run_id=~"$runs",metric="conflict_rate"}'),
                      Target("{{run_id}} exhausted (step total)", 'replay_step{run_id=~"$runs",metric="opt_exhausted"}')],
        ),
        Panel(
            title="Events processed by type",
            unit="ops", w=12,
            targets=[Target("{{eventType}}", 'sum by (eventType) (rate(es_events_processed_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} {{dim}}", 'replay_step{run_id=~"$runs",metric="events_processed"}')],
        ),
        Panel(
            title="Publish lag by event type — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{eventType}} p50", _q(0.50, "publish_lag_seconds_bucket", "le, eventType")),
                     Target("{{eventType}} p95", _q(0.95, "publish_lag_seconds_bucket", "le, eventType")),
                     Target("{{eventType}} p99", _q(0.99, "publish_lag_seconds_bucket", "le, eventType"))],
            archived=[Target("{{run_id}} {{dim}} p95", 'replay_step{run_id=~"$runs",metric="publish_lag_p95"}'),
                      Target("{{run_id}} {{dim}} p99", 'replay_step{run_id=~"$runs",metric="publish_lag_p99"}')],
        ),
        Panel(
            title="State load time by phase — p50 / p95",
            unit="s", w=12,
            targets=[Target("{{phase}} p50", _q(0.50, "state_load_time_seconds_bucket", "le, phase")),
                     Target("{{phase}} p95", _q(0.95, "state_load_time_seconds_bucket", "le, phase"))],
            archived=[Target("{{run_id}} {{dim}} p50", 'replay_step{run_id=~"$runs",metric="state_load_p50"}'),
                      Target("{{run_id}} {{dim}} p95", 'replay_step{run_id=~"$runs",metric="state_load_p95"}')],
        ),
        Panel(
            title="State persist time by source — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("{{source}} p50", _q(0.50, "state_persist_time_seconds_bucket", "le, source")),
                     Target("{{source}} p95", _q(0.95, "state_persist_time_seconds_bucket", "le, source")),
                     Target("{{source}} p99", _q(0.99, "state_persist_time_seconds_bucket", "le, source"))],
            archived=[Target("{{run_id}} {{dim}} p95", 'replay_step{run_id=~"$runs",metric="state_persist_p95"}'),
                      Target("{{run_id}} {{dim}} p99", 'replay_step{run_id=~"$runs",metric="state_persist_p99"}')],
        ),
        Panel(
            title="Projection lag — p50 / p95 / p99",
            unit="s", w=12,
            targets=[Target("inventory p50", _q(0.50, "projection_lag_seconds_bucket", "le")),
                     Target("inventory p95", _q(0.95, "projection_lag_seconds_bucket", "le")),
                     Target("order p95", _q(0.95, "order_projection_lag_seconds_bucket", "le"))],
            archived=[Target("{{run_id}} inventory p95", 'replay_step{run_id=~"$runs",metric="projection_lag_p95"}'),
                      Target("{{run_id}} order p95", 'replay_step{run_id=~"$runs",metric="order_proj_lag_p95"}')],
        ),
        Panel(
            title="Aggregate cache — hit vs miss vs catch-up",
            unit="ops", w=12,
            targets=[Target("hit", 'sum(rate(inventory_opt_cache_hit_total{job="$job"}[1m]))'),
                     Target("miss", 'sum(rate(inventory_opt_cache_miss_total{job="$job"}[1m]))'),
                     Target("catch-up", 'sum(rate(inventory_opt_catchup_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} hit", 'replay_step{run_id=~"$runs",metric="cache_hit"}'),
                      Target("{{run_id}} miss", 'replay_step{run_id=~"$runs",metric="cache_miss"}'),
                      Target("{{run_id}} catch-up", 'replay_step{run_id=~"$runs",metric="catchup"}')],
        ),
        Panel(
            title="Outbox backlog (TO family)",
            unit="short", w=12,
            targets=[Target("backlog", 'outbox_backlog{job="$job"}')],
            archived=None,
        ),
        Panel(
            title="Outbox write time — p50 / p95 (TO family)",
            unit="s", w=12,
            targets=[Target("p50", _q(0.50, "outbox_write_time_seconds_bucket", "le")),
                     Target("p95", _q(0.95, "outbox_write_time_seconds_bucket", "le"))],
            archived=None,
        ),
        Panel(
            title="Order worker — queue depth & active threads (TO family)",
            unit="short", w=12,
            targets=[Target("queued", 'executor_queued_tasks{job="$job",name="orderWorkerExecutor"}'),
                     Target("active", 'executor_active_threads{job="$job",name="orderWorkerExecutor"}')],
            archived=None,
        ),
        Panel(
            title="Saga outcome — completed vs failed (ES family)",
            unit="ops", w=12,
            targets=[Target("completed", 'sum(rate(saga_completed_total{job="$job"}[1m]))'),
                     Target("command failed", 'sum(rate(saga_command_failed_total{job="$job"}[1m]))')],
            archived=[Target("{{run_id}} completed", 'replay_step{run_id=~"$runs",metric="saga_completed"}'),
                      Target("{{run_id}} cmd failed", 'replay_step{run_id=~"$runs",metric="saga_cmd_failed"}')],
        ),
    ]),
]
```

> **Note for the implementer:** the two TO-only order-timing panels from the old dashboard
> (`order_processing_time_seconds_bucket`, `order_queue_wait_seconds_bucket`,
> `orders_completed_total`) go in this same section — add them following the exact shape of
> "Order e2e latency by outcome" above, with `archived=None`. They are listed in Task 3's
> completeness check.

- [ ] **Step 5: Write the builder**

`scripts/dashboards/build.py`:

```python
#!/usr/bin/env python3
"""Generate both Grafana dashboards from scripts/dashboards/spec.py.

    python3 -m scripts.dashboards.build

Writes monitoring/grafana/provisioning/dashboards/{the-dashboard,bench-replay}.json.
Never edit those files by hand — edit spec.py and re-run this.
"""
import json
import os

from . import spec

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(REPO_ROOT, "monitoring", "grafana", "provisioning", "dashboards")
DS = {"type": "prometheus", "uid": "prometheus"}
DS_REPLAY = {"type": "prometheus", "uid": "prometheus-replay"}
ANCHOR_ISO = "2026-01-01T00:00:00.000Z"


def _timeseries(panel, targets, datasource, panel_id, x, y):
    return {
        "id": panel_id,
        "title": panel.title,
        "description": panel.description,
        "type": panel.type,
        "gridPos": {"x": x, "y": y, "w": panel.w, "h": panel.h},
        "datasource": datasource,
        "fieldConfig": {
            "defaults": {
                "unit": panel.unit,
                "min": 0,
                **({"max": panel.max} if panel.max is not None else {}),
                "color": {"mode": "palette-classic"},
                "custom": {
                    "drawStyle": "line",
                    "lineInterpolation": "linear",
                    "lineWidth": 2,
                    "fillOpacity": 0,
                    "showPoints": "auto",
                    "spanNulls": False,
                },
            },
            "overrides": [],
        },
        "options": {
            "legend": {"displayMode": "table", "placement": "bottom",
                       "calcs": ["mean", "max", "lastNotNull"], "showLegend": True},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
        "targets": [
            {"datasource": datasource, "expr": t.expr, "legendFormat": t.legend,
             "refId": chr(ord("A") + i)}
            for i, t in enumerate(targets)
        ],
    }


def _row(title, panel_id, y):
    return {"id": panel_id, "type": "row", "title": title, "collapsed": False,
            "gridPos": {"x": 0, "y": y, "w": 24, "h": 1}, "panels": []}


def _layout(sections, pick, datasource):
    """Lay panels out left-to-right, wrapping at 24 columns, one row header per section."""
    panels, panel_id, y, skipped = [], 1, 0, []
    for section in sections:
        chosen = [(p, pick(p)) for p in section.panels]
        chosen = [(p, t) for p, t in chosen if t]
        skipped += [p.title for p in section.panels if not pick(p)]
        if not chosen:
            continue
        panels.append(_row(section.title, panel_id, y))
        panel_id += 1
        y += 1
        x = 0
        for panel, targets in chosen:
            if x + panel.w > 24:
                x, y = 0, y + panel.h
            panels.append(_timeseries(panel, targets, datasource, panel_id, x, y))
            panel_id += 1
            x += panel.w
        y += max(p.h for p, _ in chosen)
    return panels, panel_id, y, skipped


def _base(uid, title, description, time_from, time_to, templating):
    return {
        "uid": uid, "title": title, "description": description,
        "editable": False, "graphTooltip": 1, "refresh": "", "schemaVersion": 39,
        "tags": ["inventory"], "timezone": "browser",
        "time": {"from": time_from, "to": time_to}, "timepicker": {},
        "annotations": {"list": []}, "links": [], "version": 1,
        "templating": {"list": templating},
    }


def _var(name, label, query, datasource, multi=False):
    return {"name": name, "label": label, "type": "query", "datasource": datasource,
            "query": {"query": query, "refId": f"{name}-variable"}, "definition": query,
            "refresh": 2, "sort": 1, "includeAll": multi, "multi": multi,
            "current": {}, "options": []}


def build_live():
    dash = _base(
        "the-dashboard", "Inventory — Full Stack",
        "Generated by scripts/dashboards/build.py from scripts/dashboards/spec.py — do not edit by hand. "
        "Merges the former 'Metrics to compare', 'PostgreSQL Metrics' and 'JVM & Spring' dashboards. "
        "Panels for the other variant family render empty by design.",
        "now-15m", "now",
        [_var("job", "API job", "label_values(up, job)", DS),
         _var("db", "Database", "label_values(pg_database_size_bytes, datname)", DS),
         _var("dbc", "DB container", "label_values(container_memory_rss, name)", DS),
         _var("apic", "API container", "label_values(container_memory_rss, name)", DS)])
    dash["refresh"] = "5s"
    panels, _, _, _ = _layout(spec.SECTIONS, lambda p: p.targets, DS)
    dash["panels"] = panels
    return dash


def build_archived():
    dash = _base(
        "bench-replay", "Bench Replay (archived runs)",
        "Generated by scripts/dashboards/build.py — do not edit by hand. Rebuilt from "
        "bench-results/<run_id>/dump.json by scripts/replay_run.py; NOT the original Prometheus data. "
        "All runs are anchored to a common origin so several can be overlaid: the time axis is elapsed "
        "time since each run's window_full start, displayed from " + ANCHOR_ISO + ".",
        ANCHOR_ISO, "2026-01-01T02:00:00.000Z",
        [_var("runs", "Runs", "label_values(replay_series, run_id)", DS_REPLAY, multi=True)])
    panels, next_id, y, skipped = _layout(spec.SECTIONS, lambda p: p.archived, DS_REPLAY)
    panels.insert(0, _summary_table(next_id, 0))
    for p in panels[1:]:
        p["gridPos"]["y"] += 8
    panels.append({
        "id": next_id + 1, "type": "text", "title": "Not available for archived runs",
        "gridPos": {"x": 0, "y": y + 8, "w": 24, "h": 6},
        "options": {"mode": "markdown", "content":
                    "dump.json does not carry these signals, so no panel can exist for them:\n\n"
                    + "\n".join(f"- {t}" for t in skipped)},
    })
    dash["panels"] = panels
    return dash


def _summary_table(panel_id, y):
    """One row per selected run, one column per dump.json derived/scalar key."""
    return {
        "id": panel_id, "title": "Run summary (dump.json derived + scalars)", "type": "table",
        "gridPos": {"x": 0, "y": y, "w": 24, "h": 8}, "datasource": DS_REPLAY,
        "fieldConfig": {"defaults": {"custom": {"align": "right"}}, "overrides": []},
        "options": {"showHeader": True},
        "targets": [{"datasource": DS_REPLAY, "expr": 'replay_summary{run_id=~"$runs"}',
                     "format": "table", "instant": True, "refId": "A"}],
        "transformations": [
            {"id": "organize", "options": {"excludeByName": {"Time": True, "__name__": True}}},
            {"id": "groupingToMatrix",
             "options": {"columnField": "key", "rowField": "run_id", "valueField": "Value"}},
        ],
    }


def main():
    for name, dashboard in (("the-dashboard", build_live()), ("bench-replay", build_archived())):
        path = os.path.join(OUT_DIR, f"{name}.json")
        with open(path, "w") as fh:
            json.dump(dashboard, fh, indent=2, sort_keys=False)
            fh.write("\n")
        print(f"wrote {path} ({len(dashboard['panels'])} panels)")


if __name__ == "__main__":
    main()
```

- [ ] **Step 6: Write the build test**

`scripts/tests/test_build.py`:

```python
import unittest

from scripts.dashboards import build


class GeneratedDashboards(unittest.TestCase):
    def setUp(self):
        self.live = build.build_live()
        self.archived = build.build_archived()

    def test_live_keeps_the_reporter_uid(self):
        """bench.sh renders /api/v5/report/the-dashboard for every run's report.pdf."""
        self.assertEqual(self.live["uid"], "the-dashboard")

    def test_panel_ids_are_unique(self):
        for dashboard in (self.live, self.archived):
            ids = [p["id"] for p in dashboard["panels"]]
            self.assertEqual(len(ids), len(set(ids)), f"{dashboard['uid']}: duplicate panel ids")

    def test_every_variable_used_is_declared(self):
        for dashboard in (self.live, self.archived):
            declared = {v["name"] for v in dashboard["templating"]["list"]}
            for panel in dashboard["panels"]:
                for target in panel.get("targets", []):
                    for token in ("$job", "$db", "$dbc", "$apic", "$runs"):
                        if token in target.get("expr", ""):
                            self.assertIn(token[1:], declared,
                                          f"{dashboard['uid']}/{panel['title']}: {token} not declared")

    def test_no_panel_overflows_the_grid(self):
        for dashboard in (self.live, self.archived):
            for panel in dashboard["panels"]:
                pos = panel["gridPos"]
                self.assertLessEqual(pos["x"] + pos["w"], 24, f"{panel.get('title')} overflows")

    def test_build_is_deterministic(self):
        self.assertEqual(build.build_live(), self.live)
        self.assertEqual(build.build_archived(), self.archived)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 7: Run both test modules**

Run: `python3 -m unittest scripts.tests.test_spec scripts.tests.test_build -v`
Expected: PASS, all tests.

- [ ] **Step 8: Generate and eyeball**

```bash
python3 -m scripts.dashboards.build
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d prometheus grafana
sleep 35
curl -s "http://localhost:3000/api/dashboards/uid/the-dashboard" | python3 -c "import json,sys; d=json.load(sys.stdin)['dashboard']; print(d['title'], len(d['panels']), 'panels')"
```
Expected: `Inventory — Full Stack` with the HTTP and Orders rows present.

- [ ] **Step 9: Commit**

```bash
git add scripts/__init__.py scripts/dashboards scripts/tests docs/superpowers/plans
git add monitoring/grafana/provisioning/dashboards/the-dashboard.json
git commit -m "feat(grafana): generate the merged dashboard from a single panel spec"
```

---

### Task 2: JVM, Spring pools and PostgreSQL sections

**Files:**
- Modify: `scripts/dashboards/spec.py` (append three sections to `SECTIONS`)
- Modify: `scripts/tests/test_spec.py` (add the coverage test below)

**Interfaces:**
- Consumes: `spec.Section`, `spec.Panel`, `spec.Target`, `spec._q` from Task 1.
- Produces: nothing new — same module-level `SECTIONS`.

- [ ] **Step 1: Write the failing coverage test**

Append to `scripts/tests/test_spec.py`:

```python
class MergeCoverage(unittest.TestCase):
    """Every metric the three old dashboards queried must survive the merge."""

    OLD_METRICS = {
        # the-dashboard
        "http_server_requests_seconds_count", "http_server_requests_seconds_bucket",
        "process_cpu_usage", "system_cpu_usage", "jvm_memory_used_bytes", "jvm_memory_max_bytes",
        "publish_lag_seconds_bucket", "state_load_time_seconds_bucket",
        "inventory_append_success_total", "inventory_optimistic_retry_total",
        "inventory_optimistic_exhausted_total", "inventory_exception_total",
        "pg_database_size_bytes", "executor_queued_tasks", "executor_active_threads",
        "order_processing_time_seconds_bucket", "order_e2e_time_seconds_bucket",
        "order_queue_wait_seconds_bucket", "orders_completed_total",
        "state_persist_time_seconds_bucket", "outbox_backlog", "outbox_write_time_seconds_bucket",
        "hikaricp_connections_active", "hikaricp_connections_pending", "hikaricp_connections_max",
        "tomcat_threads_busy_threads", "tomcat_threads_current_threads",
        "tomcat_threads_config_max_threads",
        # postgres-dashboard
        "pg_stat_activity_count", "pg_stat_database_xact_commit", "pg_stat_database_xact_rollback",
        "pg_stat_database_tup_inserted", "pg_stat_database_tup_updated",
        "pg_stat_database_tup_deleted", "pg_stat_database_tup_fetched",
        "pg_stat_database_blks_hit", "pg_stat_database_blks_read",
        "pg_stat_user_tables_n_live_tup", "pg_stat_user_tables_n_tup_ins",
        "pg_stat_user_tables_n_tup_upd", "pg_stat_user_tables_n_tup_del",
        "pg_wal_size_bytes", "pg_locks_count", "pg_stat_bgwriter_checkpoints_timed_total",
        "pg_stat_bgwriter_checkpoints_req_total", "pg_stat_bgwriter_checkpoint_write_time_total",
        "container_cpu_usage_seconds_total", "container_memory_rss",
        "container_memory_working_set_bytes",
        # jvm-spring
        "jvm_gc_pause_seconds_bucket", "jvm_threads_live_threads", "jvm_threads_daemon_threads",
        "jvm_threads_peak_threads", "jvm_classes_loaded_classes", "process_uptime_seconds",
    }

    def test_no_old_metric_was_dropped(self):
        blob = " ".join(t.expr for s in spec.SECTIONS for p in s.panels for t in p.targets)
        missing = sorted(m for m in self.OLD_METRICS if m not in blob)
        self.assertEqual(missing, [], f"metrics lost in the merge: {missing}")
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python3 -m unittest scripts.tests.test_spec.MergeCoverage -v`
Expected: FAIL listing the ~35 JVM/pool/PostgreSQL metrics not yet in the spec.

- [ ] **Step 3: Append the three sections to `SECTIONS`**

```python
SECTIONS += [
    Section("JVM", [
        Panel(title="Heap memory", unit="bytes", w=8,
              targets=[Target("used", 'sum(jvm_memory_used_bytes{job="$job",area="heap"})'),
                       Target("max", 'sum(jvm_memory_max_bytes{job="$job",area="heap"})')],
              archived=[Target("{{run_id}}", 'replay_series{run_id=~"$runs",metric="heap"}')]),
        Panel(title="Non-heap memory by pool", unit="bytes", w=8,
              targets=[Target("{{id}}", 'sum(jvm_memory_used_bytes{job="$job",area="nonheap"}) by (id)')],
              archived=None),
        Panel(title="CPU", unit="percentunit", w=8, max=1,
              targets=[Target("process", 'avg(process_cpu_usage{job="$job"})'),
                       Target("system", 'avg(system_cpu_usage{job="$job"})')],
              archived=[Target("{{run_id}} process", 'replay_series{run_id=~"$runs",metric="cpu"}'),
                        Target("{{run_id}} system (step avg)", 'replay_step{run_id=~"$runs",metric="sys_cpu_avg"}')]),
        Panel(title="GC pause duration — p50 / p95 / p99", unit="s", w=8,
              targets=[Target("p50", _q(0.50, "jvm_gc_pause_seconds_bucket", "le")),
                       Target("p95", _q(0.95, "jvm_gc_pause_seconds_bucket", "le")),
                       Target("p99", _q(0.99, "jvm_gc_pause_seconds_bucket", "le"))],
              archived=None),
        Panel(title="JVM threads", unit="short", w=8,
              targets=[Target("live", 'jvm_threads_live_threads{job="$job"}'),
                       Target("daemon", 'jvm_threads_daemon_threads{job="$job"}'),
                       Target("peak", 'jvm_threads_peak_threads{job="$job"}')],
              archived=None),
        Panel(title="Loaded classes & uptime", unit="short", w=8,
              targets=[Target("loaded classes", 'jvm_classes_loaded_classes{job="$job"}'),
                       Target("uptime (s)", 'process_uptime_seconds{job="$job"}')],
              archived=None),
    ]),
    Section("Spring pools", [
        Panel(title="HikariCP connections", unit="short", w=12,
              targets=[Target("active", 'hikaricp_connections_active{job="$job"}'),
                       Target("pending", 'hikaricp_connections_pending{job="$job"}'),
                       Target("max", 'hikaricp_connections_max{job="$job"}')],
              archived=None),
        Panel(title="Tomcat HTTP threads", unit="short", w=12,
              targets=[Target("busy", 'tomcat_threads_busy_threads{job="$job"}'),
                       Target("current", 'tomcat_threads_current_threads{job="$job"}'),
                       Target("max", 'tomcat_threads_config_max_threads{job="$job"}')],
              archived=None),
    ]),
    Section("PostgreSQL", [
        Panel(title="Database size", unit="bytes", w=8,
              targets=[Target("size", 'pg_database_size_bytes{datname="$db"}')],
              archived=[Target("{{run_id}}", 'replay_series{run_id=~"$runs",metric="db_size"}')]),
        Panel(title="WAL size", unit="bytes", w=8,
              targets=[Target("wal", "pg_wal_size_bytes")], archived=None),
        Panel(title="Active connections by state", unit="short", w=8,
              targets=[Target("{{state}}", 'pg_stat_activity_count{datname="$db"}')], archived=None),
        Panel(title="Transaction rate", unit="ops", w=8,
              targets=[Target("commits", 'rate(pg_stat_database_xact_commit{datname="$db"}[1m])'),
                       Target("rollbacks", 'rate(pg_stat_database_xact_rollback{datname="$db"}[1m])')],
              archived=None),
        Panel(title="Tuple operations rate", unit="ops", w=8,
              targets=[Target("inserted", 'rate(pg_stat_database_tup_inserted{datname="$db"}[1m])'),
                       Target("updated", 'rate(pg_stat_database_tup_updated{datname="$db"}[1m])'),
                       Target("deleted", 'rate(pg_stat_database_tup_deleted{datname="$db"}[1m])'),
                       Target("fetched", 'rate(pg_stat_database_tup_fetched{datname="$db"}[1m])')],
              archived=None),
        Panel(title="Buffer cache hit ratio", unit="percentunit", w=8, max=1,
              targets=[Target("hit ratio",
                              'rate(pg_stat_database_blks_hit{datname="$db"}[1m]) / '
                              '(rate(pg_stat_database_blks_hit{datname="$db"}[1m]) + '
                              'rate(pg_stat_database_blks_read{datname="$db"}[1m]))')],
              archived=None),
        Panel(title="Live rows by table", unit="short", w=8,
              targets=[Target("{{relname}}", 'pg_stat_user_tables_n_live_tup{schemaname="public"}')],
              archived=None),
        Panel(title="Per-table write rate", unit="ops", w=8,
              targets=[Target("{{relname}} ins", 'rate(pg_stat_user_tables_n_tup_ins{schemaname="public"}[1m])'),
                       Target("{{relname}} upd", 'rate(pg_stat_user_tables_n_tup_upd{schemaname="public"}[1m])'),
                       Target("{{relname}} del", 'rate(pg_stat_user_tables_n_tup_del{schemaname="public"}[1m])')],
              archived=None),
        Panel(title="Locks by mode", unit="short", w=8,
              targets=[Target("{{mode}}", 'pg_locks_count{datname="$db"}')], archived=None),
        Panel(title="Checkpoint activity", unit="ops", w=12,
              targets=[Target("timed", "rate(pg_stat_bgwriter_checkpoints_timed_total[1m])"),
                       Target("requested", "rate(pg_stat_bgwriter_checkpoints_req_total[1m])"),
                       Target("write time", "rate(pg_stat_bgwriter_checkpoint_write_time_total[1m])")],
              archived=None),
        Panel(title="Container CPU", unit="percentunit", w=6,
              targets=[Target("{{name}}", 'rate(container_cpu_usage_seconds_total{name=~"$dbc|$apic"}[1m])')],
              archived=[Target("{{run_id}} api", 'replay_step{run_id=~"$runs",metric="container_cpu"}')]),
        Panel(title="Container memory", unit="bytes", w=6,
              targets=[Target("{{name}} rss", 'container_memory_rss{name=~"$dbc|$apic"}'),
                       Target("{{name}} working set", 'container_memory_working_set_bytes{name=~"$dbc|$apic"}')],
              archived=[Target("{{run_id}} api rss", 'replay_step{run_id=~"$runs",metric="container_rss"}')]),
    ]),
]
```

- [ ] **Step 4: Run the tests**

Run: `python3 -m unittest discover -s scripts/tests -v`
Expected: PASS. If `test_no_old_metric_was_dropped` still lists metrics, add the missing panel — do not edit `OLD_METRICS`.

- [ ] **Step 5: Rebuild, verify panel count, commit**

```bash
python3 -m scripts.dashboards.build
python3 -c "import json;d=json.load(open('monitoring/grafana/provisioning/dashboards/the-dashboard.json'));print(len([p for p in d['panels'] if p['type']!='row']),'panels')"
git add scripts/dashboards/spec.py scripts/tests/test_spec.py monitoring/grafana/provisioning/dashboards/
git commit -m "feat(grafana): merge JVM, Spring pool and PostgreSQL panels into the unified dashboard"
```
Expected: ~38 non-row panels.

---

### Task 3: Retire the three old dashboards

**Files:**
- Delete: `monitoring/grafana/provisioning/dashboards/postgres-dashboard.json`
- Delete: `monitoring/grafana/provisioning/dashboards/replay-dashboard.json`
- Modify: `monitoring/grafana/provisioning/dashboards/dashboard.yml`

**Interfaces:**
- Consumes: the generated `the-dashboard.json` from Task 2.
- Produces: nothing.

- [ ] **Step 1: Delete the superseded files**

```bash
git rm monitoring/grafana/provisioning/dashboards/postgres-dashboard.json
git rm monitoring/grafana/provisioning/dashboards/replay-dashboard.json
```
`jvm-spring-dashboard.json` does not exist on this branch — it is removed as part of Task 9 on ES-4.

- [ ] **Step 2: Allow provisioned deletions**

`dashboard.yml` currently sets `disableDeletion: true`, which makes Grafana keep a dashboard in its database after its JSON file is gone — the retired dashboards would linger in the UI forever. Change it:

```yaml
apiVersion: 1

providers:
  - name: inventory
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /etc/grafana/provisioning/dashboards
      foldersFromFilesStructure: false
```

- [ ] **Step 3: Force-recreate Grafana and confirm the old dashboards are gone**

The provisioning directory is a bind mount; a plain restart is enough for file *contents*, but deletions plus a provider config change need a recreate.

```bash
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --force-recreate grafana
sleep 20
curl -s "http://localhost:3000/api/search?type=dash-db" | python3 -c "import json,sys; [print(d['uid'],'|',d['title']) for d in json.load(sys.stdin)]"
```
Expected: exactly `the-dashboard | Inventory — Full Stack` and `bench-replay | Bench Replay (archived runs)`. If `postgres-dashboard` or `jvm-spring` survive, delete them explicitly:
```bash
curl -s -X DELETE "http://localhost:3000/api/dashboards/uid/postgres-dashboard"
curl -s -X DELETE "http://localhost:3000/api/dashboards/uid/jvm-spring"
```

- [ ] **Step 4: Verify the run report still renders**

```bash
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d grafana-renderer grafana-reporter
sleep 10
curl -sf -o /tmp/report-check.pdf "http://localhost:8686/api/v5/report/the-dashboard?from=$(( ($(date +%s) - 900) * 1000 ))&to=$(( $(date +%s) * 1000 ))" && echo "report.pdf path OK"
```
Expected: `report.pdf path OK`. This is the exact call `bench.sh:281` makes; if it fails, the uid was changed and the constraint was violated.

- [ ] **Step 5: Commit**

```bash
git add -A monitoring/grafana/provisioning
git commit -m "chore(grafana): retire the postgres, jvm-spring and hand-written replay dashboards"
```

---

### Task 4: `replay_run.py` v2 — three generic metrics, elapsed anchoring

**Files:**
- Modify: `scripts/replay_run.py`
- Create: `scripts/tests/test_replay_run.py`

**Interfaces:**
- Consumes: `scripts/tests/fixtures/mini-dump.json` from Task 1.
- Produces: `replay_run.build_openmetrics(run_dir, axis) -> (text, run_id, window, count)`, emitting exactly three metric families:
  - `replay_series{run_id,variant,scenario,axis,metric}`
  - `replay_step{run_id,variant,scenario,axis,metric,dim,step,target_rate}`
  - `replay_summary{run_id,variant,scenario,axis,key,dim,window}` where `window` is `full` or `load`

- [ ] **Step 1: Write the failing test**

`scripts/tests/test_replay_run.py`:

```python
import os
import re
import unittest

import importlib.util

HERE = os.path.dirname(__file__)
FIXTURE_DIR = os.path.join(HERE, "fixtures")
spec_ = importlib.util.spec_from_file_location(
    "replay_run", os.path.join(HERE, "..", "replay_run.py"))
replay_run = importlib.util.module_from_spec(spec_)
spec_.loader.exec_module(replay_run)


def samples(text, family):
    return [line for line in text.splitlines() if line.startswith(family + "{")]


def label(line, name):
    match = re.search(rf'{name}="([^"]*)"', line)
    return match.group(1) if match else None


class OpenMetricsGeneration(unittest.TestCase):
    def setUp(self):
        # The fixture directory doubles as a run directory: it contains mini-dump.json,
        # which the test copies to dump.json in a temp dir.
        import json
        import shutil
        import tempfile
        self.run_dir = tempfile.mkdtemp(prefix="replay-test-")
        shutil.copy(os.path.join(FIXTURE_DIR, "mini-dump.json"),
                    os.path.join(self.run_dir, "dump.json"))
        with open(os.path.join(self.run_dir, "meta.json"), "w") as fh:
            json.dump({"variant": "TEST-1", "scenario": "capacity", "steps": [
                {"index": 0, "targetRate": 20, "startsAt": 0, "endsAt": 120},
                {"index": 1, "targetRate": 40, "startsAt": 120, "endsAt": 240}]}, fh)
        self.text, self.run_id, self.window, self.count = \
            replay_run.build_openmetrics(self.run_dir, axis="elapsed")

    def test_emits_exactly_three_families(self):
        families = {line.split("{")[0] for line in self.text.splitlines()
                    if line and not line.startswith("#")}
        self.assertEqual(families, {"replay_series", "replay_step", "replay_summary"})

    def test_elapsed_axis_anchors_the_first_sample(self):
        first = samples(self.text, "replay_series")[0]
        # window.full starts at 1000 in the fixture; the first sample is at t=1000 -> ANCHOR + 0
        self.assertTrue(first.endswith(f" {replay_run.ANCHOR_EPOCH}"), first)

    def test_wall_axis_keeps_original_timestamps(self):
        text, _, _, _ = replay_run.build_openmetrics(self.run_dir, axis="wall")
        first = samples(text, "replay_series")[0]
        self.assertTrue(first.endswith(" 1000"), first)
        self.assertEqual(label(first, "axis"), "wall")

    def test_dict_valued_scalars_become_the_dim_label(self):
        e2e = [s for s in samples(self.text, "replay_step") if label(s, "metric") == "e2e_p95"]
        self.assertEqual(len(e2e), 2, "one sample per step")
        self.assertEqual({label(s, "dim") for s in e2e}, {"confirmed"})

    def test_null_scalars_are_skipped(self):
        self.assertEqual([s for s in samples(self.text, "replay_step")
                          if label(s, "metric") == "opt_retry"], [])

    def test_step_samples_land_at_the_plateau_midpoint(self):
        cpu = [s for s in samples(self.text, "replay_step") if label(s, "metric") == "cpu_avg"]
        # fixture step 0 window is [1010, 1070] -> midpoint 1040 -> elapsed 40
        self.assertTrue(cpu[0].endswith(f" {replay_run.ANCHOR_EPOCH + 40}"), cpu[0])

    def test_summary_carries_both_windows(self):
        windows = {label(s, "window") for s in samples(self.text, "replay_summary")}
        self.assertEqual(windows, {"full"})  # the fixture has no load_window_scalars

    def test_samples_within_a_series_are_time_ordered(self):
        """promtool rejects a series whose samples go backwards."""
        seen = {}
        for line in self.text.splitlines():
            if line.startswith("#") or not line:
                continue
            identity, _, rest = line.partition("} ")
            value, _, ts = rest.rpartition(" ")
            ts = int(ts)
            self.assertGreaterEqual(ts, seen.get(identity, ts), f"out of order: {line}")
            seen[identity] = ts


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python3 -m unittest scripts.tests.test_replay_run -v`
Expected: FAIL — `build_openmetrics() got an unexpected keyword argument 'axis'`, and the family assertion fails because v1 emits `replay_<key>` names.

- [ ] **Step 3: Rewrite the emission half of `scripts/replay_run.py`**

Replace `build_openmetrics` and its helpers. Keep `prometheus_volume`, `docker`, and `main` from v1, adding `--axis {elapsed,wall}` (default `elapsed`), `--both` (emit both axes), and `--volume` defaulting to the replay volume from Task 5.

```python
ANCHOR_EPOCH = 1767225600  # 2026-01-01T00:00:00Z — common origin for the elapsed axis

FAMILIES = ("replay_series", "replay_step", "replay_summary")
HELP = {
    "replay_series": "5s range series from dump.json series{} (metric=<key>)",
    "replay_step":   "per-capacity-step scalar from dump.json per_step[] (metric=<key>, dim=<label>)",
    "replay_summary": "run-level scalar/derived from dump.json (key=<name>, dim=<label>)",
}


def _flatten(value):
    """dump.json scalars are float | None | {label: float}. Yield (dim, float) pairs."""
    if value is None:
        return
    if isinstance(value, bool):
        yield "", float(value)
        return
    if isinstance(value, (int, float)):
        if value == value and value not in (float("inf"), float("-inf")):
            yield "", float(value)
        return
    if isinstance(value, dict):
        for dim, inner in value.items():
            for _, number in _flatten(inner):
                yield str(dim), number


def build_openmetrics(run_dir, axis="elapsed"):
    """Return (openmetrics_text, run_id, window_full, sample_count)."""
    with open(os.path.join(run_dir, "dump.json")) as fh:
        dump = json.load(fh)
    meta = {}
    meta_path = os.path.join(run_dir, "meta.json")
    if os.path.exists(meta_path):
        with open(meta_path) as fh:
            meta = json.load(fh)

    run_id = dump.get("run_id") or os.path.basename(run_dir.rstrip("/"))
    window = (dump.get("windows") or {}).get("full") or (dump.get("windows") or {}).get("load")
    if not window:
        die(f"{run_id}: dump.json has no windows.full")
    base = {
        "run_id": run_id,
        "variant": dump.get("variant") or meta.get("variant", "unknown"),
        "scenario": dump.get("scenario") or meta.get("scenario", "unknown"),
        "axis": axis,
    }

    def stamp(t):
        return int(t) if axis == "wall" else ANCHOR_EPOCH + int(t) - int(window[0])

    rows = {family: [] for family in FAMILIES}

    def add(family, labels, value, t):
        merged = dict(base, **labels)
        rendered = ",".join(f'{k}="{escape(v)}"' for k, v in merged.items())
        rows[family].append((stamp(t), f"{family}{{{rendered}}} {value!r} {stamp(t)}"))

    series = dict(dump.get("series") or {})
    tr = target_rate_series(meta, window)
    if tr:
        series["target_rate"] = tr
    for key in sorted(series):
        for t, value in sorted(series[key], key=lambda p: p[0]):
            for _, number in _flatten(value):
                add("replay_series", {"metric": key}, number, t)

    for step in dump.get("per_step") or []:
        start, end = step["window"]
        midpoint = (start + end) // 2
        step_labels = {"step": str(step["index"]), "target_rate": str(step.get("target_rate", ""))}
        for source in (step.get("scalars") or {}, step.get("derived") or {}):
            for key in sorted(source):
                for dim, number in _flatten(source[key]):
                    add("replay_step", dict(step_labels, metric=key, dim=dim), number, midpoint)

    for window_name, block in (("full", dump.get("scalars")), ("load", dump.get("load_window_scalars"))):
        for key in sorted(block or {}):
            for dim, number in _flatten(block[key]):
                add("replay_summary", {"key": key, "dim": dim, "window": window_name}, number, window[1])
    for key in sorted(dump.get("derived") or {}):
        for dim, number in _flatten(dump["derived"][key]):
            add("replay_summary", {"key": key, "dim": dim, "window": "full"}, number, window[1])

    lines, total = [], 0
    for family in FAMILIES:
        if not rows[family]:
            continue
        lines.append(f"# HELP {family} {HELP[family]}")
        lines.append(f"# TYPE {family} gauge")
        for _, line in sorted(rows[family], key=lambda pair: pair[0]):
            lines.append(line)
            total += 1
    lines.append("# EOF")
    return "\n".join(lines) + "\n", run_id, window, total
```

> Sorting each family by timestamp is what keeps `promtool` happy: it appends in file order and
> rejects a series whose samples move backwards. Sorting by timestamp across the whole family is
> stricter than per-series ordering and therefore always safe.

- [ ] **Step 4: Run the tests**

Run: `python3 -m unittest scripts.tests.test_replay_run -v`
Expected: PASS, all 8 tests.

- [ ] **Step 5: Commit**

```bash
git add scripts/replay_run.py scripts/tests/test_replay_run.py
git commit -m "feat(replay): emit replay_series/step/summary with a common elapsed axis"
```

---

### Task 5: Dedicated long-retention replay Prometheus

**Files:**
- Create: `docker-compose.replay.yml`, `monitoring/prometheus/prometheus-replay.yml`
- Modify: `monitoring/grafana/provisioning/datasources/prometheus.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: datasource uid `prometheus-replay` (referenced by `build.DS_REPLAY` from Task 1); docker volume `bench-replay-data`; Prometheus at `localhost:9091`.

- [ ] **Step 1: Create the external volume**

```bash
docker volume create bench-replay-data
```
It is declared `external: true` so `docker compose down -v` cannot delete it — that is the whole point: the ES-4 replay was lost once already because the archive lived in a project volume.

- [ ] **Step 2: Write the scrape-free Prometheus config**

`monitoring/prometheus/prometheus-replay.yml`:

```yaml
# Archive-only Prometheus. It scrapes nothing; every series is backfilled from
# bench-results/<run_id>/dump.json by scripts/replay_run.py.
global:
  scrape_interval: 1h
scrape_configs: []
```

- [ ] **Step 3: Write the compose override**

`docker-compose.replay.yml`:

```yaml
# Archive Prometheus for replayed benchmark runs.
#
#   docker compose -f docker-compose.yml -f docker-compose.replay.yml up -d prometheus-replay
#
# Deliberately NOT part of docker-compose.bench.yml: bench.sh must not start, stop or
# depend on it, and its volume is external so `docker compose down -v` leaves it alone.
services:
  prometheus-replay:
    image: prom/prometheus:v2.52.0
    container_name: prometheus-replay
    ports:
      - "9091:9090"
    volumes:
      - ./monitoring/prometheus/prometheus-replay.yml:/etc/prometheus/prometheus.yml:ro
      - bench-replay-data:/prometheus
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      # The archive must outlive the thesis. Default retention is 15d, which would silently
      # delete replayed runs anchored at 2026-01-01.
      - --storage.tsdb.retention.time=3650d
      - --storage.tsdb.retention.size=0
      - --web.enable-admin-api
      - --web.enable-lifecycle

volumes:
  bench-replay-data:
    external: true
```

- [ ] **Step 4: Add the Grafana datasource**

`monitoring/grafana/provisioning/datasources/prometheus.yml`:

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    uid: prometheus
    url: http://prometheus:9090
    access: proxy
    isDefault: true
    editable: false

  # Archive of replayed benchmark runs. Only the Bench Replay dashboard reads it; it is empty
  # (and its panels error) unless docker-compose.replay.yml is up.
  - name: Prometheus Replay
    type: prometheus
    uid: prometheus-replay
    url: http://prometheus-replay:9090
    access: proxy
    isDefault: false
    editable: false
```

- [ ] **Step 5: Bring it up and verify both datasources are healthy**

```bash
docker compose -f docker-compose.yml -f docker-compose.replay.yml up -d prometheus-replay
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --force-recreate grafana
sleep 20
curl -s "http://localhost:3000/api/datasources" | python3 -c "import json,sys; [print(d['uid'], d['url']) for d in json.load(sys.stdin)]"
curl -s "http://localhost:9091/-/ready"
```
Expected: both uids listed; `Prometheus Server is Ready.`

> The replay Prometheus joins the compose project network, so `http://prometheus-replay:9090`
> resolves from the Grafana container. If Grafana was started before the service existed, the
> `--force-recreate` above is what attaches it.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.replay.yml monitoring/prometheus/prometheus-replay.yml monitoring/grafana/provisioning/datasources/prometheus.yml
git commit -m "feat(replay): dedicated long-retention Prometheus on an external volume"
```

---

### Task 6: Point `replay_run.py` at the replay Prometheus and backfill the archive

**Files:**
- Modify: `scripts/replay_run.py` (`prometheus_volume` default, container name, `--all` flag)

**Interfaces:**
- Consumes: `bench-replay-data` volume and `prometheus-replay` container from Task 5; `build_openmetrics` from Task 4.
- Produces: a populated archive; `replay_run.main()` accepting `--all`.

- [ ] **Step 1: Change the defaults**

In `scripts/replay_run.py`, replace `prometheus_volume()` and the container references:

```python
REPLAY_VOLUME = "bench-replay-data"
REPLAY_CONTAINER = "prometheus-replay"


def prometheus_volume():
    """Volume backing the replay archive, read from the container when it exists."""
    proc = subprocess.run(
        ["docker", "inspect", REPLAY_CONTAINER, "--format",
         '{{range .Mounts}}{{if eq .Destination "/prometheus"}}{{.Name}}{{end}}{{end}}'],
        capture_output=True, text=True)
    return proc.stdout.strip() or REPLAY_VOLUME
```

Every `docker stop prometheus` / `docker start prometheus` in `main()` becomes `REPLAY_CONTAINER`, and the `-f name=^prometheus$` filter becomes `-f name=^{REPLAY_CONTAINER}$`. Add to the argument parser:

```python
ap.add_argument("--axis", choices=["elapsed", "wall"], default="elapsed",
                help="elapsed: anchor every run to a common origin so runs overlay (default). "
                     "wall: keep original timestamps, matching report.pdf and meta.json.")
ap.add_argument("--all", action="store_true",
                help="replay every bench-results/*/ that has a dump.json")
```

and, at the top of `main()`:

```python
    run_dirs = args.run_dirs
    if args.all:
        run_dirs = sorted(
            os.path.dirname(p) for p in
            glob.glob(os.path.join(REPO_ROOT, "bench-results", "*", "dump.json")))
```

(`import glob` at the top.) Print the dashboard URL against port 3000 with `var-runs=` repeated per run.

- [ ] **Step 2: Replay one run and verify the three families landed**

```bash
python3 scripts/replay_run.py bench-results/ES-4_capacity_20260805T154022Z
curl -sG 'http://localhost:9091/api/v1/label/__name__/values' \
  --data-urlencode 'match[]={__name__=~"replay_.*"}' | python3 -m json.tool
```
Expected: exactly `replay_series`, `replay_step`, `replay_summary`.

- [ ] **Step 3: Verify fidelity against `dump.json`**

```bash
curl -sG 'http://localhost:9091/api/v1/query' \
  --data-urlencode 'query=replay_summary{run_id="ES-4_capacity_20260805T154022Z",key="backlog_at_stop"}' \
  --data-urlencode 'time=1767230401' | python3 -m json.tool
python3 -c "import json;print(json.load(open('bench-results/ES-4_capacity_20260805T154022Z/dump.json'))['derived']['backlog_at_stop'])"
```
Expected: both print `134639`.

- [ ] **Step 4: Backfill the whole archive**

```bash
python3 scripts/replay_run.py --all
curl -sG 'http://localhost:9091/api/v1/label/run_id/values' | python3 -c "import json,sys; v=json.load(sys.stdin)['data']; print(len(v),'runs'); [print(' ',r) for r in v]"
```
Expected: 36 runs (every `bench-results/*/` that has a `dump.json`).

- [ ] **Step 5: Verify multi-run overlay in the dashboard**

```bash
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d grafana-renderer
sleep 8
curl -s -o /tmp/overlay.pdf -w "%{http_code}\n" \
  "http://localhost:3000/render/d/bench-replay/?orgId=1&from=1767225600000&to=1767230400000&var-runs=ES-4_capacity_20260805T154022Z&var-runs=TO-3_capacity_20260805T170427Z&width=1500&height=1400&kiosk"
```
Expected: `200`, and opening the PDF shows both runs' series in every populated panel, plus a two-row run-summary table at the top.

- [ ] **Step 6: Commit**

```bash
git add scripts/replay_run.py
git commit -m "feat(replay): target the archive Prometheus and add --all"
```

---

### Task 7: README for the archive workflow

**Files:**
- Create: `docs/bench-replay.md`
- Modify: `README.md` (add a link in whatever section lists tooling)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Write `docs/bench-replay.md`**

Cover, with runnable commands: the three metric families and their labels; `--axis elapsed` vs `wall`; that the archive Prometheus is external-volume backed and survives `down -v`; the coverage table from this plan (what dump.json cannot show); that `the-dashboard.json` and `bench-replay.json` are generated and `spec.py` is the file to edit; and that a fresh machine needs `docker volume create bench-replay-data` before `up`.

- [ ] **Step 2: Verify every command in the doc actually runs**

Run each fenced command block in order on a clean checkout. Fix anything that fails.

- [ ] **Step 3: Commit**

```bash
git add docs/bench-replay.md README.md
git commit -m "docs: how to replay archived benchmark runs into Grafana"
```

---

### Task 8 (optional — the only path to true parity for future runs)

Archived runs can never show the ~30 signals `dump.json` does not carry. Future runs can, by keeping the TSDB.

**Files:**
- Create: `scripts/prom_archive.sh`
- Modify: `scripts/prom_snapshot.sh` (write into `bench-results/<run_id>/prom-snapshot/`)

- [ ] **Step 1: Extend `prom_snapshot.sh` to take a run id**

`./scripts/prom_snapshot.sh <run_id>` snapshots the live Prometheus into `bench-results/<run_id>/prom-snapshot/` instead of `reports/`. Keep the old label-based behaviour when the argument is not an existing run directory.

- [ ] **Step 2: Write `scripts/prom_archive.sh`**

Copies a snapshot's blocks *into* `bench-replay-data` rather than `rm -rf`-ing the volume the way `prom_restore.sh` does — benchmark runs never overlap in time, so blocks from many runs coexist and the merged dashboard works for each by selecting its time range:

```bash
docker compose -f docker-compose.yml -f docker-compose.replay.yml stop prometheus-replay
docker run --rm -v bench-replay-data:/prometheus -v "$SNAPSHOT_DIR:/snapshot:ro" \
    alpine sh -c 'cp -rn /snapshot/*/ /prometheus/'
docker compose -f docker-compose.yml -f docker-compose.replay.yml start prometheus-replay
```

- [ ] **Step 3: Prove it end to end**

Run a short bench (`SCENARIO=steady DURATION=2m`), snapshot it, archive it, then point the **live** merged dashboard's time picker at that window using the `prometheus-replay` datasource — every panel, including the 21 `pg_stat_*` ones, must show data.

- [ ] **Step 4: Measure the cost and write it down**

`du -sh bench-results/<run_id>/prom-snapshot/`. Record the per-run size in `docs/bench-replay.md` so the disk budget for the remaining thesis runs is known.

- [ ] **Step 5: Commit**

```bash
git add scripts/prom_snapshot.sh scripts/prom_archive.sh docs/bench-replay.md
git commit -m "feat(bench): per-run TSDB snapshots archived into the replay Prometheus"
```

---

### Task 9: Propagate to the other variant branches

**Files:** all files from Tasks 1–8, on every variant branch.

The merged dashboard is only useful if TO and ES runs render identically — that is the entire point of the `$job` / `$db` / `$dbc` / `$apic` variables replacing the hardcoded `inventory-to` / `postgres-to` pins.

- [ ] **Step 1: List the branches that carry the harness**

```bash
git branch --list | tr -d ' *'
```
Expected: the TO-1..TO-4 and ES-1..ES-4 families.

- [ ] **Step 2: Cherry-pick onto each branch**

For every branch other than the one this was built on:

```bash
git switch <branch>
git cherry-pick <first-commit>..<last-commit>
```

On ES-4 the cherry-pick additionally has to delete `monitoring/grafana/provisioning/dashboards/jvm-spring-dashboard.json`, which only exists there:

```bash
git rm monitoring/grafana/provisioning/dashboards/jvm-spring-dashboard.json
git commit --amend --no-edit
```

- [ ] **Step 3: Verify the harness files were not touched**

On each branch:

```bash
git diff --stat ES-2 HEAD -- k6 docker-compose.bench.yml
```
Expected: empty output. A non-empty diff means a global constraint was violated — revert and redo.

- [ ] **Step 4: Verify the dashboard is byte-identical across branches**

```bash
for b in TO-1 TO-2 TO-3 TO-4 ES-1 ES-2 ES-3-optimistic ES-4; do
  printf '%s %s\n' "$b" "$(git show $b:monitoring/grafana/provisioning/dashboards/the-dashboard.json | sha256sum | cut -c1-12)"
done
```
Expected: one identical hash on every line. Different hashes mean a branch still carries a family-specific pin.

- [ ] **Step 5: Smoke-test on one branch of each family**

Check out TO-3, bring the stack up, confirm the TO rows populate and the ES-only panels are empty; then the same on ES-4 with the families reversed.

---

## Self-Review

**Spec coverage.** "Merge all 3 panels into one with no duplicate metrics" → Tasks 1–3, gated by `test_no_duplicate_live_expression` and `test_no_old_metric_was_dropped`. "Load runs from dump into archived runs dashboard" → Tasks 4–6. "Should show all what normal dashboard shows" → same spec generates both dashboards, so the archived one mirrors the live structure exactly; where `dump.json` has nothing, the build emits an explicit "not available" list rather than a blank panel, and Task 8 is the route to genuine parity for future runs. "With possibility to load multiple of them" → `axis="elapsed"` anchoring plus the multi-value `$runs` variable and the per-run summary table.

**Known gaps, stated rather than hidden.** The archived dashboard cannot show HTTP breakdowns by uri/status, GC, threads, HikariCP, Tomcat, executor queues, or any `pg_stat_*` metric for runs already archived — that data no longer exists. The per-step panels carry 10 points per run, not a continuous line; they are plotted with `showPoints: auto` so this reads as sampled rather than smooth. Task 8 is the only fix, and only for runs from here on.

**Open decision for the implementer.** Task 3 sets `disableDeletion: false`, which also means a provisioning mistake can now delete dashboards from Grafana's database. The alternative is leaving it `true` and deleting the two retired uids once by API. Either is defensible; the plan takes the first because it keeps disk and Grafana in sync automatically.
