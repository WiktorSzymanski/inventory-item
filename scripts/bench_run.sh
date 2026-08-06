#!/usr/bin/env bash
# k6/bench/bench.sh, plus a Prometheus TSDB copy that survives the next
# `docker compose down -v`.
#
# Same interface as bench.sh -- every knob is an environment variable and is inherited
# straight through, so an existing command only changes which script it names:
#
#   JAVA_HOME=$HOME/.jdks/corretto-21.0.10 SCENARIO=capacity RESERVE_DELAY_MS=25 \
#     STEP_START=10 STEP_INC=20 STEP_COUNT=8 DRAIN_TIMEOUT=3600 ./scripts/bench_run.sh
#
# WHY A WRAPPER, not a step inside bench.sh: everything under k6/ must stay byte-identical
# across all eight variant branches, and bench.sh must never start, stop or depend on the
# replay stack. This lives in scripts/, runs only after bench.sh has exited, and reaches
# the live prometheus container solely through its admin API.
#
# WHAT IT PRESERVES. bench.sh keeps no TSDB of its own: the raw series live in the
# `prometheus-data` volume, which the next `docker compose down -v` destroys. Only
# dump.json's ~20 extracted series and report.pdf survive on their own, which is 20 of the
# merged dashboard's 56 panels. This script makes two copies, both immune to `down -v`:
#
#   bench-results/<run_id>/prom-snapshot/   host directory, beside dump.json and report.pdf
#   bench-replay-data                        external docker volume, for Grafana replay
#
# The host copy is the durable one -- it is an ordinary directory in the repository, so
# neither `down -v` nor a later bench.sh run can touch it. The volume copy is what makes a
# run viewable: point `the-dashboard`'s "Data source" dropdown at "Prometheus Replay" and
# set the time picker to the run's windows.full from meta.json. See docs/bench-replay.md.
#
# Exit code is bench.sh's own (0 PASS, 1 FAIL, 2 INVALID) -- the snapshot is taken either
# way, because a FAIL or INVALID run is exactly the one whose metrics you need to look at.
#
# Knobs of its own:
#   ARCHIVE_TSDB=0   take the host-side snapshot but skip the replay-volume merge
#   SNAPSHOT_TSDB=0  skip both (equivalent to calling bench.sh directly)
#
# The snapshot needs Prometheus's admin API, which is enabled by `--web.enable-admin-api` in
# docker-compose.bench.yml. bench.sh always brings Prometheus up through that overlay, so it
# is active for any run started here; a Prometheus brought up from docker-compose.yml alone
# would answer the snapshot request with 404.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
cd "$REPO_ROOT"   # prom_snapshot.sh resolves bench-results/<run_id> relative to cwd

newest_run_dir() {
    ls -td "$REPO_ROOT"/bench-results/*/ 2>/dev/null | head -1
}

BEFORE="$(newest_run_dir || true)"

BENCH_EXIT=0
./k6/bench/bench.sh || BENCH_EXIT=$?

if [ "${SNAPSHOT_TSDB:-1}" != "1" ]; then
    exit "$BENCH_EXIT"
fi

# bench.sh creates the run directory early and nothing else writes to bench-results/, so
# "newest directory, and different from the one that was newest before" identifies this
# run's output. Comparing against BEFORE matters: a preflight failure (dirty tree, missing
# JAVA_HOME, unreachable docker) exits before mkdir, and without the comparison the
# snapshot would be filed under the PREVIOUS run's directory and silently corrupt it.
AFTER="$(newest_run_dir || true)"
if [ -z "$AFTER" ] || [ "$AFTER" = "$BEFORE" ]; then
    echo "bench_run: no new run directory — bench.sh failed before creating one; skipping snapshot" >&2
    exit "$BENCH_EXIT"
fi

RUN_ID="$(basename "$AFTER")"
SNAP_DIR="bench-results/$RUN_ID/prom-snapshot"

echo ""
echo "==> bench_run: preserving the Prometheus TSDB for $RUN_ID"

# Non-fatal on purpose. The run's own artifacts (meta.json, dump.json, verdict.json,
# report.pdf) are already written and valid; losing the snapshot costs dashboard fidelity
# later, not the measurement itself. Failing the whole invocation here would wrongly
# suggest the run is unusable.
if ! ./scripts/prom_snapshot.sh "$RUN_ID"; then
    echo "bench_run: TSDB snapshot FAILED — the run's own artifacts are intact, but this" >&2
    echo "           run will only be replayable from dump.json (~20 of 56 panels)." >&2
    exit "$BENCH_EXIT"
fi

if [ "${ARCHIVE_TSDB:-1}" = "1" ]; then
    if ! ./scripts/prom_archive.sh "$SNAP_DIR"; then
        echo "bench_run: replay-archive merge FAILED — the host-side snapshot at" >&2
        echo "           $SNAP_DIR is intact and can be merged later with:" >&2
        echo "           ./scripts/prom_archive.sh $SNAP_DIR" >&2
    fi
fi

echo ""
echo "  run:      bench-results/$RUN_ID"
echo "  snapshot: $SNAP_DIR ($(du -sh "$SNAP_DIR" 2>/dev/null | cut -f1 || echo '?'))"
echo "  archive:  bench-replay-data (external volume — survives docker compose down -v)"
echo ""

exit "$BENCH_EXIT"
