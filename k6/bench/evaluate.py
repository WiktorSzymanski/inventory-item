#!/usr/bin/env python3
"""Turn one run's artifacts into a PASS / FAIL / INVALID verdict.

Three states, not two. INVALID is what makes the harness trustworthy for a thesis: it
separates "the system failed the SLO" from "the measurement itself was broken". A run
whose e2e histogram is truncated because the backlog never drained is not a slow run —
it is an unusable one, and reporting its optimistic percentile as a result would be
worse than reporting nothing.

Exit codes:  0 PASS,  1 FAIL,  2 INVALID
"""
import argparse
import json
import os
import sys

PASS, FAIL, INVALID = "PASS", "FAIL", "INVALID"


def load(path, required=True):
    if not os.path.exists(path):
        if required:
            raise SystemExit(f"missing required artifact: {path}")
        return {}
    with open(path) as fh:
        return json.load(fh)


def num(value, default=None):
    return value if isinstance(value, (int, float)) else default


def dget(container, key, default=None):
    if isinstance(container, dict):
        return num(container.get(key), default)
    return default


class Checks:
    def __init__(self):
        self.items = []

    def add(self, name, kind, actual, limit, ok, note=None):
        entry = {"name": name, "kind": kind, "actual": actual, "limit": limit, "pass": bool(ok)}
        if note:
            entry["note"] = note
        self.items.append(entry)
        return entry

    def upper(self, name, kind, actual, limit):
        """actual must be <= limit. Unknown limit skips; unknown actual is a soft skip."""
        if limit is None:
            return None
        if actual is None:
            return self.add(name, kind, None, limit, True, "no data — check skipped")
        return self.add(name, kind, actual, limit, actual <= limit)

    def failed(self, kind):
        return [c for c in self.items if c["kind"] == kind and not c["pass"]]


# --------------------------------------------------------------------------- validity
def check_validity(checks, meta, dump, summary, cfg):
    scalars = dump.get("scalars", {})
    derived = dump.get("derived", {})
    metrics = summary.get("metrics", {})

    iterations = dget(metrics.get("iterations", {}).get("values", {}), "count", 0)
    dropped = dget(metrics.get("dropped_iterations", {}).get("values", {}), "count", 0)
    offered = iterations + dropped
    ratio = (dropped / offered) if offered else 0.0
    checks.upper("dropped_iteration_ratio", "validity", round(ratio, 6),
                 cfg.get("max_dropped_iteration_ratio"))

    # If maxVUs bound, `dropped_iterations` means "k6 ran out of VUs", not "the system
    # was slow" — the run measures the load generator rather than the system under test.
    vus_max = dget(metrics.get("vus_max", {}).get("values", {}), "max")
    limits = load(os.path.join(ARGS.run_dir, "profile.json"), required=False).get("vu_limits", {})
    ceiling = None
    for spec in limits.values():
        if isinstance(spec, dict) and isinstance(spec.get("maxVUs"), (int, float)):
            ceiling = max(ceiling or 0, spec["maxVUs"])
    if ceiling and vus_max is not None:
        checks.add("vus_max_below_ceiling", "validity", vus_max, ceiling, vus_max < ceiling)

    checks.add("scrape_up", "validity", scalars.get("scrape_up_min"), 1,
               num(scalars.get("scrape_up_min"), 0) >= 1)
    checks.add("no_api_restart", "validity", scalars.get("api_resets"), 0,
               num(scalars.get("api_resets"), 0) == 0)

    expected = meta.get("expected_replicas", 1)
    checks.add("targets_scraped", "validity", scalars.get("target_count"), expected,
               num(scalars.get("target_count"), 0) == expected)

    checks.add("backlog_drained", "validity", derived.get("drained"), True,
               bool(derived.get("drained")))

    checks.upper("completion_ratio_inverse", "validity",
                 round(1.0 - (derived.get("completion_ratio") or 0.0), 6)
                 if derived.get("completion_ratio") is not None else None,
                 round(1.0 - cfg["min_completion_ratio"], 6)
                 if cfg.get("min_completion_ratio") is not None else None)

    # At REPLICAS=1 the JVM-local LockFactory serialises every writer to an aggregate, so no
    # 23505 can occur and no command can exhaust its retries. A non-zero count therefore does
    # not mean "contention" — it falsifies that assumption, and the single-node baseline this
    # run is supposed to produce cannot be trusted. Above 1 the count is expected and is the
    # contention signal itself, so the check only applies to single-node runs.
    # An absent `command_failed` series means zero, not "no data" — the counter is only
    # created on first increment. So a dict without the key must PASS, while a missing
    # `saga_completed` entirely (TO-*, where the saga does not exist) must skip.
    saga_outcomes = scalars.get("saga_completed")
    if expected == 1 and isinstance(saga_outcomes, dict):
        contention = dget(saga_outcomes, "command_failed", 0)
        checks.add("saga_command_failed_single_node", "validity", contention, 0, contention == 0)

    checks.add("git_clean", "validity", meta.get("git_dirty"), 0, meta.get("git_dirty", 0) == 0)
    checks.add("image_fresh", "validity", meta.get("image_built_after_head"), True,
               bool(meta.get("image_built_after_head")))


