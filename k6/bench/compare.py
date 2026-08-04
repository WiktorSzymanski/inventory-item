#!/usr/bin/env python3
"""Render a set of benchmark runs as one comparison table.

    compare.py bench-results/*_steady_*
    compare.py --cols all -f csv bench-results/ES-3-*
    compare.py --baseline bench-results/TO-1_steady_20260728T101500Z bench-results/*_steady_*
    compare.py --knee bench-results/*_capacity_*

Runs outside Docker, python3 stdlib only.
"""
import argparse
import glob
import json
import os
import sys

# (header, path-into-run, formatter)
#   path is "file.key.subkey"; file is one of meta/dump/verdict/summary.
COLUMNS = {
    "core": [
        ("variant", "meta.variant", "s"),
        ("scenario", "meta.scenario", "s"),
        ("rate", "meta.config.rate", "d"),
        ("items", "meta.config.distinctItems", "d"),
        ("lines", "meta.config.itemsPerOrder", "d"),
        ("payloadB", "meta.config.payloadBytes", "d"),
        ("achieved/s", "dump.derived.achieved_rps", ".1f"),
        ("e2e p50", "dump.scalars.e2e_p50.confirmed", ".3f"),
        ("e2e p95", "dump.scalars.e2e_p95.confirmed", ".3f"),
        ("e2e p99", "dump.scalars.e2e_p99.confirmed", ".3f"),
        ("rej%", "dump.derived.rejected_ratio", "%"),
        ("drain s", "dump.derived.drain_seconds", "d"),
        ("verdict", "verdict.verdict", "s"),
    ],
    "latency": [
        ("variant", "meta.variant", "s"),
        ("scenario", "meta.scenario", "s"),
        ("e2e mean", "dump.derived.e2e_mean_confirmed", ".3f"),
        ("e2e p95", "dump.scalars.e2e_p95.confirmed", ".3f"),
        ("e2e p99", "dump.scalars.e2e_p99.confirmed", ".3f"),
        ("http p50 ms", "dump.scalars.http_order_p50", "ms"),
        ("http p99 ms", "dump.scalars.http_order_p99", "ms"),
        ("proj lag p95", "dump.scalars.projection_lag_p95", ".3f"),
        ("ord lag p95", "dump.scalars.order_proj_lag_p95", ".3f"),
        ("inflight max", "dump.scalars.inflight_max", "d"),
        ("drain rate/s", "dump.derived.drain_service_rate", ".2f"),
    ],
    "resource": [
        ("variant", "meta.variant", "s"),
        ("scenario", "meta.scenario", "s"),
        ("cpu avg", "dump.scalars.cpu_avg", "%"),
        ("cpu max", "dump.scalars.cpu_max", "%"),
        ("heap max MB", "dump.scalars.heap_max_bytes", "MB"),
        ("ctr cpu", "dump.scalars.container_cpu", ".2f"),
        ("ctr rss MB", "dump.scalars.container_rss", "MB"),
        ("db growth MB", "dump.derived.db_growth_bytes", "MB"),
        ("B/order", "dump.derived.db_bytes_per_order", ".0f"),
    ],
    "es": [
        ("variant", "meta.variant", "s"),
        ("scenario", "meta.scenario", "s"),
        ("appends", "dump.scalars.append_success", "d"),
        ("retries", "dump.scalars.opt_retry", "d"),
        ("exhausted", "dump.scalars.opt_exhausted", "d"),
        ("conflict%", "dump.derived.conflict_ratio", "%"),
        ("cache hit%", "dump.derived.cache_hit_ratio", "%"),
        ("catchup", "dump.scalars.catchup", "d"),
        ("load p95 ms", "dump.scalars.state_load_p95.total", "ms"),
        ("persist p95 ms", "dump.scalars.state_persist_p95.db_write", "ms"),
    ],
    # The contention-vs-stock split. `contention` is a lost write race that exhausted its
    # retries; `stock` is a genuine out-of-stock rejection. Both are zero on a healthy
    # single-node run — see the Scaling section of CLAUDE.md. `cmd fail-*` localises which
    # dispatch site gave up; `fail-order` and `ignored` are the two that leave an order
    # non-terminal, so a non-zero value there is a different class of problem from the rest.
    # Absent on TO-*, where the saga does not exist, and rendered as "-" like any other gap.
    "saga": [
        ("variant", "meta.variant", "s"),
        ("scenario", "meta.scenario", "s"),
        ("completed", "dump.scalars.saga_completed.completed", "d"),
        ("contention", "dump.scalars.saga_completed.command_failed", "d"),
        ("stock", "dump.scalars.saga_completed.failed", "d"),
        ("cmd fail-reserve", "dump.scalars.saga_cmd_failed.reserve", "d"),
        ("cmd fail-complete", "dump.scalars.saga_cmd_failed.complete", "d"),
        ("cmd fail-release", "dump.scalars.saga_cmd_failed.release", "d"),
        ("cmd fail-order", "dump.scalars.saga_cmd_failed.fail-order", "d"),
        ("cmd fail-ignored", "dump.scalars.saga_cmd_failed.fail-order-ignored", "d"),
        ("saga p95", "dump.scalars.saga_lifetime_p95.completed", ".3f"),
    ],
    "provenance": [
        ("variant", "meta.variant", "s"),
        ("scenario", "meta.scenario", "s"),
        ("commit", "meta.commit", "sha"),
        ("dirty", "meta.git_dirty", "d"),
        ("k6", "meta.k6_version", "s"),
        ("completion", "dump.derived.completion_ratio", ".5f"),
        ("verdict", "verdict.verdict", "s"),
    ],
}

