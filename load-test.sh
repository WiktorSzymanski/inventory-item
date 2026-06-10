#!/usr/bin/env bash
# Run a full load test, then save a Prometheus TSDB snapshot and a PDF report.
#
# Usage:
#   ./load-test.sh
#   VUS=50 DURATION=15m ./load-test.sh
#   VUS=50 DURATION=15m ./load-test.sh "es-run1-50vus"   # adds label to snapshot dir name
#
# Prerequisites: docker compose up (infrastructure) must already be running.

set -euo pipefail

DURATION="${DURATION:-10m}"
VUS="${VUS:-10}"
LABEL="${1:-}"

echo "==> Load test  VUS=$VUS  DURATION=$DURATION"
docker compose --profile load-test run --rm \
  -e VUS="$VUS" \
  -e DURATION="$DURATION" \
  k6

echo ""
./scripts/prom_snapshot.sh "$LABEL"
