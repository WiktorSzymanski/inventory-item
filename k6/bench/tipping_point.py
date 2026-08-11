#!/usr/bin/env python3
"""Locate the tipping point of a capacity staircase: the offered order rate past
which order processing stops keeping up and stops increasing.

    tipping_point.py bench-results/*_capacity_*
    tipping_point.py --steps bench-results/ES-1_capacity_W-hot_*
    tipping_point.py -f csv bench-results bench-results/capacity-W-base
    tipping_point.py --json tipping.json bench-results

What is compared, per variant family (this is the whole point of the script):

  ES  offered = es_events_processed_total{eventType="OrderCreatedEvent"}
      done    = saga_completed_total, split by outcome
      i.e. every order that entered the saga against every saga that ended.

  TO  offered = POST /inventory/orders 202s
      done    = order_e2e_time_seconds_count, split by outcome
      i.e. every order admitted against every order that reached a terminal state.

Both sides are read from dump.json's per_step block, which is already the trimmed
plateau window of each staircase step, so each row is a steady-state observation
at one offered rate rather than a ramp average.

Four numbers come out of the staircase, and they answer different questions:

  last_good   highest offered rate the system fully serviced (goodput within
              --tolerance of offered). The conservative "it still works here".
  onset       first offered rate where goodput fell behind and stayed behind.
  peak        highest goodput observed at all, and where it happened.
  plateau     lowest offered rate already within --plateau-tol of peak, i.e.
              where throughput stopped increasing. The "no point pushing
              harder" number.

Goodput is credited as min(done, offered) when picking the peak: a step that
drains a backlog left by warmup can retire more orders than it was offered, and
that is not capacity, it is catch-up.

Runs outside Docker, python3 stdlib only.
"""
import argparse
import glob
import json
import os
import sys

# Per family: where the offered side comes from, where the terminal side comes
# from, and which outcome label on the terminal side counts as success. The
# fallbacks fire when a run predates a metric or the family is unrecognised —
# every substitution is recorded in the row's `notes` rather than done silently.
FAMILIES = {
    "ES": {
        "offered": ("events_processed", "OrderCreatedEvent"),
        "outcomes": "saga_completed",
        "success": ("completed",),
        "offered_label": "OrderCreatedEvent",
        "done_label": "saga outcome",
    },
    "TO": {
        "offered": ("orders_accepted", None),
        "outcomes": "e2e_count",
        "success": ("confirmed",),
        "offered_label": "orders accepted",
        "done_label": "order outcome",
    },
}
FALLBACK = {
    "offered": ("orders_accepted", None),
    "outcomes": "e2e_count",
    "success": ("confirmed", "completed"),
    "offered_label": "orders accepted",
    "done_label": "order outcome",
}
FAMILY_ORDER = {"TO": 0, "ES": 1}


# --------------------------------------------------------------------------- io
def load_run(path):
    run = {"_dir": path, "_name": os.path.basename(path.rstrip("/"))}
    for name in ("meta", "dump"):
        fpath = os.path.join(path, f"{name}.json")
        try:
            with open(fpath) as fh:
                run[name] = json.load(fh)
        except FileNotFoundError:
            run[name] = {}
        except json.JSONDecodeError as exc:
            print(f"warn: {fpath}: {exc}", file=sys.stderr)
            run[name] = {}
    if not run["dump"]:
        return None
    return run


def expand(paths):
    """Accept run dirs, trees of run dirs, and globs. bench-results/ nests one
    campaign a level down (capacity-W-base/), so the walk has to be recursive;
    it stops descending as soon as a directory is itself a run."""
    out = []
    for path in paths:
        for cand in ([path] if os.path.isdir(path) else sorted(glob.glob(path))):
            if not os.path.isdir(cand):
                continue
            if os.path.exists(os.path.join(cand, "dump.json")):
                out.append(cand)
                continue
            for root, dirs, files in os.walk(cand):
                if "dump.json" in files:
                    out.append(root)
                    dirs[:] = []          # a run dir has no runs inside it
                else:
                    dirs.sort()
    seen, unique = set(), []
    for path in out:
        real = os.path.realpath(path)
        if real not in seen:
            seen.add(real)
            unique.append(path)
    return unique


def num(value, default=0.0):
    return default if value is None else float(value)


