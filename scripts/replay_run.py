#!/usr/bin/env python3
"""Backfill an archived run's dump.json into Prometheus so it can be viewed in Grafana.

The benchmark harness keeps no TSDB: bench.sh extracts pre-computed range series, per-step
scalars, and run-level summary numbers into bench-results/<run_id>/dump.json and renders
report.pdf, and the raw Prometheus data dies with the compose volume. This script rebuilds a
*viewable* (not identical) copy of a run from that dump: three generic metric families carry the
original dump.json key in a label (`replay_series`, `replay_step`, `replay_summary`), so the
Grafana dashboard queries the same metric names across every archived run regardless of which
keys that run happened to record.

Each family also carries an `axis` label. `axis="wall"` keeps samples at their ORIGINAL
timestamps, so the replay lands in Grafana at the wall-clock time the run actually happened.
`axis="elapsed"` re-anchors every run's first sample to the same fixed epoch (ANCHOR_EPOCH), so
several runs can be overlaid on one chart aligned by time-since-start rather than wall-clock time.

The stock dashboard cannot show this data — its panels compute rate() over raw counters that no
longer exist. The companion dashboard (uid: bench-replay) reads the replay_* gauges directly.

Mechanics: emit OpenMetrics text, let `promtool tsdb create-blocks-from openmetrics` turn it into
TSDB blocks, and drop those blocks into the Prometheus data volume while Prometheus is stopped.
Backfilled blocks must be OLDER than the running head block, which is why Prometheus is stopped
for the copy and restarted afterwards.

Python 3 standard library only, matching the rest of the harness.

Usage:
    python3 scripts/replay_run.py bench-results/ES-4_capacity_20260805T154022Z
    python3 scripts/replay_run.py bench-results/ES-4_* bench-results/TO-3_capacity_2026*
    python3 scripts/replay_run.py --dry-run <run-dir>          # write the .om file, stop there
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROM_IMAGE = "prom/prometheus:v2.52.0"

ANCHOR_EPOCH = 1767225600  # 2026-01-01T00:00:00Z — common origin for the elapsed axis

FAMILIES = ("replay_series", "replay_step", "replay_summary")
HELP = {
    "replay_series": "5s range series from dump.json series{} (metric=<key>)",
    "replay_step":   "per-capacity-step scalar from dump.json per_step[] (metric=<key>, dim=<label>)",
    "replay_summary": "run-level scalar/derived from dump.json (key=<name>, dim=<label>)",
}


def log(msg):
    print(f"[replay] {msg}", file=sys.stderr)


def die(msg):
    print(f"[replay] FATAL: {msg}", file=sys.stderr)
    raise SystemExit(1)


def escape(value):
    return str(value).replace("\\", "\\\\").replace('"', '\\"')


def target_rate_series(meta, window):
    """Staircase of the capacity scenario's target rate, from meta.json steps.

    Step offsets are relative to the start of the load window, which is where the range series
    start too. Scenarios without steps (steady, spike) simply contribute nothing.
    """
    steps = meta.get("steps") or []
    if not steps:
        return []
    t0 = window[0]
    out = []
    for step in steps:
        rate = float(step["targetRate"])
        for t in range(t0 + int(step["startsAt"]), t0 + int(step["endsAt"]), 5):
            out.append([t, rate])
    return out


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


def prometheus_volume():
    """Name of the docker volume mounted at /prometheus, from the container if it exists."""
    proc = subprocess.run(
        ["docker", "inspect", "prometheus", "--format",
         '{{range .Mounts}}{{if eq .Destination "/prometheus"}}{{.Name}}{{end}}{{end}}'],
        capture_output=True, text=True,
    )
    name = proc.stdout.strip()
    if name:
        return name
    # Container gone: fall back to compose's default project name (sanitised directory name).
    project = re.sub(r"[^a-z0-9_-]", "", os.path.basename(REPO_ROOT).lower())
    return f"{project}_prometheus-data"


def docker(*args, check=True):
    proc = subprocess.run(["docker", *args], capture_output=True, text=True)
    if check and proc.returncode != 0:
        die(f"docker {' '.join(args[:2])} failed: {proc.stderr.strip() or proc.stdout.strip()}")
    return proc


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dirs", nargs="+", help="bench-results/<run_id> directories to replay")
    ap.add_argument("--volume", help="Prometheus data volume (default: auto-detect)")
    ap.add_argument("--dry-run", action="store_true", help="only write the OpenMetrics file")
    ap.add_argument("--keep-om", metavar="DIR", help="keep the generated OpenMetrics files here")
    args = ap.parse_args()

    staging = args.keep_om or tempfile.mkdtemp(prefix="replay-")
    os.makedirs(staging, exist_ok=True)

    runs = []
    for run_dir in args.run_dirs:
        if not os.path.exists(os.path.join(run_dir, "dump.json")):
            die(f"{run_dir}: no dump.json")
        text, run_id, window, count = build_openmetrics(run_dir)
        om_path = os.path.join(staging, f"{run_id}.om")
        with open(om_path, "w") as fh:
            fh.write(text)
        span = (datetime.fromtimestamp(window[0], timezone.utc), datetime.fromtimestamp(window[1], timezone.utc))
        log(f"{run_id}: {count} samples, {span[0]:%Y-%m-%d %H:%M:%S}Z -> {span[1]:%H:%M:%S}Z -> {om_path}")
        runs.append((run_id, om_path, window))

    if args.dry_run:
        log(f"dry run: OpenMetrics files in {staging}")
        return

    volume = args.volume or prometheus_volume()
    if not docker("volume", "inspect", volume, check=False).returncode == 0:
        die(f"prometheus volume not found: {volume} (start the stack once, or pass --volume)")
    log(f"target volume: {volume}")

    running = docker("ps", "-q", "-f", "name=^prometheus$", check=False).stdout.strip()
    if running:
        log("stopping prometheus (backfilled blocks must not overlap the live head block)")
        docker("stop", "prometheus")

    for run_id, om_path, _ in runs:
        log(f"{run_id}: promtool create-blocks-from openmetrics")
        proc = docker(
            "run", "--rm",
            "-v", f"{volume}:/prometheus",
            "-v", f"{os.path.abspath(om_path)}:/in.om:ro",
            "--entrypoint", "/bin/promtool", PROM_IMAGE,
            "tsdb", "create-blocks-from", "openmetrics", "/in.om", "/prometheus",
            check=False,
        )
        if proc.returncode != 0:
            die(f"{run_id}: promtool failed:\n{proc.stdout}\n{proc.stderr}")
        blocks = [ln for ln in proc.stdout.splitlines() if ln.strip()]
        log(f"{run_id}: {blocks[-1] if blocks else 'blocks written'}")

    if running:
        log("starting prometheus")
        docker("start", "prometheus")

    print()
    for run_id, _, window in runs:
        frm, to = window[0] * 1000, window[1] * 1000
        print(f"  {run_id}")
        print(f"    http://localhost:3000/d/bench-replay/?from={frm}&to={to}&var-run={run_id}")
    print()


if __name__ == "__main__":
    main()
