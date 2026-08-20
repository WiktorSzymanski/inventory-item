#!/usr/bin/env python3
"""Generate the Grafana dashboards from scripts/dashboards/spec.py.

    python3 -m scripts.dashboards.build
    python3 -m scripts.dashboards.build --runs bench-results

Always writes monitoring/grafana/provisioning/dashboards/{the-dashboard,bench-replay}.json.
With --runs it also writes bench-runs.json, the archived-run browser (see runs.py).
Never edit those files by hand — edit spec.py and re-run this.
"""
import argparse
import json
import os

from . import spec

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(REPO_ROOT, "monitoring", "grafana", "provisioning", "dashboards")
DS = {"type": "prometheus", "uid": "prometheus"}

# The datasource the live dashboard's QUERY VARIABLES resolve against.
#
# It must follow the `ds` picker, not pin to the live Prometheus. Panels all use
# `{"uid": "${ds}"}`, so switching Data source -> "Prometheus Replay" repoints every panel
# at the archive — but a variable pinned to `uid: "prometheus"` keeps asking the LIVE
# Prometheus for its options, and after a benchmark that container is gone
# (`run-suite.sh` ends with `down -v`). label_values() then returns nothing, `current`
# stays {}, `$job` expands empty, and every panel filtering on it renders "No data" while
# the unfiltered ones render normally — which looks like a half-broken archive rather than
# an unresolved variable.
#
# This does not show up during a live run, because the live Prometheus is up at the moment
# report.pdf is rendered. It is specific to viewing an archived run.
DS_VAR = {"type": "prometheus", "uid": "${ds}"}
DS_VAR = {"type": "prometheus", "uid": "${ds}"}
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
        x, row_h = 0, 0
        for panel, targets in chosen:
            if x + panel.w > 24:
                # Advance by the height of the row just completed, not the incoming panel's
                # height -- using the incoming panel's height here caused rows to overlap
                # whenever a wrapped-to panel was shorter than the row it followed.
                x, y = 0, y + row_h
                row_h = 0
            panels.append(_timeseries(panel, targets, datasource, panel_id, x, y))
            panel_id += 1
            x += panel.w
            row_h = max(row_h, panel.h)
        y += row_h
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


def _var(name, label, query, datasource, multi=False, regex=""):
    # `regex` filters the label_values() result before Grafana computes `current` from an empty
    # `{}` (it takes the first option under `sort: 1`, alphabetical). Without a regex that leaves
    # single-valued variables ($job/$db/$dbc/$apic) resolving to whatever sorts first across every
    # scrape target and container (e.g. "cadvisor" before "inventory"), which is empty for every
    # panel that filters on them.
    #
    # A regex that matches NOTHING is just as broken and far quieter: `current` stays {}, `$job`
    # expands to the empty string, and all 58 panels query {job=""} -- so every report.pdf renders
    # blank with no error anywhere. That is exactly what shipped when the stack's names were
    # unified (`inventory-es` -> `inventory`, `postgres-es` -> `postgres`, `api-es-1` ->
    # `<project>-api-1`) and these regexes kept demanding the old family suffixes. Anything
    # changed here must be checked against the values the stack really emits; test_build.py's
    # LiveVariableRegexesSelectRealValues does that.
    #
    # Any capturing group must be non-capturing ((?:...)): Grafana uses the FIRST capturing
    # group's match as the option's value/text when one is present, not the full match.
    return {"name": name, "label": label, "type": "query", "datasource": datasource,
            "query": {"query": query, "refId": f"{name}-variable"}, "definition": query,
            "regex": regex, "refresh": 2, "sort": 1, "includeAll": multi, "multi": multi,
            "current": {}, "options": []}


