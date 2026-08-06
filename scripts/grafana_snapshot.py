#!/usr/bin/env python3
"""
Creates a self-contained Grafana snapshot with panel data embedded.

Replicates what the Grafana UI does when you click Share → Snapshot:
fetches each panel's data via /api/ds/query and embeds the frames into
the dashboard JSON so the snapshot renders without a live datasource.

Run on the host after a load test completes (Grafana must be up at localhost:3000).

Usage:
    # Last 10 minutes (default — right after a test finishes):
    python3 scripts/grafana_snapshot.py --duration 10m --output reports/snapshot_run1.json

    # Historic window: 10-minute test that ended 1 hour ago:
    python3 scripts/grafana_snapshot.py --duration 10m --to now-1h --output reports/snapshot_run1.json

    # Exact timestamps:
    python3 scripts/grafana_snapshot.py --duration 10m --to 2026-05-26T14:30:00 --output reports/snapshot_run1.json
"""

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

GRAFANA_URL = "http://localhost:3000"
GRAFANA_USER = "admin"
GRAFANA_PASSWORD = "admin"
DASHBOARD_UID = "the-dashboard"

# Approximate step: aim for ~300 data points across the test window.
TARGET_POINTS = 300

# Grafana built-in datasource used for snapshot panels.
# Panels must be switched to this so Grafana reads snapshotData
# instead of querying the original datasource.
SNAPSHOT_DS = {"type": "-- Grafana --", "uid": "-- Grafana --"}

# Grafana palette-classic colours in their canonical assignment order.
# Snapshots need colours baked in because palette-classic is resolved
# dynamically by the live frontend and is not stored in the data.
PALETTE = [
    "#7EB26D", "#EAB839", "#6ED0E0", "#EF843C",
    "#E24D42", "#1F78C1", "#BA43A9", "#705DA0",
    "#508642", "#CCA300", "#447EBC", "#C15C17",
    "#890F02", "#0A437C", "#6D1F62", "#584477",
]


def _auth_header() -> str:
    creds = base64.b64encode(f"{GRAFANA_USER}:{GRAFANA_PASSWORD}".encode()).decode()
    return f"Basic {creds}"


def grafana_get(path: str) -> dict:
    req = urllib.request.Request(f"{GRAFANA_URL}{path}")
    req.add_header("Authorization", _auth_header())
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())


def grafana_post(path: str, body: dict) -> dict:
    data = json.dumps(body).encode()
    req = urllib.request.Request(f"{GRAFANA_URL}{path}", data=data, method="POST")
    req.add_header("Authorization", _auth_header())
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())


def parse_duration_seconds(s: str) -> int:
    s = s.strip()
    if s.endswith("m"):
        return int(s[:-1]) * 60
    if s.endswith("h"):
        return int(s[:-1]) * 3600
    if s.endswith("s"):
        return int(s[:-1])
    return int(s)


def parse_to_ms(to_str: str) -> int:
    """
    Parse --to value to a Unix timestamp in milliseconds.

    Accepted formats:
      now              → current time
      now-10m          → 10 minutes ago
      now-1h           → 1 hour ago
      now-30s          → 30 seconds ago
      2026-05-26T14:30:00        (local time)
      2026-05-26T14:30:00Z       (UTC)
      2026-05-26T14:30:00+02:00  (with offset)
    """
    s = to_str.strip().lower()

    if s == "now":
        return int(time.time() * 1000)

    if s.startswith("now-"):
        offset_sec = parse_duration_seconds(s[4:])
        return int((time.time() - offset_sec) * 1000)

    # Try ISO 8601 datetime
    for fmt in (
        "%Y-%m-%dT%H:%M:%SZ",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d",
    ):
        try:
            dt = datetime.strptime(s, fmt)
            if fmt.endswith("Z"):
                dt = dt.replace(tzinfo=timezone.utc)
            elif dt.tzinfo is None:
                dt = dt.astimezone(timezone.utc)
            return int(dt.timestamp() * 1000)
        except ValueError:
            continue

    # Try datetime with timezone offset (Python 3.7+)
    try:
        dt = datetime.fromisoformat(s)
        if dt.tzinfo is None:
            dt = dt.astimezone(timezone.utc)
        return int(dt.timestamp() * 1000)
    except ValueError:
        pass

    raise ValueError(
        f"Cannot parse --to value '{to_str}'. "
        "Use 'now', 'now-1h', or an ISO 8601 datetime like '2026-05-26T14:30:00'."
    )


