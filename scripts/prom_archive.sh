#!/usr/bin/env bash
# Merge a Prometheus TSDB snapshot's blocks into the replay archive (bench-replay-data),
# so a run keeps its full metric surface -- not just what dump.json extracted -- for
# viewing in Grafana's bench-replay dashboard, or the live "the-dashboard" pointed at the
# prometheus-replay datasource, at any time in the future.
#
# Unlike prom_restore.sh, this does NOT wipe the target volume: benchmark runs never
# overlap in time, so TSDB blocks from many runs coexist side by side in one Prometheus,
# and a run is selected in Grafana just by picking its time range. Blocks are copied in
# with `cp -rn` (no-clobber) rather than the volume being replaced.
#
# Usage:  ./scripts/prom_archive.sh <snapshot-dir>
#
# Example:
#   ./scripts/prom_snapshot.sh TO-3_steady_20260806T000000Z
#   ./scripts/prom_archive.sh bench-results/TO-3_steady_20260806T000000Z/prom-snapshot

set -euo pipefail

SNAPSHOT_DIR="${1:-}"
if [[ -z "$SNAPSHOT_DIR" ]]; then
    echo "Usage: $0 <snapshot-dir>"
    echo "Example: $0 bench-results/TO-3_steady_20260806T000000Z/prom-snapshot"
    exit 1
fi

SNAPSHOT_DIR=$(realpath "$SNAPSHOT_DIR")

if [[ ! -d "$SNAPSHOT_DIR" ]]; then
    echo "Error: snapshot directory not found: $SNAPSHOT_DIR"
    exit 1
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
COMPOSE=(docker compose -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.replay.yml")

echo "==> Stopping prometheus-replay (backfilled blocks must not overlap the running head block)..."
"${COMPOSE[@]}" stop prometheus-replay

# Mirrors the try/finally in scripts/replay_run.py: prometheus-replay must come back up
# even if the copy fails partway through, so it never gets left down after this script exits.
restart_replay() {
    echo "==> Starting prometheus-replay..."
    "${COMPOSE[@]}" start prometheus-replay
}
trap restart_replay EXIT

echo "==> Copying blocks from ${SNAPSHOT_DIR} into bench-replay-data (no-clobber)..."
# A Prometheus snapshot contains ALL blocks currently in the TSDB at the moment it was
# taken, not only the run that just finished -- so consecutive per-run snapshots overlap
# heavily (e.g. run 2's snapshot re-contains every block already copied in from run 1's).
# `cp -rn` is what makes that harmless: once a block is written its directory (a ULID) is
# immutable and keeps that same name in every later snapshot, so a block already present
# in bench-replay-data is skipped rather than re-copied or corrupted, and only genuinely
# new blocks (chiefly the still-open head block) actually land. Do not swap this for a
# plain `cp -r`, which would overwrite an already-archived block with a same-named but
# differently-compacted copy.
docker run --rm \
    -v bench-replay-data:/prometheus \
    -v "${SNAPSHOT_DIR}:/snapshot:ro" \
    alpine sh -c 'cp -rn /snapshot/*/ /prometheus/'

echo ""
echo "Archived: ${SNAPSHOT_DIR} -> bench-replay-data"
echo ""
echo "View at:  http://localhost:3000 (datasource uid: prometheus-replay, port 9091)"