FAMILY_ORDER = {"TO": 0, "ES": 1}


def dig(run, path):
    part, _, rest = path.partition(".")
    node = run.get(part)
    for key in rest.split("."):
        if not isinstance(node, dict):
            return None
        node = node.get(key)
    return node


def fmt(value, spec):
    if value is None:
        return "-"
    try:
        if spec == "s":
            return str(value)
        if spec == "sha":
            return str(value)[:8]
        if spec == "d":
            return f"{float(value):.0f}"
        if spec == "%":
            return f"{float(value) * 100:.2f}%"
        if spec == "ms":
            return f"{float(value) * 1000:.2f}"
        if spec == "MB":
            return f"{float(value) / 1e6:.1f}"
        return format(float(value), spec)
    except (TypeError, ValueError):
        return str(value)


def load_run(path):
    run = {"_dir": path, "_name": os.path.basename(path.rstrip("/"))}
    for name in ("meta", "dump", "verdict", "summary"):
        fpath = os.path.join(path, f"{name}.json")
        if os.path.exists(fpath):
            try:
                with open(fpath) as fh:
                    run[name] = json.load(fh)
            except json.JSONDecodeError as exc:
                print(f"warn: {fpath}: {exc}", file=sys.stderr)
                run[name] = {}
        else:
            run[name] = {}
    if not run["dump"]:
        print(f"warn: skipping {path} (no dump.json)", file=sys.stderr)
        return None
    return run


def expand(paths):
    out = []
    for path in paths:
        if os.path.isdir(path) and os.path.exists(os.path.join(path, "dump.json")):
            out.append(path)
        elif os.path.isdir(path):
            out.extend(sorted(d for d in glob.glob(os.path.join(path, "*")) if os.path.isdir(d)))
        else:
            out.extend(sorted(glob.glob(path)))
    seen, unique = set(), []
    for path in out:
        real = os.path.realpath(path)
        if real not in seen:
            seen.add(real)
            unique.append(path)
    return unique


def sort_key(run):
    variant = dig(run, "meta.variant") or ""
    family = dig(run, "meta.variant_family") or variant.split("-")[0]
    return (
        FAMILY_ORDER.get(family, 9), variant,
        dig(run, "meta.scenario") or "",
        dig(run, "meta.config.rate") or 0,
        run["_name"],
    )


def render(rows, headers, style):
    if style == "csv" or style == "tsv":
        sep = "," if style == "csv" else "\t"
        lines = [sep.join(headers)]
        lines += [sep.join(str(c) for c in row) for row in rows]
        return "\n".join(lines)

    widths = [max(len(headers[i]), *(len(str(r[i])) for r in rows)) if rows else len(headers[i])
              for i in range(len(headers))]
    out = ["| " + " | ".join(h.ljust(widths[i]) for i, h in enumerate(headers)) + " |",
           "|" + "|".join("-" * (w + 2) for w in widths) + "|"]
    for row in rows:
        out.append("| " + " | ".join(str(c).ljust(widths[i]) for i, c in enumerate(row)) + " |")
    return "\n".join(out)


