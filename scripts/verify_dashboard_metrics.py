#!/usr/bin/env python3
"""Ask a live Prometheus whether every dashboard expression actually returns anything.

The unit tests around scripts/dashboards/ are structural: they prove expressions are unique,
panels tile the grid, and no metric NAME was dropped in the five-dashboard merge. None of that
notices a panel querying a metric the application never publishes — which is how
jvm_gc_pause_seconds_bucket, tomcat_threads_* and r2dbc_pool_* sat in the dashboard rendering
nothing. This script closes that gap by running each target against Prometheus and reporting
which return no series.

Three outcomes per target:

    OK       returns at least one series
    EMPTY    the metric names exist, but this selector matches nothing right now (a counter
             that has not fired yet, an idle stack, or a label that never occurs)
    MISSING  Prometheus has never seen one of the metric names at all — the strong signal

MISSING on a metric belonging to the other family is expected: one stack is either TO or ES,
never both, so run this against a stack of each family before concluding a panel is dead. A
metric MISSING on both families, with no meter registered on any branch, is a dead panel.

An idle stack cannot prove a counter is absent (Micrometer registers many meters lazily, on
first increment), so run this while load is flowing, or point it at the archive, which holds a
full TSDB snapshot of a completed run.

Usage:
    python3 scripts/verify_dashboard_metrics.py                   # live dashboard, :9090
    python3 scripts/verify_dashboard_metrics.py --dashboard runs  # archived runs, :9091
    python3 scripts/verify_dashboard_metrics.py --dashboard runs --run TO-3
"""
import argparse
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

DASHBOARDS = {
    "live": "monitoring/grafana/provisioning/dashboards/the-dashboard.json",
    "runs": "monitoring/grafana/provisioning/dashboards/bench-runs.json",
}
# scripts/dashboards/runs.py ANCHOR_EPOCH. Duplicated rather than imported because this script
# runs as a path script (`python3 scripts/verify_dashboard_metrics.py`), which puts scripts/ on
# sys.path, not the repo root — `from scripts.dashboards import runs` would not resolve.
# test_runs.AnchorsAgree fails if the two drift apart.
ANCHOR_RUNS = 1788220800

DEFAULT_VARS = {
    "live": {"job": "inventory-to", "db": "inventory", "dbc": "postgres-to",
             "apic": "api", "__rate_interval": "1m",
             "__interval": "15s", "__range": "1h"},
    # $run is not listed: its value is a per-run offset read out of the dashboard's own
    # variable options, so --run picks a run rather than a raw duration.
    "runs": {"job": "inventory", "db": "inventory", "dbc": "postgres",
             "apic": "api|.*-api-[0-9]+", "__rate_interval": "1m", "__interval": "15s",
             "__range": "1h"},
}

# PromQL function and keyword names, so they are not mistaken for metric names. Label names need
# no listing: string literals and grouping clauses are both blanked before matching, which leaves
# only bare identifiers in metric position.
KEYWORDS = {
    "sum", "rate", "irate", "avg", "max", "min", "count", "increase", "histogram_quantile",
    "by", "without", "topk", "bottomk", "quantile", "stddev", "stdvar", "delta", "idelta",
    "abs", "ceil", "floor", "round", "clamp", "clamp_max", "clamp_min", "time", "vector",
    "scalar", "sort", "sort_desc", "last_over_time", "max_over_time", "min_over_time",
    "avg_over_time", "sum_over_time", "count_over_time", "resets", "changes", "deriv",
    "predict_linear", "or", "and", "unless", "on", "ignoring", "group_left", "group_right",
    "bool", "offset", "le",
}
STRING = re.compile(r'"[^"]*"')
GROUPING = re.compile(r'\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)')
NAME = re.compile(r'(?<![\w:.])([a-zA-Z_][a-zA-Z0-9_]*)\s*(?=\{|\[|\)|$|\s*[-+/*])')


def metric_names(expr):
    """Metric names in `expr`.

    String literals are blanked so label VALUES never leak in ("inventory-to" must not read as a
    metric named `inventory`), and `by (le, status)` clauses are blanked so label NAMES do not
    either. Both bit an earlier version of this check and produced false MISSING reports.
    """
    stripped = GROUPING.sub("", STRING.sub('""', expr))
    return {m.group(1) for m in NAME.finditer(stripped)} - KEYWORDS


class Prom:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def get(self, path, **params):
        url = f"{self.base}{path}?" + urllib.parse.urlencode(params)
        try:
            with urllib.request.urlopen(url, timeout=30) as fh:
                return json.load(fh)
        except urllib.error.URLError as exc:
            sys.exit(f"cannot reach Prometheus at {self.base}: {exc}")

    def known_names(self):
        return set(self.get("/api/v1/label/__name__/values")["data"])

    def instant(self, expr, at=None):
        params = {"query": expr}
        if at is not None:
            params["time"] = at
        res = self.get("/api/v1/query", **params)
        if res["status"] != "success":
            raise RuntimeError(res.get("error", "query failed"))
        return len(res["data"]["result"])


