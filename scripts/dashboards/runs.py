"""The archived-run browser dashboard: one dropdown that swaps between whole benchmark runs.

This is the only way to look at a finished run. `the-dashboard` watches the live stack and
nothing else; this module generates `bench-runs`, where picking a run from a dropdown redraws
every panel over an axis that always starts at that run's own t0 -- no epoch timestamps
copied out of meta.json into a time picker, and no way to end up looking at the wrong run.

HOW THE RE-ANCHORING WORKS. The archive is untouched -- every run still sits at its real
wall-clock time, exactly where scripts/prom_archive.sh put it. What moves is the query: the
dashboard's time range is pinned to a fixed anchor window, and every selector is rewritten
with `offset $run`, where the `run` variable's VALUE is that run's distance from the anchor
(a PromQL duration such as `1571234s`) and its LABEL is the run's name. Selecting a run
therefore subtracts its own age, pulling its window into the anchor's. Runs of different
lengths and different days all land on the same axis, so flipping the dropdown redraws every
panel in place and the panels visually diff against each other.

    time range:  [ANCHOR, ANCHOR+span]  (never moves)
    TO-1 run  ->  offset 1677219s  ->  reaches 2026-08-12T14:06Z
    ES-4 run  ->  offset 1587200s  ->  reaches 2026-08-13T14:47Z

The anchor sits AFTER the whole campaign, because reaching forward in time needs a negative offset and Prometheus rejects those unless started
with --enable-feature=promql-negative-offset. scan_runs() refuses to build a dashboard for a
run recorded after the anchor rather than emit a query that 400s.

This is the full-fidelity view -- real scraped TSDB, every live panel, including the
pg_stat_*/WAL/HikariCP/outbox ones. What it deliberately cannot do is overlay two runs as
separate lines in one panel: a single query carries a single offset. Comparing two runs means
flipping the dropdown between them, which works because they share an axis -- the panels sit
in the same place and redraw in place. For a side-by-side of the numbers rather than the
curves, use the per-run summary in bench-results/<run_id>/ instead.
"""
import datetime
import glob
import os
from dataclasses import dataclass

from . import build, spec

# 2026-09-01T00:00:00Z -- after every run of the campaign, so all offsets are positive.
ANCHOR_EPOCH = 1788220800
ANCHOR_ISO = "2026-09-01T00:00:00.000Z"

# The workload points, in the order the campaign walks them. Runs are grouped by point first
# so the dropdown reads as "same workload, next variant" -- the comparison actually being made
# -- rather than interleaving workloads under each variant.
POINT_ORDER = ["W-base", "W-hot", "W-fan"]

# Identifiers that are PromQL syntax rather than metric names. Anything here is emitted
# untouched; the ones in LABEL_LIST additionally swallow the parenthesised label list that
# follows them, so `by (le, uri)` does not read as two bare metric selectors.
KEYWORDS = {"by", "without", "on", "ignoring", "group_left", "group_right",
            "and", "or", "unless", "offset", "bool", "atan2", "start", "end"}
LABEL_LIST = {"by", "without", "on", "ignoring", "group_left", "group_right"}

# Aggregation operators. These need naming separately from ordinary functions because of the
# prefix form: in `sum by (outcome) (rate(m[1m]))` the operator is followed by `by`, not by
# `(`, so the "identifier followed by a paren is a function" rule does not see it and it reads
# as a bare metric selector -- yielding `sum offset $run by (outcome) (...)`, which does not
# parse. Every one of them is listed rather than inferred; a metric named `sum` is pathological
# enough to ignore.
AGGREGATORS = {"sum", "min", "max", "avg", "group", "stddev", "stdvar", "count",
               "count_values", "bottomk", "topk", "quantile", "limitk", "limit_ratio"}


def _skip_string(expr, i):
    quote, j = expr[i], i + 1
    while j < len(expr):
        if expr[j] == "\\":
            j += 2
            continue
        if expr[j] == quote:
            return j + 1
        j += 1
    return j


def _skip_balanced(expr, i, open_ch, close_ch):
    depth, j = 0, i
    while j < len(expr):
        c = expr[j]
        if c in "\"'`":
            j = _skip_string(expr, j)
            continue
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    return j


def _skip_ws(expr, i):
    while i < len(expr) and expr[i].isspace():
        i += 1
    return i


