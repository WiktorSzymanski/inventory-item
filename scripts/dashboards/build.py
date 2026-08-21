#!/usr/bin/env python3
"""Generate the Grafana dashboards from scripts/dashboards/spec.py.

    python3 -m scripts.dashboards.build
    python3 -m scripts.dashboards.build --runs bench-results

Two dashboards, one job each:

    the-dashboard   live, watches a run happen. Always written.
    bench-runs      the archive, one run at a time. Written only with --runs (see runs.py).

Never edit the JSON by hand — edit spec.py and re-run this.
"""
import argparse
import json
import os

from . import spec

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(REPO_ROOT, "monitoring", "grafana", "provisioning", "dashboards")
DS = {"type": "prometheus", "uid": "prometheus"}

# The archive Prometheus (docker-compose.replay.yml, :9091), holding the TSDB blocks
# scripts/prom_archive.sh merged in. Only bench-runs reads it; the live dashboard is pinned
# to the live Prometheus and has no way to reach it. See runs.py.
DS_REPLAY = {"type": "prometheus", "uid": "prometheus-replay"}


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


def build_live():
    dash = _base(
        "the-dashboard", "Inventory — Full Stack",
        "Generated by scripts/dashboards/build.py from scripts/dashboards/spec.py — do not edit by hand. "
        "Merges the former 'Metrics to compare', 'PostgreSQL Metrics' and 'JVM & Spring' dashboards. "
        "Panels for the other variant family render empty by design. This dashboard only ever shows "
        "the Prometheus scraping the stack right now; for a finished run use the 'Bench Runs' "
        "dashboard, which reads the archive.",
        "now-15m", "now",
        # The unified stack emits exactly one value for each of these, so every regex is an
        # exact anchored match on it -- no family suffix, because there are no longer two
        # families' names to tell apart.
        #   job    prometheus.yml scrapes jobs `inventory`, `postgres` and `cadvisor`;
        #          only `inventory` carries the application metrics.
        #   db     pg_database_size_bytes also reports postgres/template0/template1.
        #   dbc    the DB container is named `postgres`. Must NOT catch `postgres-exporter`,
        #          hence the trailing anchor.
        #   apic   the api service pins `container_name: api`, so cadvisor sees exactly
        #          `api` -- no project prefix and no replica suffix to match around.
        [_var("job", "API job", "label_values(up, job)", DS, regex="/^inventory$/"),
         _var("db", "Database", "label_values(pg_database_size_bytes, datname)", DS, regex="/^inventory$/"),
         _var("dbc", "DB container", "label_values(container_memory_rss, name)", DS, regex="/^postgres$/"),
         _var("apic", "API container", "label_values(container_memory_rss, name)", DS, regex="/^api$/")])
    dash["refresh"] = "5s"
    panels, _, _, _ = _layout(spec.SECTIONS, lambda p: p.targets, DS)
    dash["panels"] = panels
    return dash


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
    if args.runs:
        from . import runs as runs_mod
        found = runs_mod.scan_runs(args.runs)
        if not found:
            raise SystemExit(f"no completed runs (meta.json) found under: {' '.join(args.runs)}")
        _write("bench-runs", runs_mod.build_runs(found))
        print(f"  bench-runs: {len(found)} runs, anchored at {runs_mod.ANCHOR_ISO}")


if __name__ == "__main__":
    main()
