#!/usr/bin/env bash
# Take a Prometheus TSDB snapshot and copy it to reports/.
# Run from the project root after a load test completes.
#
# Usage:  ./scripts/prom_snapshot.sh [label]
#   label  optional human-readable suffix, e.g. "es-run1-50vus"
#
# Output: reports/prom-snapshot-<timestamp>[-label]/

set -euo pipefail

LABEL="${1:-}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUT_NAME="prom-snapshot-${TIMESTAMP}${LABEL:+-$LABEL}"
OUT_DIR="reports/${OUT_NAME}"

echo "==> Triggering Prometheus TSDB snapshot (includes head block)..."
# skip_head=false ensures in-memory (WAL) data is flushed and included.
RESPONSE=$(curl -sf -X POST "http://localhost:9090/api/v1/admin/tsdb/snapshot?skip_head=false")
SNAP_NAME=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['name'])")

echo "    snapshot name: ${SNAP_NAME}"

echo "==> Copying snapshot from container to ${OUT_DIR}..."
docker cp "prometheus:/prometheus/snapshots/${SNAP_NAME}" "${OUT_DIR}"

echo "==> Cleaning up snapshot inside container..."
docker exec prometheus rm -rf "/prometheus/snapshots/${SNAP_NAME}"

echo ""
echo "Snapshot saved to: ${OUT_DIR}"
echo ""
echo "To restore later:"
echo "  ./scripts/prom_restore.sh ${OUT_DIR}"
