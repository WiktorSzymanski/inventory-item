#!/usr/bin/env bash
# Host-side benchmark orchestrator — the supported entry point for the harness.
#
#   SCENARIO=capacity ./k6/bench/bench.sh
#   SCENARIO=steady RATE=60 DURATION=10m ./k6/bench/bench.sh
#   SCENARIO=steady DISTINCT_ITEMS=1 ITEMS_PER_ORDER=1 ./k6/bench/bench.sh
#
# Orchestration lives on the host rather than in the k6 container because that container
# is busybox — no jq, psql or curl — which is exactly why the old run.sh was a two-liner
# that discarded the k6 summary. Host sequencing also removes any need for compose
# depends_on/healthcheck wiring between k6 and the API.
#
# Timeline produced (all epoch seconds):
#   t_reset -> t_seed -> t_warmup -> T0 (settle end) -> T1 (load end) -> T2 (drain end)
# Two measurement windows, because order.e2e.time is recorded by the order-projection
# tracking processor when it handles the terminal event — under saturation that lags the
# load phase by minutes, so a [T0,T1] window silently truncates the slow tail:
#   window_load = [T0, T1]  throughput, CPU, HTTP latency, conflicts, DB growth rate
#   window_full = [T0, T2]  e2e histogram, outcome counts, total DB growth
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=k6/bench/common.sh
. "$HERE/common.sh"

SCENARIO="${SCENARIO:-steady}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-900}"
SETTLE_S="${SETTLE_S:-60}"
SCRAPE_SETTLE_S="${SCRAPE_SETTLE_S:-15}"

TS="$(date -u +%Y%m%dT%H%M%SZ)"

# >>> run-label
# Optional human label, so runs differing only in PAYLOAD_BYTES / RESERVE_DELAY_MS are
# distinguishable in bench-results/ without opening meta.json. meta.json remains the source
# of truth for the config; this is navigation only.
#
# Sanitised because the label becomes a directory name AND is interpolated into the
# container-side OUT_DIR path: an unescaped slash would split the path, a space would split
# the argument. printf '%s' avoids the trailing newline that echo would feed to tr.
LABEL_PART=""
if [ -n "${RUN_LABEL:-}" ]; then
    LABEL_PART="_$(printf '%s' "$RUN_LABEL" | tr -c 'A-Za-z0-9._-' '-')"
fi
# <<< run-label

RUN_NAME="${VARIANT}_${SCENARIO}${LABEL_PART}_${TS}"
RUN_DIR="$REPO_ROOT/bench-results/$RUN_NAME"
CONTAINER_OUT="/bench-results/$RUN_NAME"

log "run $RUN_NAME"

# ---------------------------------------------------------------- preflight
# Everything here runs BEFORE the run directory is created, so a failed precondition
# leaves no empty artifact directory behind.

# Running under sudo is both unnecessary and harmful: docker needs no elevation when the
# invoking user is in the docker group, and root ownership on bench-results/ blocks every
# later non-elevated run from writing its artifacts.
if [ "$(id -u)" = "0" ] && [ "${ALLOW_ROOT:-0}" != "1" ]; then
    die "do not run this under sudo — it makes bench-results/ root-owned and breaks later runs. Add yourself to the docker group instead (set ALLOW_ROOT=1 to override)."
fi

for tool in docker python3 curl git; do
    command -v "$tool" >/dev/null || die "required tool not on PATH: $tool"
done
docker info >/dev/null 2>&1 || die "cannot reach the docker daemon (is it running, and are you in the docker group?)"

if [ -d "$REPO_ROOT/bench-results" ] && [ ! -w "$REPO_ROOT/bench-results" ]; then
    die "bench-results/ is not writable by $(id -un) — likely left root-owned by an earlier sudo run. Fix with: sudo chown -R $(id -un):$(id -gn) '$REPO_ROOT/bench-results'"
fi

# Validated here rather than left to gradle, because a bare `JAVA_HOME=~/...` passed as a
# sudo argument is not tilde-expanded by the shell and arrives as a literal "~/...".
if [ "${SKIP_BUILD:-0}" != "1" ] && [ -n "${JAVA_HOME:-}" ] && [ ! -d "$JAVA_HOME" ]; then
    die "JAVA_HOME points at a non-existent directory: $JAVA_HOME (use \$HOME/... rather than ~/... — tilde is not expanded in that position)"
fi

