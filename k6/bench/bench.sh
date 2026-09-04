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
# `spike` fires a burst into an idle system and then stops sending entirely, so the whole
# backlog it built has to clear inside the drain phase — that IS the measurement, and 900s
# is not enough for it at a peak worth calling a spike. A drain that times out reports
# INVALID (require_backlog_drained holds for every scenario but `stress`), which would fail
# the run for the exact behaviour it was asked to produce. Every other scenario keeps 900s.
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-$([ "$SCENARIO" = "spike" ] && echo 1800 || echo 900)}"
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

# >>> point-guard
# A point is expanded by resolve_point() in scripts/lib.sh, which scripts/run-suite.sh calls
# — the single resolution path, and the only place that knows points.env exists. bench.sh
# never expands one. So POINT arriving here without POINT_RESOLVED means the knobs it names
# were NEVER applied: `POINT=W-hot ./k6/bench/bench.sh` ran at config.js's defaults
# (DISTINCT_ITEMS=6, ITEMS_PER_ORDER=4) while meta.json claimed `point: W-hot`, which means 8
# — and compare.py then grouped that run with genuine W-hot runs. Refusing is better than
# recording nothing, because the run itself is not the one the operator asked for.
if [ -n "${POINT:-}" ] && [ -z "${POINT_RESOLVED:-}" ]; then
    die "POINT=$POINT was never resolved. Points are expanded by scripts/run-suite.sh (points.env), not by bench.sh, so this run would use the default workload while recording '$POINT' in meta.json. Use: POINT=$POINT scripts/run-suite.sh --only \$VARIANT — or set DISTINCT_ITEMS/ITEMS_PER_ORDER/PAYLOAD_BYTES/RESERVE_DELAY_MS here by hand and drop POINT."
fi
# <<< point-guard
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

# >>> service-logs-trap
# Installed BEFORE reset.sh rather than called after the run, because the failure modes
# that most need the service's own log all exit through `die`: reset.sh's health timeout,
# a failed seed or warmup, a drain that never clears. An EXIT trap covers those and the
# successful path in one line. (The cadvisor check below is advisory and no longer among
# them, but it used to be — see bench-results-10 for what that cost.)
#
# $T_RESET is the window anchor and is set immediately above — see capture_service_logs for
# why an unscoped dump would fold the previous run's output into this archive.
#
# The trap must not change the exit status: capture_service_logs always returns 0 and never
# calls exit, so bash carries bench.sh's own status through to the caller. run-suite.sh
# reads that status to classify the run PASS/FAIL/INVALID, and it tears the stack down
# afterwards — so this has to happen here, while the containers still exist.
trap 'capture_service_logs "$RUN_DIR/logs" "$T_RESET"' EXIT
# <<< service-logs-trap

"$HERE/reset.sh"

# ---------------------------------------------------------------- network delay (optional)
# >>> netem
# Datacenter RTT on the api -> postgres hop, when DB_NET_DELAY asks for one. Unset is the
# default and installs nothing at all: no qdisc, no sidecar, a stack identical to the one that
# existed before the netem service did. See docker-compose.yml for the mechanism, and for why
# the knob is the whole added RTT rather than one leg of it.
#
#     DB_NET_DELAY=500us scripts/run-suite.sh --only TO-1
#
# AFTER reset.sh, deliberately. Flyway's migrations and the whole Spring context start up
# unshaped, so a 50ms setting cannot push a cold start past reset.sh's 180s HEALTH_TIMEOUT and
# fail the run for a reason that has nothing to do with the measurement. Everything that IS
# measured -- seed, warmup, settle, load, drain -- runs behind the delay. Installing it here also
# means it survives reset.sh's api restart, which never touches the postgres netns the qdisc
# lives in.
#
# The guards below exist because every failure mode here is SILENT:
#
#   * A bare number is NANOSECONDS to tc. DB_NET_DELAY=100 installs `delay 100ns` -- a no-op
#     that a run would record as "100" and that nothing downstream would question. So the unit
#     is required, and its absence is refused.
#   * `docker compose up -d netem` reports "Started" even when tc rejects the value and the
#     container dies a moment later; the exit status says nothing. So the qdisc is read back out
#     of the namespace and the RTT is measured either side of installing it. What reaches
#     meta.json is what was observed, not what was asked for.
DB_NET_DELAY="${DB_NET_DELAY:-}"
DB_NET_JITTER="${DB_NET_JITTER:-}"
NETEM_QDISC=""
NETEM_RTT_BEFORE=""
NETEM_RTT_AFTER=""

