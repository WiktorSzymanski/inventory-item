#!/usr/bin/env bash
# Restore a Prometheus TSDB snapshot into the prometheus-data volume.
# Prometheus is stopped during restore and restarted after.
#
# Usage:  ./scripts/prom_restore.sh <snapshot-dir>
#
# Example:
#   ./scripts/prom_restore.sh reports/prom-snapshot-20260526_213000-es-run1

set -euo pipefail

SNAPSHOT_DIR="${1:-}"
if [[ -z "$SNAPSHOT_DIR" ]]; then
    echo "Usage: $0 <snapshot-dir>"
    echo "Example: $0 reports/prom-snapshot-20260526_213000-es-run1"
    exit 1
fi

SNAPSHOT_DIR=$(realpath "$SNAPSHOT_DIR")

if [[ ! -d "$SNAPSHOT_DIR" ]]; then
    echo "Error: snapshot directory not found: $SNAPSHOT_DIR"
    exit 1
fi

echo "==> Stopping Prometheus..."
docker compose stop prometheus

# Resolve the actual Docker volume name from the running container config.
# Docker prefixes volumes with the compose project name (usually the directory name),
# so the real name is e.g. "inventoryitemreservation_prometheus-data", not "prometheus-data".
VOLUME_NAME=$(docker inspect prometheus \
    --format '{{range .Mounts}}{{if eq .Destination "/prometheus"}}{{.Name}}{{end}}{{end}}')

if [[ -z "$VOLUME_NAME" ]]; then
    echo "Error: could not determine Prometheus volume name from container config."
    exit 1
fi

echo "==> Replacing volume contents with snapshot (volume: ${VOLUME_NAME})..."
docker run --rm \
    -v "${VOLUME_NAME}:/prometheus" \
    -v "${SNAPSHOT_DIR}:/snapshot:ro" \
    alpine sh -c "rm -rf /prometheus/* && cp -r /snapshot/. /prometheus/"

echo "==> Starting Prometheus..."
docker compose start prometheus

echo ""
echo "Prometheus restored from: ${SNAPSHOT_DIR}"

# Derive time range from the snapshot block meta.json files (no Prometheus query needed).
RANGE=$(python3 - "$SNAPSHOT_DIR" <<'EOF'
import json, sys, os, datetime, glob

snap_dir = sys.argv[1]
min_t, max_t = float('inf'), float('-inf')

for meta in glob.glob(os.path.join(snap_dir, '*', 'meta.json')):
    with open(meta) as f:
        m = json.load(f)
    min_t = min(min_t, m['minTime'] / 1000)
    max_t = max(max_t, m['maxTime'] / 1000)

if min_t == float('inf'):
    print("unknown")
else:
    fmt = '%Y-%m-%d %H:%M:%S'
    start = datetime.datetime.fromtimestamp(min_t).strftime(fmt)
    end   = datetime.datetime.fromtimestamp(max_t).strftime(fmt)
    print(f"{start}  →  {end}")
EOF
)

echo ""
echo "Data time range (local time):  ${RANGE}"
echo "Set this range in Grafana's time picker to see the data."
echo "Open Grafana: http://localhost:3000"