# ----------------------------------------------------------------------- steps
def step_rows(dump, spec, basis):
    """One row per staircase plateau: what was offered, what came out by outcome."""
    rows = []
    for step in dump.get("per_step") or []:
        target = num(step.get("target_rate"))
        window = step.get("window") or [0, 0]
        dur = num(window[1]) - num(window[0])
        # The idle step-0 warm hold offers nothing; including it would make the
        # first ratio 0/0, and its completions are leftover warmup drain.
        if target <= 0 or dur <= 0:
            continue

        sc = step.get("scalars") or {}
        key, sub = spec["offered"]
        raw = sc.get(key)
        if sub is not None:
            raw = (raw or {}).get(sub)
        offered = num(raw) / dur

        outcomes = sc.get(spec["outcomes"]) or {}
        by_outcome = {k: num(v) / dur for k, v in outcomes.items()}
        good = sum(v for k, v in by_outcome.items() if k in spec["success"])
        terminal = sum(by_outcome.values())
        done = terminal if basis == "terminal" else good

        start, end = num(sc.get("inflight_start")), num(sc.get("inflight_end"))
        rows.append({
            "index": step.get("index"),
            "target_rate": target,
            "window_s": dur,
            "offered_rps": round(offered, 2),
            "good_rps": round(good, 2),
            "terminal_rps": round(terminal, 2),
            "done_rps": round(done, 2),
            "failed_rps": round(terminal - good, 2),
            "by_outcome": {k: round(v, 2) for k, v in sorted(by_outcome.items())},
            # >1 means the step retired more than it was offered, i.e. it ate
            # into an existing backlog. Capped only where peak is computed.
            "keep_ratio": round(done / offered, 4) if offered > 0 else None,
            "inflight_start": start,
            "inflight_end": end,
            "backlog_growth_rps": round((end - start) / dur, 2),
        })
    return rows


def detect(rows, tol, plateau_tol, confirm, collapse_frac, shed_tol):
    """Reduce the staircase to the four headline rates plus a shape label."""
    if not rows:
        return {"class": "no-steps"}

    def behind(row):
        return row["keep_ratio"] is not None and row["keep_ratio"] < (1.0 - tol)

    # Onset: the first step that fell behind AND stayed behind. `confirm`
    # guards against a single noisy plateau (a GC pause, a scrape gap) being
    # read as saturation; a failing final step has nothing to confirm it, so it
    # is reported with confirmed=False rather than dropped.
    onset = None
    for i, row in enumerate(rows):
        if not behind(row):
            continue
        following = rows[i + 1:i + confirm]
        if all(behind(r) for r in following):
            onset = {
                "index": row["index"],
                "target_rate": row["target_rate"],
                "offered_rps": row["offered_rps"],
                "done_rps": row["done_rps"],
                "keep_ratio": row["keep_ratio"],
                "confirmed": len(following) >= confirm - 1,
                "prev_offered_rps": rows[i - 1]["offered_rps"] if i else None,
            }
            break

    # Highest offered rate fully serviced. Taken as the run of leading healthy
    # steps, not the global max: a lone healthy step above the onset is noise,
    # not a higher ceiling.
    last_good = None
    for row in rows:
        if behind(row):
            break
        last_good = row

    # Peak goodput. min(done, offered) refuses to credit backlog catch-up as
    # capacity — see the module docstring.
    peak_row = max(rows, key=lambda r: min(r["done_rps"], r["offered_rps"]))
    peak = round(min(peak_row["done_rps"], peak_row["offered_rps"]), 2)

    # Plateau: the first step already at (within plateau_tol of) the peak.
    # Everything offered above this bought nothing.
    plateau_row = next((r for r in rows
                        if min(r["done_rps"], r["offered_rps"]) >= (1.0 - plateau_tol) * peak),
                       peak_row)

    last = rows[-1]
    end_eff = min(last["done_rps"], last["offered_rps"])
    if onset is None and plateau_row is last:
        shape = "tracking"          # still on the diagonal when the ramp ended
    elif peak > 0 and end_eff < collapse_frac * peak:
        shape = "collapse"          # throughput went backwards under more load
    elif last["offered_rps"] > 0 and \
            last["terminal_rps"] >= (1.0 - shed_tol) * last["offered_rps"]:
        shape = "load-shed"         # keeps terminating, but as failures
    else:
        shape = "plateau"

    return {
        "class": shape,
        "last_good": last_good and {
            "target_rate": last_good["target_rate"],
            "offered_rps": last_good["offered_rps"],
            "done_rps": last_good["done_rps"],
        },
        "onset": onset,
        "peak": {
            "goodput_rps": peak,
            "target_rate": peak_row["target_rate"],
            "offered_rps": peak_row["offered_rps"],
        },
        "plateau": {
            "target_rate": plateau_row["target_rate"],
            "offered_rps": plateau_row["offered_rps"],
            "goodput_rps": min(plateau_row["done_rps"], plateau_row["offered_rps"]),
        },
        "end": {
            "offered_rps": last["offered_rps"],
            "good_rps": last["good_rps"],
            "failed_rps": last["failed_rps"],
            "backlog_growth_rps": last["backlog_growth_rps"],
            "ramp_exhausted": onset is None,
        },
    }