def offset_expr(expr, var="$run"):
    """Append `offset $var` to every vector selector in `expr`, and to nothing else.

    A missed selector is not a syntax error -- it queries the anchor window, which holds no
    data, so the panel renders empty beside populated ones with nothing to say why. The
    inverse mistake is louder but just as easy: `by (le, uri)` and the contents of a label
    matcher are full of identifiers that look exactly like metric names.

    An identifier is a metric name unless it is followed by `(` (a function call) or is PromQL
    syntax. The offset attaches after the matchers and after the range, because
    `rate(m offset 5m [1m])` does not parse -- it has to be `rate(m[1m] offset 5m)`.
    """
    out, i, n = [], 0, len(expr)
    while i < n:
        c = expr[i]
        if c in "\"'`":
            j = _skip_string(expr, i)
            out.append(expr[i:j])
            i = j
        elif c == "{":
            # Only reachable for a matcher not preceded by a metric name (`{__name__="x"}`),
            # which spec.py does not use -- but skipping it wholesale is what keeps the label
            # values inside from being read as metric names.
            j = _skip_balanced(expr, i, "{", "}")
            out.append(expr[i:j])
            i = j
        elif c.isalpha() or c in "_:":
            j = i
            while j < n and (expr[j].isalnum() or expr[j] in "_:"):
                j += 1
            ident = expr[i:j]
            after = _skip_ws(expr, j)
            if ident in KEYWORDS or ident in AGGREGATORS:
                out.append(ident)
                i = j
                if ident in LABEL_LIST and after < n and expr[after] == "(":
                    end = _skip_balanced(expr, after, "(", ")")
                    out.append(expr[j:end])
                    i = end
            elif after < n and expr[after] == "(":
                out.append(ident)          # function call
                i = j
            else:
                end = j
                probe = _skip_ws(expr, end)
                if probe < n and expr[probe] == "{":
                    end = _skip_balanced(expr, probe, "{", "}")
                probe = _skip_ws(expr, end)
                if probe < n and expr[probe] == "[":
                    end = _skip_balanced(expr, probe, "[", "]")
                out.append(expr[i:end])
                out.append(f" offset {var}")
                i = end
        else:
            out.append(c)
            i += 1
    return "".join(out)


# Stops a panel at the selected run's own end. The anchor window is sized for the LONGEST run and
# the campaign ran back-to-back, so without this a 60-minute run in a 76-minute window draws ~13
# minutes of the NEXT run's warm-up as though it belonged to the selection, and a 10-minute run
# draws three runs in a row — indistinguishable from the runs having been merged.
#
# `and on()` gates the left-hand side on the right-hand side existing, matching on no labels, so
# the panel's own series, legends and grouping are untouched; the right-hand side is a bare
# true/false on the evaluation instant. $end comes from the marker series that
# scripts/run_markers.py backfills — a PromQL offset must be a literal duration, so the dropdown's
# value is already spoken for and the run's end has to arrive as data. Applied once per target
# rather than per selector: it is a property of the query's evaluation time, not of any one metric.
CLIP = " and on() (vector(time()) < vector($end))"


def clip_expr(expr):
    return f"({expr}){CLIP}"


@dataclass
class Run:
    run_id: str
    variant: str
    family: str
    point: str
    start: int
    end: int

    @property
    def offset(self):
        return ANCHOR_EPOCH - self.start

    @property
    def seconds(self):
        return self.end - self.start

    @property
    def label(self):
        """Dropdown text. Grafana splits a custom variable's option list on `,` and `:`, so
        the timestamp is `MMDD-HHMM` rather than anything with a colon in it -- a label like
        `TO-1 W-base 14:05` would silently become two broken options."""
        when = datetime.datetime.fromtimestamp(self.start, datetime.timezone.utc)
        return f"{self.variant} · {self.point} · {when:%m%d-%H%M}"


def scan_runs(roots):
    """Every completed run under `roots`, ordered as the dropdown should read.

    A run qualifies when it has both a meta.json (it finished) and a prom-snapshot/ (its TSDB
    was captured, so scripts/prom_archive.sh had something to merge into bench-replay-data).
    Runs missing either are skipped: an aborted run has nothing to show, and a run whose TSDB
    was never captured would fill every panel with "No data" and no indication that the
    dashboard is fine and the data simply is not there. Roots are scanned one and two levels
    deep, so both `bench-results/` and a campaign directory holding per-phase subdirectories
    work as arguments.
    """
    found = {}
    for root in roots:
        for depth in ("*", os.path.join("*", "*")):
            for path in sorted(glob.glob(os.path.join(root, depth, "meta.json"))):
                if not os.path.isdir(os.path.join(os.path.dirname(path), "prom-snapshot")):
                    continue
                meta = _read_json(path)
                run_id = meta.get("run_id") or os.path.basename(os.path.dirname(path))
                if run_id in found:
                    continue
                start, end = meta["windows"]["full"]
                found[run_id] = Run(run_id, meta["variant"], meta.get("variant_family", ""),
                                    meta.get("point") or meta.get("run_label") or "?",
                                    int(start), int(end))
    late = [r.run_id for r in found.values() if r.offset <= 0]
    if late:
        raise SystemExit(
            f"runs.py: {len(late)} run(s) start at or after the anchor {ANCHOR_ISO}, which "
            f"would need a negative offset (Prometheus rejects those without "
            f"--enable-feature=promql-negative-offset). Move ANCHOR_EPOCH past them: "
            + ", ".join(sorted(late)))
    return sorted(found.values(),
                  key=lambda r: (POINT_ORDER.index(r.point) if r.point in POINT_ORDER
                                 else len(POINT_ORDER), r.point, r.variant, r.start))


def _read_json(path):
    import json
    with open(path) as fh:
        return json.load(fh)


