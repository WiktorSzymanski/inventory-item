#!/usr/bin/env python3
"""Snapshot the server-side Prometheus metrics for one benchmark run into dump.json.

This is the PRIMARY data source for the harness. k6 is fire-and-forget — POST
/inventory/orders returns 202 after persisting only OrderCreatedEvent — so k6 never
observes end-to-end order latency. That number lives exclusively in the server-side
order_e2e_time histogram, which is what this script extracts.

Python 3 standard library only, by design: the host already has everything needed and the
harness must not acquire dependencies that could drift between variant runs.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

QUANTILES = [("p50", 0.5), ("p95", 0.95), ("p99", 0.99)]


# --------------------------------------------------------------------------- Prometheus
def _get(prom, path, params):
    url = f"{prom}{path}?{urllib.parse.urlencode(params)}"
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            payload = json.load(resp)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:400]
        raise SystemExit(f"prometheus {exc.code} for {params.get('query')!r}: {body}")
    except urllib.error.URLError as exc:
        raise SystemExit(f"cannot reach prometheus at {prom}: {exc}")
    if payload.get("status") != "success":
        raise SystemExit(f"prometheus error for {params.get('query')!r}: {payload}")
    return payload["data"]


def instant(prom, expr, at=None):
    """Return [(labels_dict, float_value), ...] for an instant query.

    NaN is dropped rather than propagated. histogram_quantile over an all-zero bucket
    difference legitimately returns NaN (no observations in the window), and json.dump
    would otherwise emit a bare `NaN` literal — accepted by Python's own parser but
    invalid JSON for anything else that reads these artifacts later.
    """
    params = {"query": expr}
    if at is not None:
        params["time"] = at
    data = _get(prom, "/api/v1/query", params)
    out = []
    for series in data.get("result", []):
        try:
            value = float(series["value"][1])
        except (KeyError, ValueError, TypeError):
            continue
        if value != value or value in (float("inf"), float("-inf")):
            continue
        out.append((series.get("metric", {}), value))
    return out


def query_range(prom, expr, start, end, step=5):
    data = _get(
        prom,
        "/api/v1/query_range",
        {"query": expr, "start": start, "end": end, "step": step},
    )
    out = []
    for series in data.get("result", []):
        points = []
        for ts, val in series.get("values", []):
            try:
                fv = float(val)
            except (TypeError, ValueError):
                continue
            if fv != fv or fv in (float("inf"), float("-inf")):
                continue
            points.append([int(ts), fv])
        out.append({"labels": series.get("metric", {}), "points": points})
    return out


# --------------------------------------------------------------------------- helpers
def label_key(labels):
    """Collapse a label set to the single dimension we grouped by, else a joined key."""
    interesting = {k: v for k, v in labels.items() if k not in ("__name__", "le")}
    if not interesting:
        return None
    if len(interesting) == 1:
        return next(iter(interesting.values()))
    return "/".join(f"{k}={v}" for k, v in sorted(interesting.items()))


def collapse(pairs):
    """[(labels, value)] -> scalar when unlabelled, else {label: value}."""
    if not pairs:
        return None
    if len(pairs) == 1 and label_key(pairs[0][0]) is None:
        return pairs[0][1]
    out = {}
    for labels, value in pairs:
        key = label_key(labels)
        out[key if key is not None else "_"] = value
    return out


def subtract(end, start):
    """Delta between two collapse() results, preserving shape."""
    if end is None:
        return None
    if isinstance(end, dict):
        base = start if isinstance(start, dict) else {}
        return {k: v - base.get(k, 0.0) for k, v in end.items()}
    return end - (start if isinstance(start, (int, float)) else 0.0)


def parse_queries(path):
    entries = []
    with open(path) as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            parts = [p for p in (p.strip() for p in parts) if p]
            if len(parts) < 3:
                print(f"warn: skipping malformed query line: {line[:80]}", file=sys.stderr)
                continue
            name, kind, expr = parts[0], parts[1], "\t".join(parts[2:])
            entries.append((name, kind, expr))
    return entries


def substitute(expr, ctx):
    for key, value in ctx.items():
        expr = expr.replace("${%s}" % key, str(value)).replace("$" + key, str(value))
    return expr


# --------------------------------------------------------------------------- collection
def collect(prom, entries, ctx, start, end, want_range):
    """Run every query for one window. Returns a scalars dict (+ series if want_range)."""
    ctx = dict(ctx, S=start, E=end, W=max(1, end - start))
    scalars, series = {}, {}

    for name, kind, raw in entries:
        expr = substitute(raw, ctx)
        try:
            if kind == "delta":
                at_end = collapse(instant(prom, expr, end))
                at_start = collapse(instant(prom, expr, start))
                scalars[name] = subtract(at_end, at_start)

            elif kind == "hist":
                for suffix, q in QUANTILES:
                    scalars[f"{name}_{suffix}"] = collapse(
                        instant(prom, expr.replace("$Q", str(q)))
                    )

            elif kind == "scalar":
                scalars[name] = collapse(instant(prom, expr, end))

            elif kind == "scalar_s":
                scalars[name] = collapse(instant(prom, expr, start))

            elif kind == "range":
                if want_range:
                    result = query_range(prom, expr, start, end)
                    if len(result) == 1:
                        series[name] = result[0]["points"]
                    elif result:
                        series[name] = {
                            (label_key(r["labels"]) or "_"): r["points"] for r in result
                        }
            else:
                print(f"warn: unknown query kind {kind!r} for {name}", file=sys.stderr)
        except SystemExit:
            raise
        except Exception as exc:  # noqa: BLE001 - one bad query must not lose the run
            print(f"warn: query {name} ({kind}) failed: {exc}", file=sys.stderr)
            scalars[name] = None

    return scalars, series


def num(value, default=0.0):
    return float(value) if isinstance(value, (int, float)) else default


def dict_get(value, key, default=0.0):
    if isinstance(value, dict):
        return num(value.get(key), default)
    return default


def derive(scalars, window, drain):
    """Everything a comparison table needs, computed once so compare.py stays trivial."""
    accepted = num(scalars.get("orders_accepted"))
    non202 = num(scalars.get("orders_non202"))
    width = max(1, window[1] - window[0])

    e2e_count = scalars.get("e2e_count") or {}
    total_e2e = sum(v for v in e2e_count.values()) if isinstance(e2e_count, dict) else num(e2e_count)
    rejected = dict_get(e2e_count, "rejected")

    e2e_sum = scalars.get("e2e_sum") or {}
    confirmed_n = dict_get(e2e_count, "confirmed")
    confirmed_s = dict_get(e2e_sum, "confirmed")

    hit = num(scalars.get("cache_hit"))
    miss = num(scalars.get("cache_miss"))
    retry = num(scalars.get("opt_retry"))
    appends = num(scalars.get("append_success"))

    db_growth = num(scalars.get("db_size_end")) - num(scalars.get("db_size_start"))
    drain_seconds = int(drain.get("drain_seconds") or 0)
    backlog = int(drain.get("backlog_at_stop") or 0)

    derived = {
        "window_seconds": width,
        "achieved_rps": round(accepted / width, 3),
        "orders_accepted": accepted,
        "orders_non202": non202,
        # Survivorship guard. order_e2e_time only records orders that reach a terminal
        # event, so a stuck saga is otherwise invisible AND silently improves the
        # percentile. evaluate.py fails the run as INVALID if this drops below 0.999.
        "completion_ratio": round(total_e2e / accepted, 6) if accepted else None,
        "non202_ratio": round(non202 / (accepted + non202), 6) if (accepted + non202) else 0.0,
        "rejected_ratio": round(rejected / total_e2e, 6) if total_e2e else 0.0,
        "e2e_mean_confirmed": round(confirmed_s / confirmed_n, 4) if confirmed_n else None,
        "conflict_ratio": round(retry / appends, 6) if appends else None,
        "cache_hit_ratio": round(hit / (hit + miss), 6) if (hit + miss) else None,
        "db_growth_bytes": db_growth,
        "db_bytes_per_order": round(db_growth / accepted, 1) if accepted else None,
        "backlog_at_stop": backlog,
        "drained": bool(drain.get("drained")),
        "drain_seconds": drain_seconds,
        # The system's true unloaded terminal-service rate, with no offered-rate
        # confound — arguably the most informative single number the harness produces.
        "drain_service_rate": round(backlog / drain_seconds, 3) if drain_seconds else None,
    }
    return derived


# --------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--prom", default="http://localhost:9090")
    ap.add_argument("--queries", required=True)
    args = ap.parse_args()

    meta_path = os.path.join(args.run_dir, "meta.json")
    if not os.path.exists(meta_path):
        raise SystemExit(f"{meta_path} not found; bench.sh writes it before dump.py runs")
    with open(meta_path) as fh:
        meta = json.load(fh)

    entries = parse_queries(args.queries)
    ctx = {
        "JOB": meta["prom_job"],
        "DB": meta["db_name"],
        "CRE": meta.get("api_container_re") or meta["prom_job"],
        "RUNID": meta["run_id"],
    }

    win_load = meta["windows"]["load"]
    win_full = meta["windows"]["full"]

    # window_full for everything by default: e2e samples are emitted by the
    # order-projection processor when it handles the terminal event, which under
    # saturation lags the load phase by minutes. A [T0,T1] window would truncate the tail.
    print(f"dump: window_full {win_full[0]}..{win_full[1]} ({win_full[1] - win_full[0]}s)")
    scalars, series = collect(args.prom, entries, ctx, win_full[0], win_full[1], True)

    # window_load additionally, for the rate-shaped quantities that must exclude drain.
    print(f"dump: window_load {win_load[0]}..{win_load[1]} ({win_load[1] - win_load[0]}s)")
    load_scalars, _ = collect(args.prom, entries, ctx, win_load[0], win_load[1], False)

    derived = derive(scalars, win_full, meta.get("drain", {}))
    derived["achieved_rps_load_window"] = round(
        num(load_scalars.get("orders_accepted")) / max(1, win_load[1] - win_load[0]), 3
    )
    derived["cpu_avg_load_window"] = load_scalars.get("cpu_avg")

    # Per-step slicing for the capacity staircase (and the spike phases). Step offsets
    # come from profile.json in seconds from scenario start; T0 makes them absolute.
    per_step = []
    for step in meta.get("steps", []):
        s = win_load[0] + int(step["stableFrom"])
        e = win_load[0] + int(step["endsAt"])
        if e > win_load[1]:
            e = win_load[1]
        if e - s < 5:
            continue
        st_scalars, _ = collect(args.prom, entries, ctx, s, e, False)
        st_derived = derive(st_scalars, [s, e], {})
        per_step.append({
            "index": step["index"],
            "label": step.get("label"),
            "target_rate": step["targetRate"],
            "window": [s, e],
            "scalars": st_scalars,
            "derived": st_derived,
        })
    if per_step:
        print(f"dump: sliced {len(per_step)} steps")

    out = {
        "schema": 1,
        "run_id": meta["run_id"],
        "variant": meta["variant"],
        "scenario": meta["scenario"],
        "windows": {"load": win_load, "full": win_full},
        "scalars": scalars,
        "load_window_scalars": load_scalars,
        "derived": derived,
        "per_step": per_step,
        "series": series,
    }

    dest = os.path.join(args.run_dir, "dump.json")
    with open(dest, "w") as fh:
        json.dump(out, fh, indent=2)
    print(f"dump: wrote {dest}")


if __name__ == "__main__":
    main()