def frames_to_snapshot_data(frames: list) -> list:
    """
    Convert /api/ds/query data-plane frames (schema + columnar data arrays)
    to the DataFrameDTO[] format stored in panel.snapshotData.

    Prometheus returns every series with the generic field name "Value", using
    displayNameFromDS in field.config to carry the formatted legend label.
    Grafana assigns palette colours and legend toggle keys by field name, so
    we rename each value field to its displayNameFromDS and set the frame name
    to the same string — otherwise all series look identical in the snapshot.
    """
    result = []
    for frame in frames:
        schema = frame.get("schema", {})
        data = frame.get("data", {})
        field_schemas = schema.get("fields", [])
        column_values = data.get("values", [])

        fields = []
        display_name = None

        for i, fs in enumerate(field_schemas):
            field_name = fs.get("name", "")
            field_type = fs.get("type", "")

            if field_type == "number":
                dn = fs.get("config", {}).get("displayNameFromDS")
                if dn:
                    display_name = dn
                    field_name = dn  # unique name per series → unique colour + toggle key

            field: dict = {
                "name": field_name,
                "type": field_type,
                "typeInfo": fs.get("typeInfo", {}),
                "values": column_values[i] if i < len(column_values) else [],
            }
            if "labels" in fs:
                field["labels"] = fs["labels"]
            if "config" in fs:
                field["config"] = fs["config"]
            fields.append(field)

        length = len(column_values[0]) if column_values else 0
        dto: dict = {"fields": fields, "length": length}

        # frame.name is used by Grafana as the series display name;
        # prefer schema.name (set by some datasources), fall back to displayNameFromDS
        frame_name = schema.get("name") or display_name
        if frame_name:
            dto["name"] = frame_name
        if "refId" in schema:
            dto["refId"] = schema["refId"]

        result.append(dto)
    return result


def assign_colors(frames: list) -> list:
    """Embed explicit palette colours into every value field across a panel's frames."""
    idx = 0
    for frame in frames:
        for field in frame.get("fields", []):
            if field.get("type") == "number":
                field.setdefault("config", {})["color"] = {
                    "mode": "fixed",
                    "fixedColor": PALETTE[idx % len(PALETTE)],
                }
                idx += 1
    return frames