# ---------------------------------------------------------------- provenance guards
#
# The code under test is the VARIANT's, not this tree's. $REPO_ROOT is two levels above
# k6/bench/, which since the harness moved to `main` is `main` — a tree that holds no
# application code at all. Every provenance fact derived from it therefore describes the
# harness rather than the thing being measured, and two of them are VALIDITY checks in
# evaluate.py:
#
#   image_fresh  compared the image's Created against `main`'s HEAD. The image is built
#                from a variant branch, so those are two unrelated clocks: one docs-only
#                commit on `main` dated every one of the eight images "stale" and a whole
#                campaign returned INVALID x8 after hours of machine time.
#   git_clean    counted changes under src/, which `main` does not have — unconditionally
#                zero, so an uncommitted edit baked into a benchmarked image passed silently.
#
# scripts/run-suite.sh knows which worktree the image came from and exports these four from
# there. The fallbacks are what keeps a direct `./k6/bench/bench.sh` working — including on
# a variant branch, where $REPO_ROOT really is the tree under test and `-- src/` is right.
# Do not "simplify" either half away: neither alone is correct for both callers.
# >>> provenance
GIT_BRANCH="${VARIANT_GIT_BRANCH:-$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)}"
GIT_COMMIT="${VARIANT_GIT_COMMIT:-$(git -C "$REPO_ROOT" rev-parse HEAD)}"
GIT_DIRTY="${VARIANT_GIT_DIRTY:-$(git -C "$REPO_ROOT" status --porcelain -- src/ | wc -l | tr -d ' ')}"
HEAD_EPOCH="${VARIANT_HEAD_EPOCH:-$(git -C "$REPO_ROOT" log -1 --format=%ct)}"
# <<< provenance

if [ "$GIT_DIRTY" != "0" ] && [ "${ALLOW_DIRTY:-0}" != "1" ]; then
    die "the tree the image was built from has $GIT_DIRTY uncommitted change(s) under its build inputs; results would not be reproducible. Commit them in that worktree, or set ALLOW_DIRTY=1."
fi

# Rebuild by default. `image: <tag>:latest` with no build step means a bare
# `git checkout ES-1 && docker compose up` benchmarks whatever was last built — a silent
# corruption footgun that has no other guard.
if [ "${SKIP_BUILD:-0}" = "1" ]; then
    log "build: SKIPPED (SKIP_BUILD=1)"
else
    log "build: ./gradlew bootJar && docker build -t $IMAGE_TAG"
    (cd "$REPO_ROOT" && ./gradlew --quiet bootJar) || die "gradle build failed"
    docker build -q -t "$IMAGE_TAG" "$REPO_ROOT" >/dev/null || die "docker build failed"
fi

# Phase subdirectories are created here, not inside run_k6: the caller redirects into
# "$RUN_DIR/<phase>/k6.log", and the shell opens that redirect BEFORE the function body
# runs, so a mkdir inside the function is always too late.
mkdir -p "$RUN_DIR" "$RUN_DIR/seed" "$RUN_DIR/warmup"
log "artifacts -> bench-results/$RUN_NAME"

IMAGE_ID="$(docker image inspect "$IMAGE_TAG" --format '{{.Id}}' 2>/dev/null || echo unknown)"
IMAGE_CREATED="$(docker image inspect "$IMAGE_TAG" --format '{{.Created}}' 2>/dev/null || echo unknown)"
# HEAD_EPOCH is resolved in the provenance block above, against the tree the image was
# actually built from — not against whatever tree this script happens to live in.
IMAGE_EPOCH="$(date -d "$IMAGE_CREATED" +%s 2>/dev/null || echo 0)"
IMAGE_FRESH=$([ "$IMAGE_EPOCH" -ge "$HEAD_EPOCH" ] && echo true || echo false)

# ---------------------------------------------------------------- stack up + reset
log "stack: bringing up supporting services"
dc up -d "$DB_SVC" prometheus grafana grafana-renderer grafana-reporter \
    postgres-exporter cadvisor >/dev/null

T_RESET="$(date +%s)"
"$HERE/reset.sh"

# No pipe to head: under `set -o pipefail` an early-exiting reader SIGPIPEs the producer
# and fails the whole pipeline. Trim in the shell instead.
K6_VERSION="$(dc run --rm -T --no-deps k6 version 2>/dev/null || echo unknown)"
K6_VERSION="${K6_VERSION%%$'\n'*}"
log "k6: $K6_VERSION"

# ---------------------------------------------------------------- k6 invocation
# Every knob is forwarded with an explicit -e. k6 >= 2.0 dropped system-env forwarding
# into __ENV by default, so relying on the compose `environment:` block would silently
# break on an image bump. Explicit -e is version-proof and makes the run reproducible
# from the recorded argv alone.
KNOBS=(
    SEED DISTINCT_ITEMS ITEMS_PER_ORDER QTY_PER_LINE SEED_QTY PAYLOAD_BYTES
    RESERVE_DELAY_MS
    ITEM_PREFIX ALLOW_DUP_LINES RATE DURATION STEP_START STEP_INC STEP_COUNT
    STEP_RAMP_S STEP_PLATEAU_S STEP_TRIM SPIKE_BASE SPIKE_FACTOR SOAK_DURATION
    WARMUP_ITERATIONS WARMUP_VUS WARMUP_MAX_DURATION READ_RATE READ_MODE
    READ_PAGE_SIZE VU_HEADROOM VU_CEILING
)

