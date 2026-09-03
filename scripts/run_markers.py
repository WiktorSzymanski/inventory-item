#!/usr/bin/env python3
"""Backfill the marker series that lets bench-runs clip each run at its own end.

WHY THIS EXISTS. bench-runs pins its time range to a fixed anchor window sized for the LONGEST
run and reaches each run through a PromQL offset (see scripts/dashboards/runs.py). Everything
past the selected run's own end therefore shows whatever ran next — the campaign runs
back-to-back, so a 60-minute run in a 76-minute window draws ~13 minutes of the following run's
warm-up as if it belonged to it, and a 10-minute run draws three runs in a row. That is not a
cosmetic problem: it looks exactly like the runs having been merged together.

Clipping needs each run's end expressed in anchor space, and a Grafana variable cannot compute
it — a PromQL offset must be a literal duration, so the dropdown's value is already spoken for.
This script publishes the missing number as data:

    bench_run_marker{run, run_id, variant, point, offset, end_at} 1

one series per run, sampled across the anchor window so it always resolves whatever slice of
that window is on screen. The dashboard then chains a hidden `$end` variable off the selected
run's offset —

    label_values(bench_run_marker{offset="$run"}, end_at)

— and every panel's expression is wrapped as

    (<expr with offsets>) and on() (vector(time()) < vector($end))

`and on()` keeps the left-hand series untouched (labels, legends and all) while the right-hand
side is a bare true/false gate on the evaluation instant, so a panel simply stops at the run's
end instead of continuing into the next run.

Run it after archiving snapshots and before (or after) rebuilding the dashboard:

    ./scripts/prom_archive.sh bench-results/<run_id>/prom-snapshot
    python3 scripts/run_markers.py bench-results-2/bench-results/breakpoint bench-results
    python3 -m scripts.dashboards.build --runs bench-results-2/bench-results/breakpoint bench-results

Re-running is safe: it writes fresh blocks holding the same values, and Prometheus merges
overlapping blocks. Without these markers `$end` resolves to nothing and every panel's query
becomes `vector()`, which is a parse error — loud, and deliberately so, rather than silently
falling back to showing the next run again.
"""
import argparse
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from scripts.dashboards import runs as runs_mod  # noqa: E402

PROM_IMAGE = "prom/prometheus:v2.52.0"
REPLAY_CONTAINER = "prometheus-replay"
REPLAY_VOLUME = "bench-replay-mongo"
METRIC = "bench_run_marker"

# One sample every 5 minutes, padded on both sides of the anchor window. Grafana resolves
# label_values() over whatever range is on screen, so the series has to cover the whole window
# (and a little beyond, for a nudged time picker) or the variable comes back empty for some
# ranges and not others — the most confusing failure available.
STEP_SECONDS = 300
PAD_SECONDS = 1800


def log(msg):
    print(f"[markers] {msg}", file=sys.stderr)


def escape(value):
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def build_openmetrics(found, span_seconds):
    """OpenMetrics text carrying one marker series per run."""
    start = runs_mod.ANCHOR_EPOCH - PAD_SECONDS
    end = runs_mod.ANCHOR_EPOCH + span_seconds + PAD_SECONDS
    lines = [f"# HELP {METRIC} Per-run constants for the bench-runs dashboard: the run's PromQL "
             f"offset from the anchor, and the end of its own window in anchor time.",
             f"# TYPE {METRIC} gauge"]
    samples = 0
    for run in found:
        labels = {
            "run": run.label,
            "run_id": run.run_id,
            "variant": run.variant,
            "point": run.point,
            "offset": f"{run.offset}s",
            # Anchor space, not wall clock: the dashboard compares it against time(), which
            # evaluates inside the anchor window.
            "end_at": str(runs_mod.ANCHOR_EPOCH + run.seconds),
        }
        rendered = ",".join(f'{k}="{escape(v)}"' for k, v in labels.items())
        # Samples of one series must be emitted in time order for promtool to accept them. The
        # bound is `end + STEP` so the last sample lands at or past `end` rather than up to one
        # step short of it — that gap would be at the right edge of the window, exactly where a
        # panel is most likely to be read.
        for stamp in range(start, end + STEP_SECONDS, STEP_SECONDS):
            lines.append(f"{METRIC}{{{rendered}}} 1 {stamp}")
            samples += 1
    lines.append("# EOF")
    return "\n".join(lines) + "\n", samples