def _run_var(found):
    options = [{"selected": i == 0, "text": r.label, "value": f"{r.offset}s"}
               for i, r in enumerate(found)]
    current = options[0] if options else {}
    return {
        "name": "run", "label": "Run", "type": "custom",
        # Grafana rebuilds `options` from `query` whenever the variable is edited in the UI,
        # so the two have to say the same thing or a stray click reorders the dropdown.
        "query": ", ".join(f"{r.label} : {r.offset}s" for r in found),
        "options": options,
        "current": {"selected": True, "text": current.get("text", ""),
                    "value": current.get("value", "")},
        "multi": False, "includeAll": False, "hide": 0, "refresh": 0, "sort": 0,
        "description": "Which archived run every panel shows. The value is the run's distance "
                       "from the dashboard's fixed anchor, applied as a PromQL offset.",
    }


def _end_var():
    """Hidden, chained off `$run`: the selected run's end, in anchor time.

    `$run`'s VALUE is the run's offset, and scripts/run_markers.py stamps that same offset onto
    the marker series as a label — so this resolves without a second dropdown for the user to
    keep in sync. Grafana re-resolves a chained variable whenever its dependency changes.

    If the markers were never backfilled this comes back empty and every panel's query ends in
    `vector()`, a parse error. That is deliberate: the alternative failure is showing the next
    run's data as if it were this one, which is the bug this exists to fix.
    """
    query = 'label_values(bench_run_marker{offset="$run"}, end_at)'
    return {"name": "end", "label": "Run end (derived)", "type": "query",
            "datasource": build.DS_REPLAY,
            "query": {"query": query, "refId": "end-variable"}, "definition": query,
            "regex": "", "refresh": 1, "sort": 0, "includeAll": False, "multi": False,
            "hide": 2, "current": {}, "options": []}


def _const_var(name, label, value):
    # A constant, not a query variable: query variables resolve against the dashboard's time
    # range, and this dashboard's range is the anchor window -- which contains no data at all,
    # so label_values() would return nothing and $job would expand to "" on every panel.
    # The unified stack emits exactly one value for each of these on both variant families
    # (verified against the archived TO and ES runs), so there is nothing to choose anyway.
    return {"name": name, "label": label, "type": "constant", "query": value,
            "current": {"selected": False, "text": value, "value": value},
            "options": [{"selected": True, "text": value, "value": value}], "hide": 2}


def _header_panel(panel_id, span_minutes):
    return {
        "id": panel_id, "type": "text", "title": "Selected run",
        "gridPos": {"x": 0, "y": 0, "w": 24, "h": 3},
        "datasource": build.DS_REPLAY,
        "options": {"mode": "markdown", "content":
                    "## ${run:text}\n\n"
                    f"The axis below is **minutes since this run's own t0** "
                    f"(`meta.json` `windows.full[0]`, i.e. the start of measured load). The axis is "
                    f"{span_minutes} minutes wide — sized for the longest archived run — but every "
                    "panel stops at **this** run's end, so a shorter run simply leaves the right "
                    "side empty rather than continuing into whichever run came next.\n\n"
                    "The time picker is pinned to a fixed anchor and must stay there: the run is "
                    "reached by the offset carried in the **Run** variable, not by the time range."},
    }


def build_runs(found):
    span = max([r.seconds for r in found], default=3600)
    span_minutes = int((span + 599) // 600 * 10)          # round up to a whole 10 minutes
    end_iso = (datetime.datetime.fromtimestamp(ANCHOR_EPOCH, datetime.timezone.utc)
               + datetime.timedelta(minutes=span_minutes)).strftime("%Y-%m-%dT%H:%M:%S.000Z")

    dash = build._base(
        "bench-runs", "Bench Runs (archived, pick a run)",
        "Generated by scripts/dashboards/build.py --runs — do not edit by hand. Full-fidelity "
        "view of one archived run at a time: the real scraped TSDB that scripts/prom_archive.sh "
        "merged into the bench-replay-data archive volume. Pick a run from the "
        "'Run' dropdown; every panel re-queries that run's own window through a PromQL offset, "
        "so all runs share one axis starting at their t0, and stops at that run's own end. Leave "
        "the time range alone. Requires the marker series from scripts/run_markers.py.",
        ANCHOR_ISO, end_iso,
        [_run_var(found), _end_var(),
         _const_var("job", "API job", "inventory"),
         _const_var("db", "Database", "inventory"),
         _const_var("dbc", "DB container", "postgres"),
         # An alternation, unlike the live dashboard's plain `api`: this dashboard queries
         # ARCHIVED TSDBs, and runs recorded before the api gained a container_name carry
         # the old Compose-generated `<project>-api-N` name instead.
         _const_var("apic", "API container", "api|.*-api-[0-9]+")])

    def pick(panel):
        if not panel.targets:
            return None
        return [spec.Target(t.legend, clip_expr(offset_expr(t.expr))) for t in panel.targets]

    panels, next_id, _, _ = build._layout(spec.SECTIONS, pick, build.DS_REPLAY)
    header = _header_panel(next_id, span_minutes)
    for panel in panels:
        panel["gridPos"]["y"] += header["gridPos"]["h"]
    dash["panels"] = [header] + panels
    return dash
