#!/usr/bin/env bash
# Run the benchmark on every variant, one after another, from `main`.
#
#   SCENARIO=steady RATE=60 DURATION=10m scripts/run-suite.sh
#   scripts/run-suite.sh --only ES-4,TO-1
#   SCENARIO=capacity scripts/run-suite.sh --continue-on-fail
#
# Every benchmark knob is the one bench.sh already understands — SCENARIO, RATE, DURATION,
# DISTINCT_ITEMS, ITEMS_PER_ORDER, QTY_PER_LINE, PAYLOAD_BYTES, RESERVE_DELAY_MS,
# WARMUP_ITERATIONS, STEP_*, DRAIN_TIMEOUT, ... — and is inherited straight through. This
# script adds no knob vocabulary of its own; it decides WHICH variants run and in what
# order, and guarantees each one starts from a clean stack. Knobs can also be pinned for a
# whole campaign in workload.env, which this sources; the shell environment still wins.
#
# PAYLOAD_BYTES and RESERVE_DELAY_MS are both honoured on all eight branches. If that ever
# stops being true the failure is silent — k6 sends the field regardless, Spring ignores
# unknown properties, and meta.json records the requested value rather than what the server
# applied — so the suite warns for any selected variant missing the capability in
# variants.env. Both levers are held under the row/aggregate lock, so lower RATE when
# raising them.
#
# Options:
#   --only V1,V2         subset of variants.env (validated; order still follows the registry)
#   --no-build           use the images as they are; do not rebuild even if stale
#   --continue-on-fail   run every variant even after one fails, and report at the end
#   --no-snapshot-tsdb   skip preserving the raw Prometheus TSDB (kept by default)
#   --no-archive-tsdb    keep the host-side snapshot but skip the replay-volume merge
#
# TSDB preservation is on by default, and it has to be: `down -v` between variants destroys
# the prometheus-data volume, and dump.json only extracts ~20 of the merged dashboard's 56
# panels. Two copies are made, both immune to `down -v` — bench-results/<run_id>/prom-snapshot/
# (an ordinary host directory) and the external `bench-replay-data` volume (for Grafana
# replay). SNAPSHOT_TSDB=0 / ARCHIVE_TSDB=0 work too, matching bench_run.sh's knob names.
#
# WHY SEQUENTIAL. All eight variants publish the same host ports (8080 nginx, 9090
# prometheus, 3000 grafana, 5432 postgres), so they physically cannot overlap — and even if
# they could, sharing a machine would make every number a measure of contention with the
# neighbour rather than of the variant.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
. "$HERE/lib.sh"

ONLY=""
NO_BUILD=0
CONTINUE=0
# TSDB preservation is ON by default, matching scripts/bench_run.sh on TO-3, whose contract
# the campaign runbook is written against. Same two knob names, so a runbook command that
# sets them keeps working.
SNAPSHOT_TSDB="${SNAPSHOT_TSDB:-1}"
ARCHIVE_TSDB="${ARCHIVE_TSDB:-1}"
while [ $# -gt 0 ]; do
    case "$1" in
        --only|-o)          ONLY="$2"; shift 2 ;;
        --no-build)         NO_BUILD=1; shift ;;
        --continue-on-fail) CONTINUE=1; shift ;;
        --no-snapshot-tsdb) SNAPSHOT_TSDB=0; shift ;;
        --no-archive-tsdb)  ARCHIVE_TSDB=0; shift ;;
        -h|--help)          sed -n '2,36p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *)                  die "unknown argument: $1" ;;
    esac
done

require_not_root
require_tools
assert_ports_free

# Order matters. The shell snapshot must be taken before any point expands, because
# afterwards a point-set knob and a shell-set knob are indistinguishable. workload.env is
# sourced afterwards and uses the ${VAR:-value} form throughout, so it fills only what is
# still unset and can never silently contradict a named point.
snapshot_shell_knobs
resolve_point

# Must run before the first bench.sh invocation: bench.sh resolves its own REPO_ROOT to
# MAIN_ROOT and writes there, while this script looks for the run under RESULTS_DIR.
ensure_results_link

# The archive volume is declared `external: true`, so Compose will not create it and the
# merge would fail on a fresh machine. Creating it is a no-op when it already exists.
if [ "$SNAPSHOT_TSDB" = "1" ] && [ "$ARCHIVE_TSDB" = "1" ]; then
    docker volume create bench-replay-data >/dev/null 2>&1 || true
fi