def analyse(run, args):
    meta, dump = run["meta"], run["dump"]
    variant = meta.get("variant") or dump.get("variant") or run["_name"].split("_")[0]
    family = meta.get("variant_family") or variant.split("-")[0]
    spec, notes = FAMILIES.get(family), []
    if spec is None:
        spec, notes = FALLBACK, [f"unknown family {family!r}: used accepted/e2e fallback"]

    rows = step_rows(dump, spec, args.basis)
    # A run whose family metric never fired (an ES image without the saga
    # counter, say) yields an all-zero terminal side, which would read as an
    # instant tipping point. Retry on the generic metrics and say so.
    if rows and spec["outcomes"] != FALLBACK["outcomes"] \
            and not any(r["terminal_rps"] for r in rows):
        rows = step_rows(dump, FALLBACK, args.basis)
        notes.append(f"{spec['outcomes']} absent or all-zero: used e2e_count instead")
        spec = FALLBACK
    if rows and not any(r["offered_rps"] for r in rows):
        notes.append("offered side is all-zero: result is meaningless")

    result = detect(rows, args.tolerance, args.plateau_tol, args.confirm,
                    args.collapse_frac, args.shed_tol)
    return {
        "run": run["_name"],
        "dir": run["_dir"],
        "variant": variant,
        "family": family,
        "point": meta.get("point") or meta.get("run_label"),
        "scenario": meta.get("scenario") or dump.get("scenario"),
        "replicas": meta.get("expected_replicas"),
        "basis": args.basis,
        "offered_metric": spec["offered_label"],
        "done_metric": spec["done_label"],
        "notes": notes,
        "steps": rows,
        **result,
    }


# -------------------------------------------------------------------- rendering
def render(rows, headers, style):
    if style in ("csv", "tsv"):
        sep = "," if style == "csv" else "\t"
        return "\n".join([sep.join(headers)] + [sep.join(str(c) for c in r) for r in rows])

    widths = [max([len(headers[i])] + [len(str(r[i])) for r in rows])
              for i in range(len(headers))]
    out = ["| " + " | ".join(h.ljust(widths[i]) for i, h in enumerate(headers)) + " |",
           "|" + "|".join("-" * (w + 2) for w in widths) + "|"]
    for row in rows:
        out.append("| " + " | ".join(str(c).ljust(widths[i]) for i, c in enumerate(row)) + " |")
    return "\n".join(out)


def cell(value, spec="—"):
    return spec if value is None else value


def summary_table(results, style):
    headers = ["variant", "point", "repl", "basis", "sustained/s", "tipping/s",
               "peak good/s", "plateau/s", "end good/s", "end fail/s",
               "backlog/s", "shape"]
    rows = []
    for res in results:
        last_good = res.get("last_good") or {}
        onset = res.get("onset") or {}
        peak = res.get("peak") or {}
        plateau = res.get("plateau") or {}
        end = res.get("end") or {}
        tip = onset.get("offered_rps")
        rows.append([
            res["variant"],
            cell(res.get("point")),
            cell(res.get("replicas")),
            res["basis"],
            f"{last_good.get('offered_rps'):.0f}" if last_good.get("offered_rps") else "—",
            (f"{tip:.0f}" + ("" if onset.get("confirmed", True) else "?")) if tip else "none",
            f"{peak.get('goodput_rps', 0):.0f}",
            f"{plateau.get('offered_rps', 0):.0f}",
            f"{end.get('good_rps', 0):.0f}",
            f"{end.get('failed_rps', 0):.0f}",
            f"{end.get('backlog_growth_rps', 0):+.0f}",
            res.get("class", "?"),
        ])
    return render(rows, headers, style)


def step_blocks(results, style):
    blocks = []
    for res in results:
        onset_idx = (res.get("onset") or {}).get("index")
        peak_rate = (res.get("peak") or {}).get("target_rate")
        headers = ["step", "target/s", "offered/s", "done/s", "keep%", "outcomes",
                   "inflight", "backlog/s", ""]
        rows = []
        for row in res["steps"]:
            marks = []
            if row["index"] == onset_idx:
                marks.append("<- TIPPING POINT")
            if row["target_rate"] == peak_rate:
                marks.append("<- peak")
            rows.append([
                row["index"], f"{row['target_rate']:.0f}", f"{row['offered_rps']:.1f}",
                f"{row['done_rps']:.1f}",
                f"{row['keep_ratio'] * 100:.0f}" if row["keep_ratio"] is not None else "—",
                " ".join(f"{k}={v:.1f}" for k, v in row["by_outcome"].items()) or "—",
                f"{row['inflight_start']:.0f}->{row['inflight_end']:.0f}",
                f"{row['backlog_growth_rps']:+.1f}",
                " ".join(marks),
            ])
        title = (f"### {res['variant']} {res.get('point') or ''} — {res['run']}\n"
                 f"offered = {res['offered_metric']}, done = {res['done_metric']} "
                 f"({res['basis']})")
        blocks.append(title + "\n\n" + render(rows, headers, style) + "\n\n" + verdict(res))
    return "\n\n".join(blocks)


