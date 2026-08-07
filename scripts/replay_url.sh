#!/usr/bin/env bash
# Print (or open) the Grafana URL that shows one archived run.
#
#   scripts/replay_url.sh                                    # the newest run
#   scripts/replay_url.sh bench-results/ES-4_steady_2026...   # a specific run
#   scripts/replay_url.sh --open                              # and open it in a browser
#
# WHY THIS EXISTS. `the-dashboard` defaults to `from=now-15m&to=now` with `refresh=5s`,
# because its first job is watching a run happen. An archived run is a fixed window in the
# past, so opening the dashboard without an explicit time range shows an empty dashboard
# that looks broken — and the 5s refresh keeps sliding the window further away from it.
# Nothing about that failure points at the time range, so it reads as "the archive is
# empty" when the data is perfectly fine.
#
# The window comes from the run's own meta.json (`windows.full`, epoch seconds), which
# covers load plus drain — the drain tail matters, because order_e2e_time is recorded when
# the projection handles the terminal event and under load that lags the load phase.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
. "$HERE/lib.sh"

OPEN=0
RUN_DIR=""
GRAFANA_PORT="${GRAFANA_REPLAY_PORT:-3001}"

while [ $# -gt 0 ]; do
    case "$1" in
        --open|-o)  OPEN=1; shift ;;
        -h|--help)  sed -n '2,20p' "$0" | sed 's/^# \?//'; exit 0 ;;
        -*)         die "unknown option: $1" ;;
        *)          RUN_DIR="$1"; shift ;;
    esac
done

if [ -z "$RUN_DIR" ]; then
    RUN_DIR="$(ls -td "$RESULTS_DIR"/*_*/ 2>/dev/null | head -1 || true)"
    [ -n "$RUN_DIR" ] || die "no runs found in $RESULTS_DIR"
    RUN_DIR="${RUN_DIR%/}"
    log "newest run: $(basename "$RUN_DIR")"
fi

[ -d "$RUN_DIR" ] || die "no such run directory: $RUN_DIR"
[ -f "$RUN_DIR/meta.json" ] || die "$RUN_DIR has no meta.json — not a completed run"

URL="$(python3 - "$RUN_DIR" "$GRAFANA_PORT" <<'PY'
import json, os, sys
run_dir, port = sys.argv[1], sys.argv[2]
with open(os.path.join(run_dir, "meta.json")) as fh:
    meta = json.load(fh)
# windows.full = [T0, T2]: measured load through end of drain.
start, end = meta["windows"]["full"]
print(f"http://localhost:{port}/d/the-dashboard/"
      f"?from={start}000&to={end}000&var-ds=prometheus-replay&refresh=")
PY
)"

# A run whose TSDB was never archived will open to an empty dashboard for a different
# reason, so say so here rather than letting it look like the same problem.
if [ ! -d "$RUN_DIR/prom-snapshot" ]; then
    log "WARNING: $(basename "$RUN_DIR") has no prom-snapshot/ — its TSDB was never captured"
    log "         (run made with --no-snapshot-tsdb?). Only dump.json survives; this URL"
    log "         will show an empty dashboard."
fi

if ! curl -sf -o /dev/null "http://localhost:${GRAFANA_PORT}/api/health" 2>/dev/null; then
    log "note: nothing is serving :${GRAFANA_PORT} — start the replay stack first:"
    log "        COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-iir} docker compose -f docker-compose.replay.yml up -d"
fi

echo "$URL"

if [ "$OPEN" = "1" ]; then
    ( xdg-open "$URL" >/dev/null 2>&1 || open "$URL" >/dev/null 2>&1 ) \
        || log "could not open a browser; copy the URL above"
fi