# A tc time string -> milliseconds; non-zero exit when it carries no unit or is malformed.
netem_ms() {
    python3 - "$1" <<'PYNETEM'
import re, sys
m = re.fullmatch(r'\s*([0-9]+(?:\.[0-9]+)?)\s*(us|usec|ms|msec|s|sec|secs)\s*', sys.argv[1])
if not m:
    sys.exit(1)
v, u = float(m.group(1)), m.group(2)
print(v / 1000.0 if u.startswith('us') else v if u.startswith('ms') else v * 1000.0)
PYNETEM
}

# Median TCP-connect time to postgres from the HOST, in ms. Not the api -> postgres path itself
# -- the api's traffic never leaves the bridge -- but it crosses the very same egress qdisc, so
# it witnesses that the shaping is in force and roughly the right size. Addressed by container
# IP rather than through the published port, which would put docker-proxy's userland hop into
# every sample.
db_rtt_ms() {
    python3 - "$1" <<'PYNETEM' 2>/dev/null || true
import socket, statistics, sys, time
host, xs = sys.argv[1], []
for _ in range(21):
    s = socket.socket(); s.settimeout(5)
    t = time.perf_counter()
    try:
        s.connect((host, 5432))
    except OSError:
        sys.exit(1)
    xs.append((time.perf_counter() - t) * 1000)
    s.close()
print("%.3f" % statistics.median(xs))
PYNETEM
}

# Recreate the sidecar with the knob empty -- which deletes the qdisc and installs nothing --
# prove the namespace is unshaped, then remove the container. Echoes what tc reported.
netem_clear() {
    local q
    DB_NET_DELAY="" DB_NET_JITTER="" dc up -d --force-recreate netem >/dev/null \
        || die "netem: could not recreate the sidecar to clear its qdisc"
    q="$(dc exec -T netem tc qdisc show dev eth0 2>/dev/null | tr -d '\r' | head -1)"
    case "$q" in
        *netem*) die "netem: a stale qdisc is STILL in force on $DB_SVC ($q). This run would be shaped without recording it. Clear it with: docker compose down -v" ;;
    esac
    dc rm -sf netem >/dev/null 2>&1 || true
    printf '%s' "$q"
}

# A sidecar left behind by an earlier run, cleared before anything else happens. teardown()
# removes it between variants, so this can only be a repeated direct bench.sh -- but there it is
# load-bearing twice over. An unshaped run would otherwise inherit the previous run's delay and
# record nothing about it: `up -d postgres prometheus ...` names the services it wants and so
# never touches this one. And a run LOWERING the delay would measure its "before" RTT against
# the old shaping, see the number fall, and abort on the delta guard below.
if docker inspect netem >/dev/null 2>&1; then
    log "netem: a netem container is left over from an earlier run — clearing its qdisc first"
    # Assigned, not inlined into the log argument. netem_clear can die, and a `die` inside a
    # command substitution exits only the SUBSHELL: as an argument to `log` its non-zero status
    # is discarded by the simple command that expanded it, and the run would carry on shaped.
    # As the right-hand side of an assignment the status is the assignment's own, so set -e sees
    # it.
    STALE_QDISC="$(netem_clear)"
    log "netem: cleared — $DB_SVC egress is unshaped ($STALE_QDISC)"
fi

if [ -n "$DB_NET_DELAY" ]; then
    DB_NET_DELAY_MS="$(netem_ms "$DB_NET_DELAY")" || die \
"DB_NET_DELAY='$DB_NET_DELAY' is not a tc time with a unit (500us, 2ms, 50ms). The unit is required rather than defaulted because a bare number is NANOSECONDS to tc: DB_NET_DELAY=2 installs 'delay 2ns' and shapes nothing at all."
    if [ -n "$DB_NET_JITTER" ]; then
        netem_ms "$DB_NET_JITTER" >/dev/null || die \