# --------------------------------------------------------------------------- SLO
def check_slo(checks, dump, limits):
    scalars = dump.get("scalars", {})
    derived = dump.get("derived", {})

    checks.upper("e2e_p95_confirmed_s", "slo",
                 dget(scalars.get("e2e_p95"), "confirmed"), limits.get("max_e2e_p95_confirmed_s"))
    checks.upper("e2e_p99_confirmed_s", "slo",
                 dget(scalars.get("e2e_p99"), "confirmed"), limits.get("max_e2e_p99_confirmed_s"))
    checks.upper("non202_ratio", "slo", derived.get("non202_ratio"), limits.get("max_non202_ratio"))
    checks.upper("rejected_ratio", "slo", derived.get("rejected_ratio"),
                 limits.get("max_rejected_ratio"))
    checks.upper("opt_exhausted", "slo", num(scalars.get("opt_exhausted")),
                 limits.get("max_opt_exhausted"))
    checks.upper("projection_lag_p95_s", "slo", num(scalars.get("projection_lag_p95")),
                 limits.get("max_projection_lag_p95_s"))
    checks.upper("drain_seconds", "slo", derived.get("drain_seconds"),
                 limits.get("max_drain_seconds"))


# --------------------------------------------------------------------------- knee
def detect_knee(dump, limits):
    """Highest staircase step that is still keeping up.

    Three conditions, all required:
      1. the system accepted ~everything offered,
      2. e2e p95 stayed under the SLO,
      3. in-flight orders did not grow across the plateau.
    (3) is the load-bearing one: it needs no SLO guess, because a queue growing
    monotonically at a constant offered rate is the definition of saturation.
    """
    steps = dump.get("per_step") or []
    if not steps:
        return None

    tol = limits.get("knee_rate_tolerance", 0.95)
    slo = limits.get("knee_e2e_p95_s", 2.0)
    growth = limits.get("knee_inflight_growth", 1.2)

    knee, table = None, []
    for step in steps:
        sc, dv = step.get("scalars", {}), step.get("derived", {})
        target = step["target_rate"]
        achieved = dv.get("achieved_rps") or 0.0
        p95 = dget(sc.get("e2e_p95"), "confirmed")
        start = num(sc.get("inflight_start"), 0.0)
        end = num(sc.get("inflight_end"), 0.0)

        keeps_rate = achieved >= tol * target
        meets_slo = p95 is not None and p95 <= slo
        # A near-zero starting queue makes the ratio meaningless; use an absolute floor.
        stable = end <= max(start * growth, start + 5)

        ok = keeps_rate and meets_slo and stable
        reasons = []
        if not keeps_rate:
            reasons.append(f"achieved {achieved:.1f}/s < {tol:.0%} of {target}/s")
        if not meets_slo:
            reasons.append(f"e2e p95 {p95}s > {slo}s" if p95 is not None else "no e2e data")
        if not stable:
            ratio = (end / start) if start else float("inf")
            reasons.append(f"inflight grew {start:.0f}->{end:.0f} ({ratio:.1f}x)")

        table.append({
            "index": step["index"], "target_rate": target,
            "achieved_rps": round(achieved, 2), "e2e_p95_confirmed": p95,
            "inflight_start": start, "inflight_end": end,
            "sustained": ok, "reasons": reasons,
        })
        if ok:
            knee = {"rps": target, "step_index": step["index"], "reason": "sustained"}
        elif knee is not None:
            knee["reason"] = f"step {step['index']} failed: " + "; ".join(reasons)
            break

    return {"knee": knee, "steps": table}