def _ds_var():
    # Datasource-type variable, not query-type: lets the SAME dashboard JSON be pointed at
    # either the live Prometheus or the replay archive from the dropdown, which is how a
    # snapshotted run (Task 8) actually gets viewed at full fidelity -- switch this to
    # "Prometheus Replay" and set the time range to the run's window. `current` is set
    # explicitly to the live datasource so the default is unambiguous rather than left to
    # Grafana's "first option" behaviour. Archived dashboard (build_archived) deliberately
    # does NOT get this variable -- it must always stay pinned to prometheus-replay.
    return {"name": "ds", "label": "Data source", "type": "datasource",
            "query": "prometheus", "refresh": 1, "hide": 0,
            "current": {"type": "prometheus", "uid": "prometheus", "text": "Prometheus"},
            "options": []}


def build_live():
    dash = _base(
        "the-dashboard", "Inventory — Full Stack",
        "Generated by scripts/dashboards/build.py from scripts/dashboards/spec.py — do not edit by hand. "
        "Merges the former 'Metrics to compare', 'PostgreSQL Metrics' and 'JVM & Spring' dashboards. "
        "Panels for the other variant family render empty by design. Use the 'Data source' dropdown "
        "to view a snapshotted run archived by scripts/prom_archive.sh: switch it to 'Prometheus "
        "Replay' and set the time range to that run's window.",
        "now-15m", "now",
        [_ds_var(),
         # The unified stack emits exactly one value for each of these, so every regex is an
         # exact anchored match on it -- no family suffix, because there are no longer two
         # families' names to tell apart.
         #   job    prometheus.yml scrapes jobs `inventory`, `postgres` and `cadvisor`;
         #          only `inventory` carries the application metrics.
         #   db     pg_database_size_bytes also reports postgres/template0/template1.
         #   dbc    the DB container is named `postgres`. Must NOT catch `postgres-exporter`,
         #          hence the trailing anchor.
         #   apic   the api service is scaled by deploy.replicas and so has no container_name;
         #          cadvisor sees `<project>-api-N`. The project name is a knob
         #          (COMPOSE_PROJECT_NAME, `iir` by default), so match the shape, not `iir`.
         _var("job", "API job", "label_values(up, job)", DS_VAR, regex="/^inventory$/"),
         _var("db", "Database", "label_values(pg_database_size_bytes, datname)", DS_VAR, regex="/^inventory$/"),
         _var("dbc", "DB container", "label_values(container_memory_rss, name)", DS_VAR, regex="/^postgres$/"),
         _var("apic", "API container", "label_values(container_memory_rss, name)", DS_VAR, regex="/^.*-api-[0-9]+$/")])
    dash["refresh"] = "5s"
    panels, _, _, _ = _layout(spec.SECTIONS, lambda p: p.targets, DS_VAR)
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
    tables = _summary_tables(next_id, 0)
    panels[0:0] = tables
    for p in panels[len(tables):]:
        p["gridPos"]["y"] += 8
    panels.append({
        "id": next_id + len(tables), "type": "text", "title": "Not available for archived runs",
        "gridPos": {"x": 0, "y": y + 8, "w": 24, "h": 6},
        "options": {"mode": "markdown", "content":
                    "dump.json does not carry these signals, so no panel can exist for them:\n\n"
                    + "\n".join(f"- {t}" for t in skipped)},
    })
    dash["panels"] = panels
    return dash