"DB_NET_JITTER='$DB_NET_JITTER' is not a tc time with a unit (500us, 2ms, ...). Same nanosecond trap as DB_NET_DELAY."
    fi

    DB_IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
             "$DB_SVC" 2>/dev/null || true)"
    [ -n "$DB_IP" ] || die "netem: cannot resolve the '$DB_SVC' container IP; is the stack up?"

    NETEM_RTT_BEFORE="$(db_rtt_ms "$DB_IP")"
    log "netem: installing delay=$DB_NET_DELAY jitter=${DB_NET_JITTER:-none} on $DB_SVC egress"
    export DB_NET_DELAY DB_NET_JITTER
    dc up -d netem >/dev/null || die "netem: the delay sidecar would not start"

    NETEM_QDISC="$(dc exec -T netem tc qdisc show dev eth0 2>/dev/null | tr -d '\r' | head -1)"
    case "$NETEM_QDISC" in
        *netem*delay*) ;;
        *) die "netem: no netem qdisc on $DB_SVC after asking for delay=$DB_NET_DELAY.
   tc reported: ${NETEM_QDISC:-<the sidecar is not running>}
   Its own output says why:  docker compose logs netem" ;;
    esac

    NETEM_RTT_AFTER="$(db_rtt_ms "$DB_IP")"
    # Half the requested delay is a deliberately loose floor: it is proving that the shaping is
    # REAL and of the right order, not calibrating it. netem's own scheduling overhead pushes the
    # measured figure slightly ABOVE the request (2ms asked, ~2.03ms seen), so the interesting
    # direction is a value that barely moved.
    python3 - "${NETEM_RTT_BEFORE:-0}" "${NETEM_RTT_AFTER:-0}" "$DB_NET_DELAY_MS" <<'PYNETEM' || die \
"netem: the qdisc is installed but the measured RTT did not move by anything like $DB_NET_DELAY (see the line above). Treat this run as unshaped."
import sys
before, after, want = (float(x) for x in sys.argv[1:4])
got = after - before
print("[netem] RTT %.3f -> %.3f ms (+%.3f, asked for +%.3f)" % (before, after, got, want),
      file=sys.stderr)
sys.exit(0 if got >= 0.5 * want else 1)
PYNETEM
    log "netem: $NETEM_QDISC"
fi
# <<< netem

# ---------------------------------------------------------------- cadvisor check (advisory)
#
# Cannot run before "$RUN_DIR" is created: cadvisor can only be asked about the api
# container once reset.sh has started it and waited for health.
#
# The 2026-08-22 campaign is why this exists. For the entire lifetime of one Docker daemon,
# cadvisor attributed no cgroup to any container: it published ~110 host cgroup series with
# name="" and nothing else. It stayed `up`, container_scrape_error stayed 0, and it was
# recreated at the start of all 14 runs without ever recovering — only a daemon restart
# cleared it. So neither `up` nor the scrape-error counter is the signal, and restarting
# cadvisor is not a reliable remedy; the only thing worth checking is that the two
# containers whose panels this run has to record are actually named in the exposition.
#
# ADVISORY, NOT FATAL, as of 2026-08-25. This block used to `die`, on the reasoning that an
# hour of machine time is worth less than a run whose container panels are unusable. What
# that actually bought was bench-results-10: three capacity runs killed 161 s in — 60 s
# wait, one cadvisor restart, 90 s wait, die — against a daemon that had been blind since
# the previous day. The API, Postgres, JVM and k6 numbers would all have been sound; only
# the container CPU/memory/network panels were ever at risk. The rest of the run is worth
# having, so it proceeds and the operator is told loudly instead.
#
# What that costs is the reason the guard existed in the first place: a blind cadvisor is
# SILENT downstream. --docker_only at least keeps the host cgroups out of the exposition,
# so the container panels render nothing rather than plausible whole-machine numbers — but
# nothing inside the run directory records that the collector was blind. The warning below
# lives in this run's stdout and nowhere else, so a run archived from a scrolled-past
# terminal is indistinguishable from a healthy one. Read the warning, or check the snapshot:
#
#   strings <run>/prom-snapshot/*/index | grep -c '^/docker/'   # 0 means blind
CADVISOR_URL="${CADVISOR_URL:-http://localhost:8082}"

