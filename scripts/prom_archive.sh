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

REPLAY_CONTAINER=prometheus-replay

# Plain `docker`, not `docker compose`, and deliberately so — the same choice
# scripts/replay_run.py makes for the same operation:
#
#   * docker-compose.yml declares `image: ${IMAGE_TAG:?...}`, so ANY compose invocation
#     naming it fails outright unless IMAGE_TAG happens to be set. Nothing about archiving
#     TSDB blocks knows or cares which variant's image exists, and run-suite.sh calls this
#     in a subshell where IMAGE_TAG is not set. That is why this step failed on every run.
#   * A compose invocation also only sees containers in ITS project. prometheus-replay is
#     brought up by hand (see docs/bench-replay.md) and may well carry a different project
#     name than the suite's `iir`, in which case `compose stop` would find nothing, report
#     success, and leave blocks being copied in underneath a live head block. Matching on
#     the container_name that docker-compose.replay.yml pins cannot miss it.
running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$REPLAY_CONTAINER" 2>/dev/null || true)" = "true" ]
}

WAS_RUNNING=0
if running; then
    WAS_RUNNING=1
    echo "==> Stopping ${REPLAY_CONTAINER} (backfilled blocks must not overlap the running head block)..."
    docker stop "$REPLAY_CONTAINER" >/dev/null
else
    # Not an error, and not something to fix by starting it: with no process attached to the
    # volume there is no head block to overlap, so the copy below is safe as-is. Starting it
    # here would also make this script a stack-management tool, which it is not.
    echo "==> ${REPLAY_CONTAINER} is not running; copying straight into the volume."
fi

# Mirrors the try/finally in scripts/replay_run.py: prometheus-replay must come back up even
# if the copy fails partway through, so it is never left down after this script exits. Only
# restarts what this script stopped.
restart_replay() {
    [ "$WAS_RUNNING" = "1" ] || return 0
    echo "==> Starting ${REPLAY_CONTAINER}..."
    docker start "$REPLAY_CONTAINER" >/dev/null
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
if [ "$WAS_RUNNING" = "0" ]; then
    # COMPOSE_PROJECT_NAME must match the benchmark stack's, so prometheus-replay joins the
    # same default network and Grafana can resolve it by name. Do NOT add
    # `-f docker-compose.yml`: it demands IMAGE_TAG and contributes nothing here.
    echo "          (start it first: COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-iir} \\"
    echo "           docker compose -f docker-compose.replay.yml up -d ${REPLAY_CONTAINER})"
fi
