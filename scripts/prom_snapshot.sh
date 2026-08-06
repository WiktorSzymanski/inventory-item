#!/usr/bin/env bash
# Take a Prometheus TSDB snapshot and copy it out of the container.
# Run from the project root after a load test completes.
#
# Usage:  ./scripts/prom_snapshot.sh [run_id|label]
#
#   If the argument names an existing bench-results/<run_id> directory (i.e. a run
#   produced by k6/bench/bench.sh), the snapshot is written into that run's own
#   directory, alongside dump.json and report.pdf:
#     bench-results/<run_id>/prom-snapshot/
#
#   Otherwise the argument (if any) is treated as a free-form label and the old
#   behaviour applies:
#     reports/prom-snapshot-<timestamp>[-label]/

set -euo pipefail

ARG="${1:-}"

if [[ -n "$ARG" && -d "bench-results/${ARG}" ]]; then
    RUN_ID="$ARG"
    OUT_DIR="bench-results/${RUN_ID}/prom-snapshot"
else
    LABEL="$ARG"
    TIMESTAMP=$(date +%Y%m%d_%H%M%S)
    OUT_NAME="prom-snapshot-${TIMESTAMP}${LABEL:+-$LABEL}"
    OUT_DIR="reports/${OUT_NAME}"
fi

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
if [[ -n "${RUN_ID:-}" ]]; then
    echo "To merge into the replay archive:"
    echo "  ./scripts/prom_archive.sh ${OUT_DIR}"
else
    echo "To restore later:"
    echo "  ./scripts/prom_restore.sh ${OUT_DIR}"
fi