def targets(dashboard_path):
    with open(dashboard_path) as fh:
        dashboard = json.load(fh)
    for panel in dashboard["panels"]:
        if panel.get("type") == "row":
            continue
        for target in panel.get("targets", []):
            if target.get("expr"):
                yield panel["title"], target.get("legendFormat") or "-", target["expr"]


def pick_run(dashboard_path, wanted):
    """(label, offset) for one option of bench-runs' `run` variable."""
    with open(dashboard_path) as fh:
        options = next(v for v in json.load(fh)["templating"]["list"]
                       if v["name"] == "run")["options"]
    if not options:
        sys.exit(f"{dashboard_path} has no runs; rebuild it with build.py --runs <dir>")
    if wanted:
        matches = [o for o in options if wanted in o["text"]]
        if not matches:
            sys.exit(f"no run matching {wanted!r}. Options:\n  "
                     + "\n  ".join(o["text"] for o in options))
        options = matches
    return options[0]["text"], options[0]["value"]


def marker_end(prom, offset):
    """The selected run's end, from the marker series scripts/run_markers.py backfills."""
    res = prom.get("/api/v1/query", query=f'bench_run_marker{{offset="{offset}"}}',
                   time=ANCHOR_RUNS + 60)
    series = res.get("data", {}).get("result", [])
    if not series:
        sys.exit(f"no bench_run_marker with offset={offset}. Backfill the markers first:\n"
                 f"  python3 scripts/run_markers.py <run-dirs>")
    return series[0]["metric"]["end_at"]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dashboard", choices=sorted(DASHBOARDS), default="live")
    ap.add_argument("--prometheus", help="default: :9090 for live, :9091 for the archive")
    ap.add_argument("--var", action="append", default=[], metavar="NAME=VALUE",
                    help="override a template variable, e.g. --var job=inventory-es")
    ap.add_argument("--run", metavar="SUBSTRING",
                    help="--dashboard runs: which run's option to check (matched against the "
                         "dropdown label); default is the first option")
    args = ap.parse_args()

    variables = dict(DEFAULT_VARS[args.dashboard])
    for override in args.var:
        name, _, value = override.partition("=")
        variables[name.lstrip("$")] = value

    prom = Prom(args.prometheus or
                ("http://localhost:9090" if args.dashboard == "live" else "http://localhost:9091"))
    known = prom.known_names()

    if args.dashboard == "runs":
        # Mirrors what Grafana does with the two chained variables: $run is picked from the
        # dropdown, $end follows from the marker series carrying that same offset as a label.
        label, offset = pick_run(DASHBOARDS["runs"], args.run)
        variables["run"] = offset
        variables["end"] = marker_end(prom, offset)
        print(f"run: {label}  ($run = {offset}, $end = {variables['end']})")

    # bench-runs' samples sit at their real wall-clock time and the QUERY carries the offset, so
    # the probe times are inside its anchor window and only need to be far enough in for a 1m
    # rate to have data on both sides. The live dashboard just evaluates at "now".
    if args.dashboard == "runs":
        anchor, offsets = ANCHOR_RUNS, [600, 1800, 3000]
    else:
        anchor, offsets = 0, [None]

    buckets = {"OK": [], "EMPTY": [], "MISSING": [], "ERROR": []}
    for title, legend, expr in targets(DASHBOARDS[args.dashboard]):
        query = expr
        # Longest name first: with both `run` and `runs` in play, replacing `$run` first would
        # turn `$runs` into a value followed by a stray "s".
        for name in sorted(variables, key=len, reverse=True):
            query = query.replace(f"${name}", variables[name])
        try:
            hits = 0
            for offset in offsets:
                hits = prom.instant(query, None if offset is None else anchor + offset)
                if hits:
                    break
        except RuntimeError as exc:
            buckets["ERROR"].append((title, legend, str(exc), query))
            continue
        if hits:
            buckets["OK"].append((title, legend, f"{hits} series", query))
        else:
            absent = sorted(n for n in metric_names(query) if n not in known)
            buckets["MISSING" if absent else "EMPTY"].append(
                (title, legend, ",".join(absent) or "selector matches nothing", query))

    for tag in ("ERROR", "MISSING", "EMPTY", "OK"):
        rows = buckets[tag]
        print(f"\n===== {tag}: {len(rows)} =====")
        for title, legend, detail, query in rows:
            print(f"  [{title}] {legend}  ->  {detail}")
            if tag != "OK":
                print(f"      {query}")

    total = sum(len(v) for v in buckets.values())
    print(f"\n{len(buckets['OK'])}/{total} targets return data")
    return 1 if buckets["ERROR"] or buckets["MISSING"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
