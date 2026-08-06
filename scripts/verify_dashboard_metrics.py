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
first increment), so run this while load is flowing, or point it at the replay archive, which
holds a full TSDB snapshot of a completed run.

Usage:
    python3 scripts/verify_dashboard_metrics.py                       # live dashboard, :9090
    python3 scripts/verify_dashboard_metrics.py --dashboard archived  # replay dashboard, :9091
    python3 scripts/verify_dashboard_metrics.py --var job=inventory-es --var dbc=postgres-es
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
    "archived": "monitoring/grafana/provisioning/dashboards/bench-replay.json",
}
# Matches scripts/replay_run.py: every archived run is re-anchored to this epoch, so an instant
# query at "now" sees nothing — the samples sit years in the past, far outside the 5-minute
# staleness window. Evaluate inside the anchored window instead.
ANCHOR_EPOCH = 1767225600

DEFAULT_VARS = {
    "live": {"job": "inventory-to", "db": "inventory", "dbc": "postgres-to",
             "apic": "inventoryitemreservation-api-to-1", "__rate_interval": "1m",
             "__interval": "15s", "__range": "1h"},
    "archived": {"runs": ".*", "__rate_interval": "1m", "__range": "3650d"},
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


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dashboard", choices=sorted(DASHBOARDS), default="live")
    ap.add_argument("--prometheus", help="default: :9090 for live, :9091 for archived")
    ap.add_argument("--var", action="append", default=[], metavar="NAME=VALUE",
                    help="override a template variable, e.g. --var job=inventory-es")
    args = ap.parse_args()

    variables = dict(DEFAULT_VARS[args.dashboard])
    for override in args.var:
        name, _, value = override.partition("=")
        variables[name.lstrip("$")] = value

    prom = Prom(args.prometheus or
                ("http://localhost:9091" if args.dashboard == "archived" else "http://localhost:9090"))
    known = prom.known_names()

    # Archived samples live at the anchor epoch, so probe across a run-length spread of offsets:
    # a per-step metric has one sample per capacity step and is absent between them.
    offsets = [60, 300, 600, 1200, 1800, 3600] if args.dashboard == "archived" else [None]

    buckets = {"OK": [], "EMPTY": [], "MISSING": [], "ERROR": []}
    for title, legend, expr in targets(DASHBOARDS[args.dashboard]):
        query = expr
        for name, value in variables.items():
            query = query.replace(f"${name}", value)
        try:
            hits = 0
            for offset in offsets:
                hits = prom.instant(query, None if offset is None else ANCHOR_EPOCH + offset)
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
