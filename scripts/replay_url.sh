#!/usr/bin/env bash
# Print (or open) the Grafana URL that shows one archived run.
#
#   scripts/replay_url.sh                                     # the newest run
#   scripts/replay_url.sh bench-results/ES-4_steady_2026...   # a specific run
#   scripts/replay_url.sh --open                              # and open it in a browser
#
# WHY THIS EXISTS. `bench-runs` already has a dropdown of every archived run, so this is a
# shortcut rather than the only way in: it saves picking the run you just finished out of a
# dropdown of thirty, and gives you a URL to paste into notes.
#
# The `run` variable's VALUE is the run's distance from the dashboard's fixed anchor, applied
# as a PromQL offset — so that is what goes in the URL, computed from the run's own meta.json
# (`windows.full`, epoch seconds) exactly the way scripts/dashboards/runs.py computes it.
# The time range is deliberately NOT set: bench-runs pins it to the anchor window and the
# offset does the rest.
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

OUT="$(python3 - "$RUN_DIR" "$GRAFANA_PORT" "$MAIN_ROOT" <<'PY'
import json, os, sys
run_dir, port, repo_root = sys.argv[1], sys.argv[2], sys.argv[3]
# `python3 -` has no __file__, so the repo root arrives as an argument rather than being
# derived here; it is what makes `scripts.dashboards.runs` importable.
sys.path.insert(0, repo_root)
from scripts.dashboards.runs import ANCHOR_EPOCH

with open(os.path.join(run_dir, "meta.json")) as fh:
    meta = json.load(fh)
# windows.full = [T0, T2]: measured load through end of drain; runs.py anchors on T0. The
# anchor is imported rather than repeated so a stale copy here cannot drift out of step.
start, _end = meta["windows"]["full"]
offset = f"{ANCHOR_EPOCH - int(start)}s"

# bench-runs' dropdown is a list baked into the JSON at `build --runs` time, so a run that was
# never scanned into it has no option to select -- and Grafana responds to an unknown
# `var-run` by falling back to the first option, drawing a DIFFERENT run's data under this
# run's name with nothing on screen to say so. Checking here turns that into a message.
dash = os.path.join(repo_root, "monitoring/grafana/provisioning/dashboards/bench-runs.json")
known = set()
if os.path.exists(dash):
    with open(dash) as fh:
        for var in json.load(fh)["templating"]["list"]:
            if var["name"] == "run":
                known = {o["value"] for o in var["options"]}

print(f"http://localhost:{port}/d/bench-runs/?var-run={offset}")
print("known" if offset in known else "unknown")
PY
)"
URL="$(echo "$OUT" | sed -n 1p)"
IN_DROPDOWN="$(echo "$OUT" | sed -n 2p)"

if [ "$IN_DROPDOWN" != "known" ]; then
    log "WARNING: this run is not in bench-runs.json's Run dropdown, so the URL will land on"
    log "         whichever run is first. Rebuild the dashboard from the same directory the"
    log "         archive was loaded from:"
    log "           python3 -m scripts.dashboards.build --runs $RESULTS_DIR"
fi

# The likeliest reason for the warning above: scan_runs() skips a run with no prom-snapshot/,
# so no rebuild will ever put this one in the dropdown.
if [ ! -d "$RUN_DIR/prom-snapshot" ]; then
    log "         $(basename "$RUN_DIR") has no prom-snapshot/ — its TSDB was never captured"
    log "         (run made with --no-snapshot-tsdb?), so rebuilding will not add it."
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