def _summary_tables(panel_id, y):
    """Two tables covering replay_summary, split so neither silently collapses distinct series
    into one cell. replay_summary carries `window` (full|load) and `dim` (e.g. outcome, phase) on
    top of `key` -- a single groupingToMatrix keyed only on (run_id, key) used to pick one of
    several same-keyed values with no sign the others existed. Both queries are pinned to
    axis="elapsed" (the archived dashboard's common anchor) and window="full" (the run-end
    figure, not the load-phase-only one) so the only remaining axis is `dim`, which the two tables
    split on explicitly instead of collapsing.
    """
    scalars = {
        "id": panel_id, "title": "Run summary — scalars (window=full)", "type": "table",
        "gridPos": {"x": 0, "y": y, "w": 12, "h": 8}, "datasource": DS_REPLAY,
        "fieldConfig": {"defaults": {"custom": {"align": "right"}}, "overrides": []},
        "options": {"showHeader": True},
        "targets": [{"datasource": DS_REPLAY,
                     "expr": 'last_over_time(replay_summary{run_id=~"$runs",axis="elapsed",'
                             'window="full",dim=""}[$__range])',
                     "format": "table", "instant": True, "refId": "A"}],
        "transformations": [
            {"id": "organize", "options": {"excludeByName": {
                "Time": True, "__name__": True, "axis": True, "window": True, "dim": True}}},
            {"id": "groupingToMatrix",
             "options": {"columnField": "key", "rowField": "run_id", "valueField": "Value"}},
        ],
    }
    # Deliberately NOT a groupingToMatrix on `dim` alone: several keys share the same dim values
    # (e2e_p50/p95/p99 and state_load_p50/p95/p99 are all broken out by "confirmed"/"phase" etc),
    # so a matrix keyed only on dim would reintroduce the exact silent-collapse bug this split
    # exists to fix -- one cell per (run_id, dim) with no sign which key's value survived. This
    # table instead stays one row per (run_id, key, dim): nothing is discarded.
    dimensioned = {
        "id": panel_id + 1,
        "title": "Run summary — dimensioned metrics (by key x dim, window=full)", "type": "table",
        "description": "One row per (run_id, key, dim) -- e.g. e2e_p50 broken out by outcome, "
                       "state_load_p95 by phase. Not matrixed: several keys share dim values, so "
                       "a dim-only matrix would collapse them the same way the old single table did.",
        "gridPos": {"x": 12, "y": y, "w": 12, "h": 8}, "datasource": DS_REPLAY,
        "fieldConfig": {"defaults": {"custom": {"align": "right"}}, "overrides": []},
        "options": {"showHeader": True, "sortBy": [{"displayName": "run_id"}, {"displayName": "key"}]},
        "targets": [{"datasource": DS_REPLAY,
                     "expr": 'last_over_time(replay_summary{run_id=~"$runs",axis="elapsed",'
                             'window="full",dim!=""}[$__range])',
                     "format": "table", "instant": True, "refId": "A"}],
        "transformations": [
            {"id": "organize", "options": {
                "excludeByName": {"Time": True, "__name__": True, "axis": True, "window": True},
                "indexByName": {"run_id": 0, "key": 1, "dim": 2, "Value": 3},
            }},
        ],
    }
    return [scalars, dimensioned]


def _write(name, dashboard):
    path = os.path.join(OUT_DIR, f"{name}.json")
    with open(path, "w") as fh:
        json.dump(dashboard, fh, indent=2, sort_keys=False)
        fh.write("\n")
    print(f"wrote {path} ({len(dashboard['panels'])} panels)")


def main():
    # bench-runs is rebuilt only when --runs names the directories to scan, and is otherwise
    # left alone. Its dropdown is a list of runs baked into the JSON, so a bare `build` that
    # regenerated it from a default location would quietly replace a campaign's worth of
    # options with whatever happened to be in bench-results/ -- and the loss would only show
    # up as a shorter dropdown, which is not something anyone checks.
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", nargs="+", metavar="DIR", default=None,
                        help="directories of completed runs (scanned 1-2 levels deep for "
                             "meta.json); rebuilds bench-runs.json from them")
    args = parser.parse_args()

    _write("the-dashboard", build_live())
    _write("bench-replay", build_archived())
    if args.runs:
        from . import runs as runs_mod
        found = runs_mod.scan_runs(args.runs)
        if not found:
            raise SystemExit(f"no completed runs (meta.json) found under: {' '.join(args.runs)}")
        _write("bench-runs", runs_mod.build_runs(found))
        print(f"  bench-runs: {len(found)} runs, anchored at {runs_mod.ANCHOR_ISO}")


if __name__ == "__main__":
    main()