def build_table(runs, cols, baseline, style):
    spec = COLUMNS[cols] if cols != "all" else _all_columns()
    headers = [h for h, _, _ in spec]
    base = None
    if baseline:
        base = next((r for r in runs if os.path.realpath(r["_dir"]) == os.path.realpath(baseline)), None)
        if base is None:
            print(f"warn: baseline {baseline} not among the runs; no delta columns",
                  file=sys.stderr)

    if base is not None:
        headers = []
        for header, _, style_spec in spec:
            headers.append(header)
            if style_spec not in ("s", "sha"):
                headers.append(f"{header} Δ%")

    rows = []
    for run in runs:
        row = []
        for header, path, style_spec in spec:
            value = dig(run, path)
            row.append(fmt(value, style_spec))
            if base is not None and style_spec not in ("s", "sha"):
                ref = dig(base, path)
                try:
                    if ref in (None, 0) or value is None:
                        row.append("-")
                    else:
                        row.append(f"{(float(value) / float(ref) - 1) * 100:+.1f}%")
                except (TypeError, ValueError, ZeroDivisionError):
                    row.append("-")
        rows.append(row)
    return render(rows, headers, style)


def _all_columns():
    seen, spec = set(), []
    for group in ("core", "latency", "resource", "es", "provenance"):
        for col in COLUMNS[group]:
            if col[0] not in seen:
                seen.add(col[0])
                spec.append(col)
    return spec


def knee_table(runs, style):
    blocks = []
    for run in runs:
        steps = dig(run, "verdict.knee_steps") or []
        if not steps:
            per_step = dig(run, "dump.per_step") or []
            steps = [{"index": s["index"], "target_rate": s["target_rate"],
                      "achieved_rps": (s.get("derived") or {}).get("achieved_rps"),
                      "e2e_p95_confirmed": ((s.get("scalars") or {}).get("e2e_p95") or {}).get("confirmed"),
                      "inflight_start": (s.get("scalars") or {}).get("inflight_start"),
                      "inflight_end": (s.get("scalars") or {}).get("inflight_end"),
                      "sustained": None, "reasons": []} for s in per_step]
        if not steps:
            continue

        knee = dig(run, "verdict.knee") or {}
        headers = ["step", "target/s", "achieved/s", "e2e p95", "inflight", "sustained", "note"]
        rows = []
        for step in steps:
            marker = " <- KNEE" if knee.get("step_index") == step["index"] else ""
            rows.append([
                str(step["index"]),
                fmt(step["target_rate"], "d"),
                fmt(step.get("achieved_rps"), ".1f"),
                fmt(step.get("e2e_p95_confirmed"), ".3f"),
                f"{fmt(step.get('inflight_start'), 'd')}->{fmt(step.get('inflight_end'), 'd')}",
                {True: "yes", False: "no", None: "?"}[step.get("sustained")] + marker,
                "; ".join(step.get("reasons") or []),
            ])
        title = f"### {dig(run, 'meta.variant')} — {run['_name']}"
        if knee.get("rps"):
            title += f"  (knee = {knee['rps']} rps)"
        blocks.append(title + "\n\n" + render(rows, headers, style))
    return "\n\n".join(blocks)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("rundirs", nargs="+")
    ap.add_argument("-f", "--format", dest="style", default="md", choices=["md", "csv", "tsv"])
    ap.add_argument("--cols", default="core",
                    choices=list(COLUMNS.keys()) + ["all"])
    ap.add_argument("--baseline", help="run dir to compute Δ%% columns against")
    ap.add_argument("--knee", action="store_true", help="emit the per-step staircase table")
    args = ap.parse_args()

    runs = [r for r in (load_run(p) for p in expand(args.rundirs)) if r]
    if not runs:
        raise SystemExit("no runs with a dump.json found")
    runs.sort(key=sort_key)

    print(knee_table(runs, args.style) if args.knee
          else build_table(runs, args.cols, args.baseline, args.style))


if __name__ == "__main__":
    main()