# Emits two lines per set knob ("-e", then "KEY=VALUE") and nothing at all when none are
# set. Building an array and printf-ing it would emit one empty line in the empty case,
# which mapfile turns into a single empty argument — and that empty argument reaches
# `k6 run` on the default invocation where no knobs are overridden at all.
k6_env_args() {
    local k
    for k in "${KNOBS[@]}"; do
        if [ -n "${!k:-}" ]; then printf -- '-e\n%s=%s\n' "$k" "${!k}"; fi
    done
}
mapfile -t ENV_ARGS < <(k6_env_args)

# $1 = scenario, $2 = out subdir ("" for the run root), rest = extra k6 args
run_k6() {
    local scen="$1" out="$2"; shift 2
    local host_dir="$RUN_DIR" cont_dir="$CONTAINER_OUT"
    if [ -n "$out" ]; then host_dir="$RUN_DIR/$out"; cont_dir="$CONTAINER_OUT/$out"; fi
    mkdir -p "$host_dir"
    # --user is required: the k6 image runs as uid 12345, which cannot write into a
    # bind-mounted bench-results/ owned by the invoking user. Without it k6 silently fails
    # to emit summary.json and profile.json, which in turn leaves meta.json with no config
    # and no step offsets -- and the capacity staircase cannot be sliced per step at all.
    dc run --rm -T --no-deps --user "$(id -u):$(id -g)" k6 run /scripts/main.js \
        -e "SCENARIO=$scen" \
        -e "RUN_ID=$RUN_NAME" \
        -e "OUT_DIR=$cont_dir" \
        -e "BASE_URL=$BENCH_BASE_URL" \
        ${ENV_ARGS[@]+"${ENV_ARGS[@]}"} \
        "$@"
}

log "seed: creating ${DISTINCT_ITEMS:-6} items"
T_SEED="$(date +%s)"
run_k6 seed seed >"$RUN_DIR/seed/k6.log" 2>&1 || die "seed failed (see $RUN_DIR/seed/k6.log)"

# Warmup is fixed-ITERATION, never fixed-duration. A duration-based warmup hands a fast
# variant proportionally more warmup events, so variants would enter the measured window
# with different event-store depth, snapshot count and cache state — a systematic bias
# against exactly what is under measurement.
log "warmup: ${WARMUP_ITERATIONS:-5000} iterations"
T_WARMUP="$(date +%s)"
run_k6 warmup warmup >"$RUN_DIR/warmup/k6.log" 2>&1 || die "warmup failed (see $RUN_DIR/warmup/k6.log)"
T_WARMUP_END="$(date +%s)"

# Settle: let the warmup backlog clear so the measured window does not open on top of it.
log "settle: draining warmup backlog (cap ${SETTLE_S}s)"
mapfile -t SETTLE < <(drain_wait "$SETTLE_S" || true)
log "settle: backlog=${SETTLE[0]} ${SETTLE[1]} in ${SETTLE[2]}s"

# Then wait for those completions to be SCRAPED before anchoring T0. Draining only
# guarantees the work finished, not that Prometheus has observed it -- and the counters
# read at T0 are whatever the last scrape captured. Without this, warmup completions
# scraped just after T0 land inside the window's e2e delta while their matching 202s sit
# outside it, which drives completion_ratio above 1.0 (measured at 1.42 on a 31s window).
log "settle: waiting ${SCRAPE_SETTLE_S}s for warmup completions to be scraped"
sleep "$SCRAPE_SETTLE_S"

T0="$(date +%s)"

# ---------------------------------------------------------------- measured load
log "load: SCENARIO=$SCENARIO"
K6_EXIT=0
run_k6 "$SCENARIO" "" \
    --out experimental-prometheus-rw \
    --tag "testid=$RUN_NAME" \
    --tag "variant=$VARIANT" \
    2>&1 | tee "$RUN_DIR/k6.log" || K6_EXIT=$?
T1="$(date +%s)"
log "load: complete in $((T1 - T0))s (k6 exit $K6_EXIT)"

# ---------------------------------------------------------------- drain
log "drain: waiting for order backlog to clear (cap ${DRAIN_TIMEOUT}s)"
# drain_wait's exit status is not observable through the process substitution, so the
# drained/timeout verdict is read from its output instead. evaluate.py turns a timeout
# into INVALID, since a truncated e2e histogram is an unusable measurement.
mapfile -t DRAIN < <(drain_wait "$DRAIN_TIMEOUT")
BACKLOG_AT_STOP="${DRAIN[0]}"
DRAIN_STATE="${DRAIN[1]}"
DRAIN_SECONDS="${DRAIN[2]}"
T2="$(date +%s)"
log "drain: backlog_at_stop=$BACKLOG_AT_STOP $DRAIN_STATE in ${DRAIN_SECONDS}s"