# >>> cadvisor-probe
# Reads a cadvisor /metrics body on stdin; succeeds only when both containers are named.
#
# The name label is extracted and then matched with `grep -x`, rather than grepped for
# inside the raw line, for two reasons. Prometheus anchors `name=~` fully, so `postgres`
# must not be satisfied by `postgres-exporter` — a guard looser than the queries it protects
# would pass runs whose panels still render nothing. And cadvisor decorates every series
# with container_label_* labels carrying arbitrary values, including the `name` label that
# many images ship (exposed as container_label_name), so a substring search for name="api"
# can be satisfied by a collector that sees no containers at all.
#
# API_CONTAINER_RE is a regex everywhere else it is consumed (`name=~"$apic"`), so it is
# applied as one here too.
cadvisor_sees_containers() {
    local names
    names="$(sed -n 's/^container_memory_rss{.*[,{]name="\([^"]*\)".*/\1/p')"
    printf '%s\n' "$names" | grep -qxE "$DB_SVC" || return 1
    printf '%s\n' "$names" | grep -qxE "$API_CONTAINER_RE" || return 1
    return 0
}
# <<< cadvisor-probe

cadvisor_ready() {
    local deadline=$(( SECONDS + $1 ))
    while :; do
        if curl -sf --max-time 5 "$CADVISOR_URL/metrics" 2>/dev/null | cadvisor_sees_containers
        then
            return 0
        fi
        [ "$SECONDS" -ge "$deadline" ] && return 1
        sleep 3
    done
}

cadvisor_ok() { log "cadvisor: per-container series present for '$DB_SVC' and '$API_CONTAINER_RE'$1"; }

if cadvisor_ready "${CADVISOR_WAIT:-60}"; then
    cadvisor_ok ""
else
    log "cadvisor: no per-container series yet — restarting it once"
    dc restart cadvisor >/dev/null 2>&1 || true
    if cadvisor_ready "${CADVISOR_WAIT_RETRY:-90}"; then
        cadvisor_ok " (after restart)"
    else
        log \
"WARNING: cadvisor is reachable but reports no per-container series for '$DB_SVC' and
'$API_CONTAINER_RE'. THE RUN CONTINUES — every artifact except the container
CPU/memory/network panels is unaffected and valid. Those panels will be empty for this run,
and nothing in the run directory itself will say why.

What this looked like on 2026-08-22: cadvisor up, container_scrape_error 0, and ~110 host
cgroup series with name=\"\" standing in for the containers. It survived being recreated at
every run and was cleared only by restarting the Docker daemon — so the rest of THIS
campaign will very likely be blind too.

  docker logs cadvisor | tail -50          # what it says about the container runtime
  curl -s $CADVISOR_URL/metrics | grep -c 'name=\"[^\"]'   # 0 means still blind
  sudo systemctl restart docker            # what actually cleared it last time"
    fi
fi

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
    STEP_RAMP_S STEP_PLATEAU_S STEP_TRIM SPIKE_PEAK SOAK_DURATION
    BP_START BP_PEAK BP_RAMP
    WARMUP_ITERATIONS WARMUP_RATE WARMUP_MAX_DURATION READ_RATE READ_MODE
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

# Warmup is fixed-ITERATION and fixed-RATE. Fixed iterations so a fast variant does not
# enter the measured window with more event-store depth, snapshot count and cache state
# than a slow one. Fixed rate so it does not enter with a BACKLOG either: the old 50-VU
# closed loop submitted at up to 1543/s against variants sustaining 89-422/s, leaving the
# settle phase below to undo thousands of queued orders. WARMUP_RATE has no default and
# comes from the point (points.env); k6 refuses the run without one.
log "warmup: ${WARMUP_ITERATIONS:-5000} iterations at ${WARMUP_RATE:-<unset>}/s"
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
# `breakpoint` does not drain, by design. The scenario ramps past the point where the system
# keeps up, so the backlog at T1 is unbounded and waiting for it to clear would measure the
# recovery of a deliberately broken system -- and would take DRAIN_TIMEOUT to conclude
# nothing. T2 is therefore pinned to T1: window_full collapses onto window_load, which
# dump.py handles (`width = max(1, ...)`).
#
# The cost is that order_e2e_time is truncated at T1, so a breakpoint run's e2e percentiles
# cover only the orders that completed while the ramp was climbing. They are biased optimistic
# and are not comparable with any draining scenario. See the header of breakpointProfile().
#
# backlog_at_stop is still recorded: one PENDING count after the load ends is cheap and it is
# the single most useful number the run produces about how far past break it got.
if [ "$SCENARIO" = "breakpoint" ]; then
    BACKLOG_AT_STOP="$(pending_orders)"
    DRAIN_STATE="skipped"
    DRAIN_SECONDS=0
    T2="$T1"
    log "drain: SKIPPED for breakpoint; backlog_at_stop=$BACKLOG_AT_STOP"
else
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
fi

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
    "point": "${POINT_RESOLVED:-}",
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
    "expected_replicas": 1,
    # order-saga segment count actually in force. Not derivable from branch+commit: compose
    # passes AXON_SAGA_TOTAL_SEGMENTS on every run, so two runs of the same commit can differ
    # here. TO variants have no saga processor and the value is meaningless for them.
    #
    # THE FALLBACK MUST MIRROR DOCKER-COMPOSE'S, not the branch's application.yaml. Compose
    # always SETS the variable, so an unset host env still reaches the container as compose's
    # default and the yaml's own `${AXON_SAGA_TOTAL_SEGMENTS:60}` is dead text. This read 60
    # against compose's 32 until 2026-09-04: every run that did not set the knob recorded a
    # segment count the API never ran, and no archive can be backfilled from meta.json alone.
    # scripts/tests/test_compose_files.py pins the two files together so it cannot drift again.
    "saga_total_segments": int("${AXON_SAGA_TOTAL_SEGMENTS:-32}"),
    # ES-4-bounded's intake bound, for the same reason and with the same fallback rule; it read
    # 112 against compose's 70 until the same date. Meaningless for every other variant, which
    # binds the property and never reads it.
    "saga_intake_capacity": int("${AXON_SAGA_INTAKE_CAPACITY:-70}"),
    # The saga-command pool width, for the same reason, and it matters more than the other two:
    # it sets the connection budget as 2 x (command_pool + saga threads + 3), so a run that moved
    # it is not comparable to one that did not even at the same commit. Default here mirrors
    # docker-compose's.
    "command_pool": int("${COMMAND_POOL:-112}"),
    # The TO order-worker width. Compose has pinned this on every TO run since 667d4e5 and it was
    # recorded NOWHERE until 2026-09-04, so no archived TO run says which width it ran at -- the
    # branches' own application.yaml said 200 and was overridden to 50 every single time. Fallback
    # mirrors docker-compose's, as above. Meaningless on ES, which never binds it.
    "order_worker_threads": int("${ORDER_WORKER_THREADS:-50}"),
    # TO-3-parallel's reserve fan-out pool, and the queue in front of it. Two runs of the same
    # commit differ by these: the width sets how much of the modify phase actually runs in
    # parallel, and once width and queue are both exhausted a line group runs inline on the
    # order-worker thread instead -- i.e. the branch degrades to TO-3 without saying so anywhere
    # else. Fallbacks mirror docker-compose's. Meaningless on every other variant.
    "reserve_fanout_threads": int("${RESERVE_FANOUT_THREADS:-150}"),
    "reserve_fanout_queue_capacity": int("${RESERVE_FANOUT_QUEUE_CAPACITY:-1000}"),
    # The snapshot trigger actually in force, on the ES-2/ES-4 family. Strings, and EMPTY when the
    # run did not set them: compose forwards both bare, so an unset run is whatever that branch's
    # application.yaml ships (enabled, every 30 events) and there is no branch-independent number
    # to fall back to -- ES-1 takes no snapshots at all, and int("") would abort the run here.
    # Same reason as db_net_delay below: absent means "the default stack", not a value.
    "snapshot_enabled": "${SNAPSHOT_ENABLED:-}",
    "snapshot_event_count": "${SNAPSHOT_EVENT_COUNT:-}",
    # The injected datacenter RTT on the postgres -> api hop, and the evidence that it took.
    # Empty means the run was unshaped, which is the default. db_net_qdisc is what tc reported
    # from inside postgres' namespace and the two rtt figures bracket the install, so a run
    # cannot record a delay it did not actually get -- the same reason image_id is recorded
    # next to image_tag.
    "db_net_delay": "${DB_NET_DELAY:-}",
    "db_net_jitter": "${DB_NET_JITTER:-}",
    "db_net_qdisc": """$NETEM_QDISC""".strip(),
    # None, not null: this heredoc is Python source, and json.dump writes it out as null.
    "db_net_rtt_before_ms": ${NETEM_RTT_BEFORE:-None},
    "db_net_rtt_after_ms": ${NETEM_RTT_AFTER:-None},
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
        # drained/timeout/skipped. The bool above cannot tell a drain that ran out of time
        # from `breakpoint`, which never drains at all -- both leave drained=false.
        "drain_state": "$DRAIN_STATE",
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
