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
    TO-1 run  ->  offset 12218040s  ->  reaches 2026-08-12T14:06Z
    ES-4 run  ->  offset 12129180s  ->  reaches 2026-08-13T14:47Z

The anchor sits AFTER the whole campaign, because reaching forward in time needs a negative offset and Prometheus rejects those unless started
with --enable-feature=promql-negative-offset. scan_runs() refuses to build a dashboard for a
run recorded after the anchor rather than emit a query that 400s.

The panel at the top is the one this dashboard adds to the live set: publish lag with every
event type pooled into a single histogram (see POOLED below), which the per-type curves further
down cannot be read as -- a quantile of a union is not a function of its parts' quantiles.

This is the full-fidelity view -- real scraped TSDB, every live panel, including the
pg_stat_*/WAL/HikariCP/outbox ones. What it deliberately cannot do is overlay two runs as
separate lines in one panel: a single query carries a single offset. Comparing two runs means
flipping the dropdown between them, which works because they share an axis -- the panels sit
in the same place and redraw in place. For a side-by-side of the numbers rather than the
curves, use the per-run summary in bench-results/<run_id>/ instead.
"""
import datetime
import os
from dataclasses import dataclass

from . import build, spec

# 2027-01-01T00:00:00Z -- after every run of the campaign, so all offsets are positive.
#
# It has to sit after the LAST run that will ever be loaded, and moving it costs nothing: the
# archive is never re-ingested, and the offsets, the marker series and the dashboard's time
# range are all derived from this constant and regenerated together on every load. It was
# 2026-09-01 until the campaign reached that date and scan_runs() started refusing the whole
# set (the "steady" runs of 2026-09-01 were the first past it); the months of headroom here are
# so that does not happen again mid-campaign. Anything already archived keeps working -- only
# a bench-runs.json generated under the OLD anchor goes stale, and rebuilding it is one load.
ANCHOR_EPOCH = 1798761600
ANCHOR_ISO = "2027-01-01T00:00:00.000Z"

# The scrape job every run used before monitoring/prometheus/prometheus.yml grew a second one,
# and the fallback for any meta.json written before bench.sh recorded `prom_job`.
DEFAULT_PROM_JOB = "inventory"


# What joins the components of a run's dropdown label -- the selected directory, every
# subdirectory between it and the run, and the run's own directory name. Anything but `,` and
# `:` would do (see Run.label); a spaced dash reads as a path without looking like one, so it
# is not mistaken for something that can be pasted into a shell.
RUN_PATH_SEPARATOR = " - "

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


# ---------------------------------------------------------------- publish lag, pooled
# Publish lag over EVERY event type at once, which the by-eventType panel further down cannot
# be read as. Quantiles do not aggregate: three per-type p95 curves say nothing about the p95
# of the population they partition, and the highest of them is not it either. The pooling has
# to happen inside the query, by summing every type's buckets before histogram_quantile --
# which is exactly what dropping `eventType` from the `by` clause does.
#
# k6/bench/dump.py records publish_lag_p50/95/99 into dump.json split by eventType and only so,
# so this is the one place the pooled figure is drawn at all.
#
# It is still histogram_quantile(rate(...[1m])) -- a quantile OF THAT MINUTE, like every other
# latency panel here. Neither its peak nor its eyeballed average is the run's percentile; that
# number is the quantile over the bucket-vector difference between the run's two endpoints, and
# no panel shows it.
#
# Nothing in the expression is replay-specific: no $run, no $end, no `@`. It sits in this module
# rather than in spec.py because the archive browser is where a finished run's publish lag gets
# read; moving the panel into spec.py would give the live dashboard the same curve. pick()
# offsets and clips it like every other curve, so it stops at the selected run's end.
POOLED_QUANTILES = ((0.50, "p50"), (0.95, "p95"), (0.99, "p99"))

POOLED = spec.Section("Publish lag, all event types", [
    spec.Panel(
        title="Publish lag, all event types — p50 / p95 / p99",
        unit="s", w=24,
        description="Every publish_lag sample of the selected run, all event types pooled into "
                    "one histogram before the quantile is taken — not the per-type curves "
                    "further down, and not their maximum: a quantile of a union is not a "
                    "function of the quantiles of its parts. Per-minute, like every latency "
                    "panel on this dashboard, so it is a shape rather than a single figure for "
                    "the run; dump.json carries the run-wide numbers, split by eventType.",
        targets=[spec.Target(name, spec._q(q, "publish_lag_seconds_bucket", "le"))
                 for q, name in POOLED_QUANTILES]),
])


# ---------------------------------------------------------------- orders completed, running total
# How many orders the run actually finished, split by outcome. Every other orders panel on this
# dashboard is a RATE -- orders/s at each instant -- and a rate curve does not answer "how many",
# which is the first thing asked of a finished run. This is the same counter, undifferentiated:
# the curve shows where in the run the work accrued (a flat stretch is a stall), and the legend
# table's `Last *` column is the run's total for that outcome.
#
# THE RAW COUNTER, DELIBERATELY -- not `counter - counter @ start()`, which is the obvious way to
# strip the warm-up out of the baseline and is wrong here. Prometheus carries a series' last
# sample forward for 5 minutes, and run-suite.sh restarts the stack back-to-back, so a series
# that has no sample yet in THIS run resolves to the PREVIOUS run's final value for the first
# few minutes past t0. In the 12-run phase-1 archive that hits `rejected` on 2 of the 6 TO runs
# (TO-3 0828-1652 opens at 238 140, TO-2-fix-A 0828-2206 at 43 400) because no order had been
# rejected yet when measured load started. Subtracting that baseline makes the whole series
# negative for the rest of the run -- and on a `min: 0` axis a negative series is simply not
# drawn, so 101 767 rejections read as "no rejections" with nothing on screen to say otherwise.
# The raw counter degrades the other way: the carry-over shows as a plateau-then-cliff in the
# first minutes, which is visible and self-explanatory, and every point after the reset -- the
# `Last *` total included -- is this run's own count.
#
# Two things the totals are therefore NOT:
#   - warm-up is included. k6/bench/bench.sh's paced warm-up lands ~5 000 confirmed orders before
#     t0, so `confirmed` starts at ~5 001 rather than 0. Against the 200 k - 1.7 M totals of a
#     full run that is under 3%, and it is a constant, not a variant difference.
#   - `rejected` on a carried-over run starts at the previous run's value. See above.
#
# TO family only: orders.completed is registered in InventoryService, which the ES branches do
# not have (their terminal outcomes arrive through the projection). ES runs render this empty,
# like every other TO-family panel here. The cross-family count is order_e2e_time_seconds_count,
# which the "Offered vs accepted vs terminal" panel already draws as a rate.
#
# Replay-only, like POOLED: on the live dashboard the same expression would draw the counter
# since JVM start, which is a different quantity and not one anyone watches climb.
TOTALS = spec.Section("Orders completed, running total", [
    spec.Panel(
        title="Orders completed by outcome — running total",
        unit="short", w=24,
        description="Cumulative count of terminal orders, by outcome, over the selected run. "
                    "The run's total per outcome is the legend table's `Last *` column. TO family "
                    "only. Two caveats, both by design: the ~5 000 warm-up orders completed "
                    "before t0 are included in `confirmed`, and on a run whose first rejection "
                    "came minutes after t0 the `rejected` curve opens on the previous run's "
                    "final value — Prometheus' 5-minute lookback — until this run's own series "
                    "appears and the counter resets. Every point after that cliff, the total "
                    "included, is this run's own.",
        targets=[spec.Target("{{outcome}}",
                             'sum by (outcome) (orders_completed_total{job=~"$job"})')]),
])


@dataclass
class Run:
    run_id: str
    variant: str
    family: str
    point: str
    start: int
    end: int
    # Which Prometheus scrape job carried this run's application metrics. Variants that give
    # actuator its own connector (management.server.port) are scraped as `inventory-mgmt`
    # instead of `inventory`; bench.sh records the choice in meta.json as `prom_job`. Defaulted,
    # because runs archived before that field existed have no `prom_job` and were all scraped
    # under `inventory`. See DEFAULT_PROM_JOB and _job_constant().
    prom_job: str = "inventory"
    # Where the run was found, as label components: the selected directory's name, then every
    # directory between it and the run, then the run's own directory. This is the dropdown
    # text (see label) -- scan_runs() fills it for every run it finds; a Run built by hand
    # without one falls back to the descriptive label.
    path: tuple = ()

    @property
    def offset(self):
        return ANCHOR_EPOCH - self.start

    @property
    def seconds(self):
        return self.end - self.start

    @property
    def label(self):
        """Dropdown text: the run's path under the selected directory, `dirA - dirB - run`.

        A campaign tree's directory names are how the runs were grouped in the first place --
        `2-ES-snapshot` vs `3-Cache` is the comparison being made -- and the same run_id can
        sit in two of them, so the path is the only thing that tells the copies apart on
        screen. The run's own directory name carries the variant, point and timestamp already
        (`ES-2_capacity_W-base_20260831T233802Z`), which is why nothing else is appended.

        Grafana splits a custom variable's option list on `,` and `:`, so a directory named
        with either would silently become two broken options; both are replaced here rather
        than trusted to stay out of directory names.
        """
        if self.path:
            return RUN_PATH_SEPARATOR.join(_label_part(part) for part in self.path)
        when = datetime.datetime.fromtimestamp(self.start, datetime.timezone.utc)
        return f"{self.variant} · {self.point} · {when:%m%d-%H%M}"


def _label_part(name):
    """One path component, safe to put in a Grafana custom variable's option list.

    Grafana parses that list as `label : value, label : value`, so a `:` or `,` anywhere in a
    label silently splits it into a different -- and broken -- option. Directory names are the
    user's, not this script's, so neither character is assumed absent.
    """
    return name.replace(":", "-").replace(",", "-").strip() or "?"


def root_label(root):
    """The display name for a scanned directory: its own basename.

    This is the first component of every label under it, so `RUNS_DIR=~/Desktop/Final-Bench`
    gives `Final-Bench - 2-ES-snapshot - ES-1_capacity_...`. It is derived rather than passed
    because on the command line the name is right there in the argument -- but see
    scan_runs()'s `root_labels`, which docker-compose.replay-load.yml needs: the selected
    directory is bind-mounted at /runs there, and `runs` is not what the user called it.
    """
    return os.path.basename(os.path.abspath(root)) or os.path.abspath(root)


def scan_runs(roots, root_labels=()):
    """Every completed run under `roots`, ordered as the dropdown should read.

    A run qualifies when it has both a meta.json (it finished) and a prom-snapshot/ (its TSDB
    was captured, so scripts/prom_archive.sh had something to merge into bench-replay-mongo).
    Runs missing either are skipped: an aborted run has nothing to show, and a run whose TSDB
    was never captured would fill every panel with "No data" and no indication that the
    dashboard is fine and the data simply is not there.

    Roots are walked to any depth, so a flat `bench-results/`, a campaign directory of
    per-phase subdirectories, and a directory of those all work as one argument. Each run
    carries the path it was found at (see Run.label), whose first component is the root's own
    name -- `root_labels` overrides that per root, positionally, for callers whose root is a
    mount point rather than the directory the user named.

    os.walk does NOT follow symlinks, which matters here: when MAIN_ROOT is not the repo root,
    ensure_results_link() leaves a self-referential `bench-results/bench-results` symlink, and
    a recursive glob walks through it forever. Directories are visited in sorted order so the
    dropdown is reproducible, and the run_id keying still drops a run reached twice through
    overlapping roots.
    """
    found = {}
    for i, root in enumerate(roots):
        label = root_labels[i] if i < len(root_labels) and root_labels[i] else root_label(root)
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames.sort()
            # Pruned unconditionally: prom-snapshot/ holds one directory per TSDB block, and
            # every block carries a meta.json of its OWN -- a Prometheus block descriptor, not
            # a run's. They are excluded by the prom-snapshot/ test below anyway, but there is
            # nothing under there worth walking into, and there are thousands of files.
            has_snapshot = "prom-snapshot" in dirnames
            if has_snapshot:
                dirnames.remove("prom-snapshot")
            if "meta.json" not in filenames:
                continue
            dirnames[:] = []                    # nothing nests inside a run directory
            if not has_snapshot:
                continue
            meta = _read_json(os.path.join(dirpath, "meta.json"))
            run_id = meta.get("run_id") or os.path.basename(dirpath)
            if run_id in found:
                continue
            rel = os.path.relpath(dirpath, root)
            path = (label,) if rel == os.curdir else (label, *rel.split(os.sep))
            start, end = meta["windows"]["full"]
            found[run_id] = Run(run_id, meta["variant"], meta.get("variant_family", ""),
                                meta.get("point") or meta.get("run_label") or "?",
                                int(start), int(end),
                                meta.get("prom_job") or DEFAULT_PROM_JOB,
                                path)
    late = [r.run_id for r in found.values() if r.offset <= 0]
    if late:
        raise SystemExit(
            f"runs.py: {len(late)} run(s) start at or after the anchor {ANCHOR_ISO}, which "
            f"would need a negative offset (Prometheus rejects those without "
            f"--enable-feature=promql-negative-offset). Move ANCHOR_EPOCH past them: "
            + ", ".join(sorted(late)))
    # By path, which is what the dropdown now READS: sorting by anything else would leave the
    # options in an order the labels do not explain, and a directory's runs scattered through
    # the list is exactly what naming them after their directory is meant to fix.
    return sorted(found.values(), key=lambda r: r.path)


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


def _job_constant(found):
    """$job's value: every scrape job the run set actually used, as a regex alternation.

    One dashboard has to serve an archive that is not uniform. monitoring/prometheus/prometheus.yml
    declares two application jobs -- `inventory` (actuator on the request connector) and
    `inventory-mgmt` (actuator on its own `management.server.port`) -- and which one a run landed
    under is a property of the VARIANT, recorded per run in meta.json as `prom_job`. In the phase-1
    archive the two TO-2-fix-A runs are `inventory-mgmt` and the other ten are `inventory`.

    $job cannot be a query variable here (they resolve against the anchor window, which holds no
    data) and a constant holds one string, so the string is the alternation of every prom_job in
    the set and spec.py matches it with `job=~` rather than `job=`. Building it from the run set
    rather than hard-coding it means a third job name arrives with the run that uses it.

    THIS IS THE BUG IT FIXES. With a flat `inventory` constant, selecting either TO-2-fix-A run
    left 90 of 91 $job-filtered targets returning nothing -- the whole dashboard blank, which
    reads as a missing TSDB snapshot rather than a one-word label mismatch. Those two runs are the
    A/B evidence for the watermark cursor, so they are exactly the ones worth opening.

    Over-matching would need two API jobs carrying the same metric at the same instant, i.e. two
    API containers scraped at once; the stack runs one, and the non-selected job's target is down
    (`up == 0`, no application series at all). Verified across all 12 phase-1 runs: no instant of
    any run has both jobs present.
    """
    return "|".join(sorted({run.prom_job for run in found})) or DEFAULT_PROM_JOB


def _header_panel(panel_id, span_minutes):
    return {
        "id": panel_id, "type": "text", "title": "Selected run",
        "gridPos": {"x": 0, "y": 0, "w": 24, "h": 3},
        "datasource": build.DS_REPLAY,
        "options": {"mode": "markdown", "content":
                    "## ${run:text}\n\n"
                    f"The axis on every panel is **minutes since this run's own t0** "
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
        "merged into the bench-replay-mongo archive volume. Pick a run from the "
        "'Run' dropdown; every panel re-queries that run's own window through a PromQL offset, "
        "so all runs share one axis starting at their t0, and stops at that run's own end. Leave "
        "the time range alone. Requires the marker series from scripts/run_markers.py.",
        ANCHOR_ISO, end_iso,
        [_run_var(found), _end_var(),
         build._const_var("job", "API job", _job_constant(found)),
         build._const_var("db", "Database", "inventory"),
         build._const_var("dbc", "DB container", "mongo"),
         # An alternation, unlike the live dashboard's plain `api`: this dashboard queries
         # ARCHIVED TSDBs, and runs recorded before the api gained a container_name carry
         # the old Compose-generated `<project>-api-N` name instead.
         build._const_var("apic", "API container", "api|.*-api-[0-9]+")])

    def pick(panel):
        if not panel.targets:
            return None
        return [spec.Target(t.legend, clip_expr(offset_expr(t.expr))) for t in panel.targets]

    panels, next_id, _, _ = build._layout([POOLED, TOTALS] + spec.SECTIONS, pick, build.DS_REPLAY)
    header = _header_panel(next_id, span_minutes)
    for panel in panels:
        panel["gridPos"]["y"] += header["gridPos"]["h"]
    dash["panels"] = [header] + panels
    return dash