def fetch_panel_data(panel: dict, from_ms: int, to_ms: int) -> list:
    """Query Grafana for each panel target and return snapshot-ready frames."""
    targets = panel.get("targets", [])
    if not targets:
        return []

    duration_sec = (to_ms - from_ms) / 1000
    max_data_points = max(300, int(duration_sec / (duration_sec / TARGET_POINTS)))

    queries = [
        {
            "datasource": target.get("datasource"),
            "expr": target.get("expr", ""),
            "legendFormat": target.get("legendFormat", ""),
            "refId": target.get("refId", "A"),
            "range": True,
            "instant": False,
            "interval": "",
            "maxDataPoints": max_data_points,
        }
        for target in targets
    ]

    try:
        result = grafana_post(
            "/api/ds/query",
            {"queries": queries, "from": str(from_ms), "to": str(to_ms)},
        )
        results = result.get("results", {})
        all_frames = []
        for target in targets:
            ref_id = target.get("refId", "A")
            if ref_id in results:
                frames = results[ref_id].get("frames", [])
                all_frames.extend(frames_to_snapshot_data(frames))
        return all_frames
    except urllib.error.HTTPError as e:
        print(f"    HTTP {e.code}: {e.read().decode()[:200]}", file=sys.stderr)
        return []
    except Exception as e:
        print(f"    Error: {e}", file=sys.stderr)
        return []


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create a self-contained Grafana snapshot with embedded panel data."
    )
    parser.add_argument(
        "--from", dest="from_", default=None,
        help="Start of the capture window, e.g. '2026-05-26T21:06:09' or 'now-1h'",
    )
    parser.add_argument(
        "--to",
        default="now",
        help="End of the capture window (default: now), e.g. '2026-05-26T21:17:02' or 'now-30m'",
    )
    parser.add_argument(
        "--duration", default=None,
        help="Window size, e.g. 10m or 1h. Used when --from is omitted.",
    )
    parser.add_argument("--output", required=True, help="Output path for snapshot JSON")
    parser.add_argument(
        "--name", default=None, help="Snapshot name (defaults to run-<timestamp>)"
    )
    args = parser.parse_args()

    if args.from_ is None and args.duration is None:
        parser.error("Provide either --from or --duration.")

    to_ms = parse_to_ms(args.to)

    if args.from_ is not None:
        from_ms = parse_to_ms(args.from_)
    else:
        duration_sec = parse_duration_seconds(args.duration)
        from_ms = to_ms - duration_sec * 1000

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    name = args.name or f"run-{timestamp}"

    from_iso = datetime.fromtimestamp(from_ms / 1000, tz=timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%S.000Z"
    )
    to_iso = datetime.fromtimestamp(to_ms / 1000, tz=timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%S.000Z"
    )

    print(f"Fetching dashboard '{DASHBOARD_UID}' from {GRAFANA_URL} ...")
    dashboard_resp = grafana_get(f"/api/dashboards/uid/{DASHBOARD_UID}")
    dashboard = dashboard_resp["dashboard"]

    # Pin the time range to the exact test window so the snapshot always
    # opens on the right data regardless of Grafana's default time picker.
    dashboard["time"] = {"from": from_iso, "to": to_iso}

    panels = dashboard.get("panels", [])
    print(f"Time range: {from_iso}  →  {to_iso}")
    print(f"Fetching data for {len(panels)} panel(s)...")

    for panel in panels:
        panel_id = panel.get("id")
        title = panel.get("title", f"panel-{panel_id}")
        panel_type = panel.get("type", "")
        if panel_type in ("row", "text", "news"):
            print(f"  [{panel_id}] {title}  (skipped — {panel_type})")
            continue

        print(f"  [{panel_id}] {title} ...", end=" ", flush=True)
        snapshot_data = assign_colors(fetch_panel_data(panel, from_ms, to_ms))
        if snapshot_data:
            panel["snapshotData"] = snapshot_data
            # Mirror what the Grafana UI does: switch panel and all targets
            # to the built-in snapshot datasource so Grafana reads
            # snapshotData instead of querying Prometheus.
            panel["datasource"] = SNAPSHOT_DS
            panel["targets"] = [
                {
                    "refId": t.get("refId", "A"),
                    "datasource": SNAPSHOT_DS,
                    "queryType": "snapshot",
                }
                for t in panel.get("targets", [])
            ]
            total_points = sum(f.get("length", 0) for f in snapshot_data)
            print(f"{len(snapshot_data)} frame(s), {total_points} point(s)")
        else:
            print("no data")

    # Save to file BEFORE uploading — this is the source of truth.
    # The file is in the exact format POST /api/snapshots expects, so
    # re-importing is a single curl call and Grafana UI import won't mangle it.
    import_body = {"dashboard": dashboard, "name": name, "expires": 0}
    with open(args.output, "w") as f:
        json.dump(import_body, f, indent=2)
    print(f"\nSnapshot saved to:  {args.output}")

    print("Uploading snapshot to Grafana...")
    snap_result = grafana_post("/api/snapshots", import_body)

    snap_key = snap_result.get("key")
    if not snap_key:
        print(f"Warning: Grafana upload failed: {snap_result}", file=sys.stderr)
        print("The file is still saved and can be imported manually.")
    else:
        print(f"View locally:       {GRAFANA_URL}/dashboard/snapshot/{snap_key}")

    print()
    print("To import on another Grafana instance:")
    print(f"  curl -X POST http://admin:admin@<host>:3000/api/snapshots \\")
    print(f"    -H 'Content-Type: application/json' \\")
    print(f"    -d @{args.output}")


if __name__ == "__main__":
    main()