# --------------------------------------------------------------------------- drift
def series_points(series, name):
    entry = series.get(name)
    if isinstance(entry, list):
        return [p for p in entry if isinstance(p, list) and len(p) == 2]
    return []


def drift_ratio(points, fraction=0.1):
    """Mean of the last `fraction` of the run over the mean of the first `fraction`."""
    values = [v for _, v in points if v == v]  # drop NaN
    if len(values) < 20:
        return None
    n = max(2, int(len(values) * fraction))
    head = sum(values[:n]) / n
    tail = sum(values[-n:]) / n
    if head <= 0:
        return None
    return round(tail / head, 4)


def check_drift(checks, dump, limits):
    series = dump.get("series", {})
    checks.upper("e2e_p95_drift_ratio", "slo",
                 drift_ratio(series_points(series, "e2e_p95_1m")),
                 limits.get("max_e2e_p95_drift_ratio"))
    checks.upper("heap_drift_ratio", "slo",
                 drift_ratio(series_points(series, "heap")),
                 limits.get("max_heap_drift_ratio"))


def check_recovery(checks, dump, limits):
    """Spike: seconds after the burst before in-flight returns to its pre-spike level."""
    limit = limits.get("max_recovery_seconds")
    if limit is None:
        return
    points = series_points(dump.get("series", {}), "inflight")
    steps = {s.get("label"): s for s in dump.get("per_step", []) if s.get("label")}
    pre, burst = steps.get("pre"), steps.get("burst")
    if not points or not pre or not burst:
        checks.add("recovery_seconds", "slo", None, limit, True, "no data — check skipped")
        return

    baseline = num(pre.get("scalars", {}).get("inflight_end"), 0.0)
    burst_end = burst["window"][1]
    recovered = next((ts for ts, v in points if ts >= burst_end and v <= max(baseline * 1.1, baseline + 5)), None)
    actual = (recovered - burst_end) if recovered else None
    checks.add("recovery_seconds", "slo", actual, limit,
               actual is not None and actual <= limit,
               None if actual is not None else "never recovered within the run")


# --------------------------------------------------------------------------- main
def main():
    global ARGS
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--thresholds", required=True)
    ARGS = ap.parse_args()

    meta = load(os.path.join(ARGS.run_dir, "meta.json"))
    dump = load(os.path.join(ARGS.run_dir, "dump.json"))
    summary = load(os.path.join(ARGS.run_dir, "summary.json"), required=False)
    conf = load(ARGS.thresholds)

    scenario = meta.get("scenario", "steady")
    limits = dict(conf.get("defaults", {}))
    limits.update(conf.get("scenarios", {}).get(scenario, {}))
    limits.pop("_comment", None)

    checks = Checks()
    check_validity(checks, meta, dump, summary, conf.get("validity", {}))
    check_slo(checks, dump, limits)

    if scenario == "soak":
        check_drift(checks, dump, limits)
    if scenario == "spike":
        check_recovery(checks, dump, limits)

    result = {
        "run_id": meta.get("run_id"),
        "variant": meta.get("variant"),
        "scenario": scenario,
        "verdict": PASS,
        "checks": checks.items,
    }

    if limits.get("require_knee"):
        knee = detect_knee(dump, limits)
        if knee:
            result["knee"] = knee["knee"]
            result["knee_steps"] = knee["steps"]
        if knee is None or knee["knee"] is None:
            checks.add("knee_found", "slo", None, "any sustained step", False,
                       "no step met all three sustain conditions")
        else:
            floor = limits.get("min_knee_rps")
            checks.add("knee_found", "slo", knee["knee"]["rps"], floor or "any", True)

    if checks.failed("validity"):
        result["verdict"] = INVALID
    elif checks.failed("slo"):
        result["verdict"] = FAIL

    dest = os.path.join(ARGS.run_dir, "verdict.json")
    with open(dest, "w") as fh:
        json.dump(result, fh, indent=2)

    print(f"verdict: {result['verdict']}")
    for check in checks.items:
        if not check["pass"]:
            print(f"  {check['kind']:8s} {check['name']:32s} "
                  f"actual={check['actual']} limit={check['limit']}")

    return {PASS: 0, FAIL: 1, INVALID: 2}[result["verdict"]]


if __name__ == "__main__":
    sys.exit(main())