# Sticky knobs. Every assignment in that file is written `VAR="${VAR:-value}"`, so anything
# already in the environment wins and a one-off override on the command line still works.
if [ -f "$MAIN_ROOT/workload.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$MAIN_ROOT/workload.env"
    set +a
fi

VARIANTS="$(select_variants "$ONLY")"
SCENARIO="${SCENARIO:-steady}"

warn_unsupported_knobs "$VARIANTS"
mkdir -p "$RESULTS_DIR"

[ -w "$RESULTS_DIR" ] || die \
    "bench-results/ is not writable by $(id -un) — likely left root-owned by an earlier sudo run. Fix with: sudo chown -R $(id -un):$(id -gn) '$RESULTS_DIR'"

log "suite: scenario=$SCENARIO variants=$(tr '\n' ' ' <<<"$VARIANTS")"

# Verdict codes are bench.sh's own: 0 PASS, 1 FAIL, 2 INVALID.
declare -A VERDICT RUNDIR

# The image is stale if the manifest has no record of it, the recorded commit no longer
# matches the branch head, or the tag has since been deleted from the daemon.
image_is_current() {
    local variant="$1" wt
    wt="$(worktree_path "$variant")"
    [ -d "$wt" ] || return 1          # never built: no worktree yet
    python3 - "$RESULTS_DIR/images.json" "$variant" "$(git -C "$wt" rev-parse HEAD)" \
             "$(image_tag "$variant")" <<'PY'
import json, subprocess, sys
manifest, variant, head, tag = sys.argv[1:5]
try:
    rec = json.load(open(manifest))["images"][variant]
except Exception:
    sys.exit(1)
if rec.get("commit") != head:
    sys.exit(1)
sys.exit(subprocess.call(["docker", "image", "inspect", tag],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
PY
}

run_one() {
    local variant="$1" rc=0 run_dir

    # Clean slate before this variant starts, not after the previous one finished — so an
    # aborted earlier suite, or a stack left running by hand, is cleared too.
    teardown

    if [ "$NO_BUILD" = "0" ] && ! image_is_current "$variant"; then
        log "$variant: image missing or behind branch head — building"
        "$HERE/build-images.sh" --only "$variant"
    fi

    log "=== $variant: $SCENARIO ${POINT_RESOLVED:+($POINT_RESOLVED)} ==="

    # main's own harness, against this variant's image. No worktree, no branch switch:
    # the image is the only thing that differs between variants.
    (
        cd "$MAIN_ROOT"
        VARIANT="$variant" \
        VARIANT_FAMILY="$(family_of "$variant")" \
        IMAGE_TAG="$(image_tag "$variant")" \
        SKIP_BUILD=1 \
        ./k6/bench/bench.sh
    ) || rc=$?

    case "$rc" in
        0) VERDICT[$variant]="PASS" ;;
        1) VERDICT[$variant]="FAIL" ;;
        2) VERDICT[$variant]="INVALID" ;;
        *) VERDICT[$variant]="ERROR($rc)" ;;
    esac

    # Newest run directory for this variant. The label sits between scenario and
    # timestamp, so the glob must tolerate it.
    run_dir="$(ls -td "$RESULTS_DIR/${variant}_${SCENARIO}"*  2>/dev/null | head -1 || true)"
    RUNDIR[$variant]="${run_dir:-(none)}"

    # BEFORE teardown: `down -v` destroys prometheus-data and with it every raw series.
    # What survives unaided is dump.json's ~20 extracted series, against the merged
    # dashboard's 56 panels. Non-fatal throughout — the run's own artifacts are already
    # written and valid.
    if [ "$SNAPSHOT_TSDB" = "1" ] && [ -n "$run_dir" ]; then
        local snap_dir="bench-results/$(basename "$run_dir")/prom-snapshot"
        if ! ( cd "$MAIN_ROOT" && ./scripts/prom_snapshot.sh "$(basename "$run_dir")" >/dev/null ); then
            log "$variant: TSDB snapshot FAILED — run artifacts intact, but this run will"
            log "           only ever replay from dump.json (~20 of 56 panels)"
        else
            log "$variant: TSDB -> $snap_dir"
            if [ "$ARCHIVE_TSDB" = "1" ]; then
                ( cd "$MAIN_ROOT" && ./scripts/prom_archive.sh "$snap_dir" >/dev/null ) \
                    || log "$variant: replay-archive merge failed — the host snapshot is intact and can be merged later with: ./scripts/prom_archive.sh $snap_dir"
            fi
        fi
    fi

    log "$variant: ${VERDICT[$variant]}"
    [ "$rc" = "0" ] || [ "$CONTINUE" = "1" ] || return "$rc"
    return 0
}

SUITE_RC=0
for v in $VARIANTS; do
    run_one "$v" || { SUITE_RC=$?; break; }
done

teardown

# ---------------------------------------------------------------- report
echo
printf '  %-8s  %-9s  %s\n' VARIANT VERDICT RUN
printf '  %-8s  %-9s  %s\n' -------- --------- ---
for v in $VARIANTS; do
    printf '  %-8s  %-9s  %s\n' "$v" "${VERDICT[$v]:-not-run}" \
        "$(basename "${RUNDIR[$v]:-}" 2>/dev/null)"
done
echo
echo "  compare:  python3 scripts/compare.py bench-results/*_${SCENARIO}_*"
echo

for v in $VARIANTS; do
    case "${VERDICT[$v]:-not-run}" in
        PASS|SKIPPED) ;;
        *) SUITE_RC=1 ;;
    esac
done
exit "$SUITE_RC"