def verdict(res):
    peak = (res.get("peak") or {}).get("goodput_rps", 0)
    onset = res.get("onset") or {}
    last_good = res.get("last_good") or {}
    plateau = res.get("plateau") or {}
    shape = res.get("class")
    if shape == "tracking":
        return (f"No tipping point within the tested range: processing tracked the offered "
                f"load to the end of the staircase ({peak:.0f}/s). Ramp higher to find it.")
    lines = []
    if onset.get("offered_rps"):
        qualifier = "" if onset.get("confirmed", True) else " (last step, unconfirmed)"
        lines.append(f"Fell behind at {onset['offered_rps']:.0f} orders/s"
                     f"{qualifier} — retired {onset['done_rps']:.0f}/s of them "
                     f"({onset['keep_ratio'] * 100:.0f}%).")
    if last_good.get("offered_rps"):
        lines.append(f"Highest fully-serviced rate: {last_good['offered_rps']:.0f}/s.")
    lines.append(f"Throughput stopped increasing at {plateau.get('offered_rps', 0):.0f} "
                 f"orders/s offered, peaking at {peak:.0f}/s.")
    end = res.get("end") or {}
    if shape == "collapse":
        lines.append(f"Collapse: by {end.get('offered_rps', 0):.0f}/s offered it was down to "
                     f"{end.get('good_rps', 0):.0f}/s, backlog growing "
                     f"{end.get('backlog_growth_rps', 0):+.0f}/s.")
    elif shape == "load-shed":
        lines.append(f"Load-shed: it keeps terminating orders at the offered rate, but "
                     f"{end.get('failed_rps', 0):.0f}/s of them end as failures.")
    return "\n".join(lines)


# -------------------------------------------------------------------------- cli
def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="run dirs, dirs of run dirs, or globs")
    ap.add_argument("--basis", choices=("goodput", "terminal"), default="goodput",
                    help="count only successful outcomes (default) or every terminal "
                         "outcome as processed. TO sheds load as rejections, so "
                         "`terminal` will report it as keeping up to the last step")
    ap.add_argument("--tolerance", type=float, default=0.05,
                    help="how far below offered a step may fall and still count as "
                         "keeping up (default 0.05)")
    ap.add_argument("--plateau-tol", type=float, default=0.05,
                    help="how close to peak counts as 'already at the plateau' "
                         "(default 0.05)")
    ap.add_argument("--confirm", type=int, default=2,
                    help="consecutive behind steps required to call it the tipping "
                         "point (default 2)")
    ap.add_argument("--collapse-frac", type=float, default=0.7,
                    help="final goodput below this fraction of peak is a collapse "
                         "(default 0.7)")
    ap.add_argument("--shed-tol", type=float, default=0.05,
                    help="terminal rate within this of offered at the last step means "
                         "load-shedding rather than queueing (default 0.05)")
    ap.add_argument("--steps", action="store_true", help="print the per-step staircase")
    ap.add_argument("-f", "--format", choices=("md", "csv", "tsv"), default="md")
    ap.add_argument("--json", metavar="PATH", help="write the full analysis as JSON")
    args = ap.parse_args()

    results, skipped = [], []
    for path in expand(args.paths):
        run = load_run(path)
        if run is None:
            skipped.append((os.path.basename(path.rstrip("/")), "no dump.json"))
            continue
        res = analyse(run, args)
        if not res["steps"]:
            skipped.append((res["run"], f"no staircase steps (scenario={res['scenario']})"))
            continue
        results.append(res)

    results.sort(key=lambda r: (FAMILY_ORDER.get(r["family"], 9), r["variant"],
                                str(r.get("point")), r["run"]))

    for name, why in skipped:
        print(f"warn: skipping {name}: {why}", file=sys.stderr)
    if not results:
        print("error: no capacity runs found", file=sys.stderr)
        return 1

    if args.steps:
        print(step_blocks(results, args.format))
        print()
    print(summary_table(results, args.format))
    for res in results:
        for note in res["notes"]:
            print(f"note: {res['run']}: {note}", file=sys.stderr)

    if args.json:
        with open(args.json, "w") as fh:
            json.dump({"basis": args.basis,
                       "params": {"tolerance": args.tolerance,
                                  "plateau_tol": args.plateau_tol,
                                  "confirm": args.confirm,
                                  "collapse_frac": args.collapse_frac,
                                  "shed_tol": args.shed_tol},
                       "runs": results}, fh, indent=1)
        print(f"wrote {args.json}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