def docker(*args, check=True):
    proc = subprocess.run(["docker", *args], capture_output=True, text=True)
    if check and proc.returncode != 0:
        sys.exit(f"docker {' '.join(args[:2])} failed: "
                 f"{proc.stderr.strip() or proc.stdout.strip()}")
    return proc


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("roots", nargs="+", metavar="DIR",
                    help="the same run directories passed to build.py --runs")
    ap.add_argument("--root-label", nargs="+", metavar="NAME", default=[],
                    help="the same --root-label passed to build.py: the marker series carries "
                         "each run's dropdown label, so the two must agree")
    ap.add_argument("--volume", default=REPLAY_VOLUME, help=f"default: {REPLAY_VOLUME}")
    ap.add_argument("--dry-run", action="store_true", help="print the OpenMetrics text and stop")
    args = ap.parse_args()

    found = runs_mod.scan_runs(args.roots, args.root_label)
    if not found:
        sys.exit(f"no completed runs found under: {' '.join(args.roots)}")
    span = max(run.seconds for run in found)
    text, samples = build_openmetrics(found, span)
    log(f"{len(found)} runs, {samples} samples")

    if args.dry_run:
        # write(), not print(): `text` already ends in a newline after "# EOF", and the extra
        # one print() appends is not cosmetic -- promtool rejects the file outright with
        # "unexpected data after # EOF". Byte-for-byte what the backfill path below writes,
        # so `--dry-run > markers.om` is a usable substitute for it (docker-compose.replay-load.yml
        # relies on exactly that).
        sys.stdout.write(text)
        return

    with tempfile.TemporaryDirectory(prefix="markers-") as staging:
        om_path = os.path.join(staging, "markers.om")
        with open(om_path, "w") as fh:
            fh.write(text)
        os.chmod(staging, 0o755)
        os.chmod(om_path, 0o644)

        # Same rule as prom_archive.sh: backfilled blocks must not be written
        # underneath a live head block, so the container is stopped for the copy and restarted
        # afterwards — but only if this script was the one that stopped it.
        running = docker("ps", "-q", "-f", f"name=^{REPLAY_CONTAINER}$", check=False).stdout.strip()
        if running:
            log(f"stopping {REPLAY_CONTAINER}")
            docker("stop", REPLAY_CONTAINER)
        try:
            proc = docker("run", "--rm",
                          "-v", f"{args.volume}:/prometheus",
                          "-v", f"{om_path}:/in.om:ro",
                          "--entrypoint", "/bin/promtool", PROM_IMAGE,
                          "tsdb", "create-blocks-from", "openmetrics", "/in.om", "/prometheus",
                          check=False)
            if proc.returncode != 0:
                sys.exit(f"promtool failed:\n{proc.stdout}\n{proc.stderr}")
            # promtool writes as root; prom/prometheus runs as nobody and must be able to write
            # into /prometheus (it mmaps queries.active at startup) or it panics on boot.
            docker("run", "--rm", "-v", f"{args.volume}:/prometheus", "alpine",
                   "chown", "-R", "65534:65534", "/prometheus")
            log(proc.stdout.strip().splitlines()[-1] if proc.stdout.strip() else "blocks written")
        finally:
            if running:
                log(f"starting {REPLAY_CONTAINER}")
                docker("start", REPLAY_CONTAINER)

    log(f"markers cover ANCHOR-{PAD_SECONDS}s .. ANCHOR+{span + PAD_SECONDS}s")


if __name__ == "__main__":
    main()