# Let the final scrapes land before querying (scrape_interval is 5s).
log "waiting ${SCRAPE_SETTLE_S}s for final Prometheus scrapes"
sleep "$SCRAPE_SETTLE_S"

# ---------------------------------------------------------------- meta.json
python3 - "$RUN_DIR/meta.json" <<PYEOF
import json, os, sys, platform, shutil, subprocess

steps = []
prof = os.path.join("$RUN_DIR", "profile.json")
config = {}
if os.path.exists(prof):
    with open(prof) as fh:
        p = json.load(fh)
    steps = p.get("steps", [])
    config = p.get("config", {})

du = shutil.disk_usage("$REPO_ROOT")
meta = {
    "schema": 1,
    "run_id": "$RUN_NAME",
    "variant": "$VARIANT",
    "variant_family": "${VARIANT_FAMILY:-}",
    "scenario": "$SCENARIO",
    "run_label": "${RUN_LABEL:-}",
    "point": "${POINT_RESOLVED:-${POINT:-}}",
    "branch": "$GIT_BRANCH",
    "commit": "$GIT_COMMIT",
    "git_dirty": int("$GIT_DIRTY"),
    "image_tag": "$IMAGE_TAG",
    "image_id": "$IMAGE_ID",
    "image_created": "$IMAGE_CREATED",
    "image_built_after_head": "$IMAGE_FRESH" == "true",
    "k6_version": """$K6_VERSION""".strip(),
    "prom_job": "$PROM_JOB",
    "db_name": "$DB_NAME",
    "api_container_re": "${API_CONTAINER_RE:-}",
    "expected_replicas": int("${EXPECTED_REPLICAS:-1}"),
    "k6_exit_code": int("$K6_EXIT"),
    "config": config,
    "steps": steps,
    "timeline": {
        "t_reset": int("$T_RESET"), "t_seed": int("$T_SEED"),
        "t_warmup": int("$T_WARMUP"), "t_warmup_end": int("$T_WARMUP_END"),
        "t_settle_end": int("$T0"), "t_load_end": int("$T1"),
        "t_drain_end": int("$T2"),
    },
    "windows": {"load": [int("$T0"), int("$T1")], "full": [int("$T0"), int("$T2")]},
    "drain": {
        "backlog_at_stop": int("$BACKLOG_AT_STOP"),
        "drained": "$DRAIN_STATE" == "drained",
        "drain_seconds": int("$DRAIN_SECONDS"),
    },
    "host": {
        "cpus": os.cpu_count(),
        "kernel": platform.release(),
        "disk_free_gb": round(du.free / 1e9, 1),
    },
}
with open(sys.argv[1], "w") as fh:
    json.dump(meta, fh, indent=2)
print("meta.json written")
PYEOF

# ---------------------------------------------------------------- analysis
log "dump: querying Prometheus"
python3 "$HERE/dump.py" --run-dir "$RUN_DIR" --prom "$PROM_URL" \
    --queries "$HERE/queries.promql" || die "dump.py failed"

log "evaluate: computing verdict"
EVAL_EXIT=0
python3 "$HERE/evaluate.py" --run-dir "$RUN_DIR" \
    --thresholds "$HERE/thresholds.json" || EVAL_EXIT=$?

# ---------------------------------------------------------------- Grafana PDF
# Absolute epoch-ms bounds of window_full. The old `?from=now-${DURATION}&to=now` meant a
# 5m ramp produced a PDF starting at the END of the ramp, and never showing the drain.
log "report: rendering dashboard PDF over window_full"
curl -sf -o "$RUN_DIR/report.pdf" \
    "$REPORTER_URL/api/v5/report/the-dashboard?from=$((T0 * 1000))&to=$((T2 * 1000))" \
    || log "report: PDF render failed (non-fatal)"

log "done -> bench-results/$RUN_NAME"
python3 -c "
import json
v = json.load(open('$RUN_DIR/verdict.json'))
print()
print('  VERDICT: ' + v['verdict'])
for c in v.get('checks', []):
    if not c.get('pass', True):
        print('    x %-32s actual=%s limit=%s' % (c['name'], c.get('actual'), c.get('limit')))
if v.get('knee'):
    print('  KNEE: %s rps (step %s) - %s' % (v['knee'].get('rps'), v['knee'].get('step_index'), v['knee'].get('reason')))
print()
" 2>/dev/null || true

exit "$EVAL_EXIT"
