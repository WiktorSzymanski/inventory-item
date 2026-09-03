#!/usr/bin/env bash
# Shared helpers. Sourced by bench.sh / reset.sh / sweep.sh.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export REPO_ROOT

# There is no bench.env any more. main owns the only harness and names the compose
# services itself, so every value that used to be per-branch is either a constant or comes
# from variants.env via scripts/run-suite.sh, which exports VARIANT, VARIANT_FAMILY and
# IMAGE_TAG before invoking this.
#
# Keeping these as overridable defaults rather than hardcoding them lets a one-off manual
# run point at a differently-named stack without editing the harness.
API_SVC="${API_SVC:-api}"
DB_SVC="${DB_SVC:-mongo}"
# Which scrape job carries this variant's app metrics, and which port its /actuator lives on.
#
# Variants listed here give actuator its own connector via `management.server.port`, so that a
# saturated request pool cannot starve the scrape: on fix-A's capacity run of 2026-08-25 -- taken
# BEFORE it had the port, and INVALID on `scrape_up` because of it -- the target was lost for 23.2
# minutes starting three minutes after in-flight HTTP pinned at the 99-thread cap.
# Prometheus scrapes them under `inventory-mgmt` (monitoring/prometheus/prometheus.yml), and the
# health check has to follow because /actuator moves to that port wholesale.
#
# A list, not a rule: it is a property of the branch's application.yaml, which this harness
# cannot see. Add a variant here in the same change that adds the port to its branch, and delete
# the whole block once every variant has one -- it exists only to let the two kinds coexist.
case "${VARIANT:-}" in
    TO-2-fix-A|TO-2-fix-B)
        PROM_JOB="${PROM_JOB:-inventory-mgmt}"
        MGMT_PORT="${MGMT_PORT:-8090}"
        ;;
    *)
        PROM_JOB="${PROM_JOB:-inventory}"
        MGMT_PORT="${MGMT_PORT:-8080}"
        ;;
esac
export MGMT_PORT
# An exact name, not a pattern. docker-compose.yml pins `container_name: api`, so cadvisor
# reports exactly that -- no `<project>-api-N` shape to tolerate and no dependence on
# COMPOSE_PROJECT_NAME. queries.promql applies it as an anchored name=~"$CRE" and Prometheus
# anchors regexes fully, so this matches the api container and no sibling.
API_CONTAINER_RE="${API_CONTAINER_RE:-api}"
DB_NAME="${DB_NAME:-inventory}"
# No DB_USER. mongod runs without authentication here, exactly as the Postgres stack runs
# with a fixed throwaway password: the database is a per-run scratch container on a private
# compose network, and a credential would be one more thing to keep in step between the
# compose file, the branches' URIs and this harness.
VARIANT_FAMILY="${VARIANT_FAMILY:-}"
export API_SVC DB_SVC PROM_JOB API_CONTAINER_RE DB_NAME VARIANT_FAMILY

: "${VARIANT:?VARIANT must be set (scripts/run-suite.sh sets it from variants.env)}"
# Guarded here for the same reason VARIANT is, and just as loudly. docker-compose.yml
# declares `image: ${IMAGE_TAG:?…}` with no default so a bare `docker compose up` cannot
# silently benchmark whichever variant was last built — but without this line the first
# symptom under `set -u` was a bare `IMAGE_TAG: unbound variable` from bench.sh, naming
# neither the cause nor the fix. Exported, not merely checked: docker compose reads it from
# the ENVIRONMENT, so a set-but-unexported value would pass the check and still fail there.
: "${IMAGE_TAG:?IMAGE_TAG must be set (scripts/run-suite.sh derives it from variants.env, e.g. inventory-reservation-es-4:latest)}"
export IMAGE_TAG

# Compose invocation as a function over an array, not a string. A string variable used as
# `dc run ...` depends on unquoted word-splitting, which breaks outright under zsh and is
# fragile anywhere a path could contain a space.
DC_ARGS=(docker compose
         -f "$REPO_ROOT/docker-compose.yml"
         -f "$REPO_ROOT/docker-compose.bench.yml")
dc() { "${DC_ARGS[@]}" "$@"; }

PROM_URL="${PROM_URL:-http://localhost:9090}"
REPORTER_URL="${REPORTER_URL:-http://localhost:8686}"
# MGMT_PORT, not a literal 8080: /actuator moves wholesale to management.server.port on the
# variants that set one, health included. 8080 for every variant that does not.
HEALTH_URL="${HEALTH_URL:-http://localhost:${MGMT_PORT}/actuator/health}"
# URL k6 uses from inside the compose network. It addresses the API service directly: there
# is one container and no proxy in front of it, so no hop is measured that is not the
# application. HEALTH_URL above reaches the same container via its published host port.
BENCH_BASE_URL="${BENCH_BASE_URL:-http://api:8080}"
export PROM_URL REPORTER_URL HEALTH_URL BENCH_BASE_URL

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { printf '[%s] FATAL: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

# Block until mongod is a writable PRIMARY, not merely reachable.
#
# THIS IS NOT BELT-AND-BRACES; without it the first reset of every fresh stack fails with
# "MongoServerError: node is not in primary or recovering state". `docker compose up -d`
# returns when containers have STARTED, not when the one-shot mongo-init has finished, and
# the `service_completed_successfully` gate in docker-compose.yml only covers the `api`
# service -- which bench.sh does not start at that point. So the harness's own first query
# races rs.initiate(), and mongo's healthcheck cannot help: it is a ping, and a mongod with
# no initiated replica set answers it happily.
#
# Polling hello() rather than waiting on the init container is deliberate: it is also correct
# on a stack whose mongo-init exited minutes ago, which is what a hand-run reset.sh sees.
wait_for_mongo_primary() {
    local timeout="${1:-60}" deadline
    deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if [ "$(dc exec -T "$DB_SVC" mongosh --quiet --eval \
                 'db.hello().isWritablePrimary' 2>/dev/null | tr -d '[:space:]')" = "true" ]; then
            return 0
        fi
        sleep 1
    done
    die "mongo did not become PRIMARY within ${timeout}s -- is mongo-init failing? (docker logs mongo-init)"
}

# Single-value query against the app database. The mongosh counterpart of the Postgres
# harness's psql_q, and used the same way: pass an expression, get one bare value back.
#
# --quiet suppresses the shell banner; without it every caller would have to strip it.
mongo_q() {
    dc exec -T "$DB_SVC" mongosh --quiet --eval "$1" "$DB_NAME" 2>/dev/null | tr -d '[:space:]'
}

# Count of orders that have not yet reached a terminal state.
# Family-agnostic on purpose: ES writes CONFIRMED/REJECTED and TO writes COMPLETED/FAILED,
# but both schemas DEFAULT the status column to 'PENDING', so counting PENDING is the one
# query that means the same thing on all 11 branches.
#
# NOTE this is a COLLECTION SCAN: `orders` carries no index on `status`, only the _id one
# Mongo creates for it. It is therefore called exactly twice per run (once to size the
# backlog, once to confirm it cleared) and never in the polling loop -- see drain_wait.
pending_orders() {
    local n
    n=$(mongo_q 'db.orders.countDocuments({status: "PENDING"})')
    # A count is all digits; anything else is mongosh reporting a problem on stdout, and
    # must not be mistaken for a backlog of zero.
    case "$n" in
        ''|*[!0-9]*) echo "-1" ;;
        *)           echo "$n" ;;
    esac
}

# In-flight orders straight from Prometheus: admitted (202) minus terminal (e2e recorded).
# Both counters reset to 0 with the API, so the difference is exact.
inflight_prom() {
    local expr v
    # `or vector(0)` on both sides: a binary operator drops unmatched operands, so before
    # the first order completes the e2e series does not exist and a bare subtraction
    # returns an EMPTY vector -- indistinguishable from "query failed", even though the
    # real backlog may be thousands deep.
    expr="(sum(http_server_requests_seconds_count{job=\"$PROM_JOB\",uri=\"/inventory/orders\",method=\"POST\",status=\"202\"}) or vector(0)) - (sum(order_e2e_time_seconds_count{job=\"$PROM_JOB\"}) or vector(0))"
    v=$(curl -sf --get "$PROM_URL/api/v1/query" --data-urlencode "query=$expr" 2>/dev/null \
        | python3 -c 'import json,sys
try:
    r = json.load(sys.stdin)["data"]["result"]
    print(int(float(r[0]["value"][1])) if r else -1)
except Exception:
    print(-1)' 2>/dev/null)
    [ -n "$v" ] && echo "$v" || echo "-1"
}

# Poll the order backlog to zero. Echoes the backlog observed at entry, then "drained"
# or "timeout", then the elapsed seconds -- one field per line.
#
# The polling signal is Prometheus, NOT the database. Prometheus is already scraping the API
# every 5s, so reading the in-flight difference costs the system under test nothing. The
# obvious alternative -- re-running the PENDING count every couple of seconds -- would
# collection-scan `orders` against the very database whose drain rate is being measured,
# and would get steadily more expensive as the collection grows, biasing the tail of the
# drain window. Mongo is still consulted twice, for the exact entry and exit counts.
drain_wait() {
    local timeout="${1:-900}" start backlog now inflight tail
    start=$(date +%s)
    backlog=$(pending_orders)

    while :; do
        inflight=$(inflight_prom)
        now=$(date +%s)

        # -1 means the query failed or no series exists yet; keep waiting rather than
        # declaring a false drain.
        if [ "$inflight" -ge 0 ] 2>/dev/null && [ "$inflight" -le 0 ]; then
            # Confirm against the source of truth before calling it drained: Prometheus
            # can be up to one scrape interval stale.
            tail=$(pending_orders)
            if [ "$tail" = "0" ]; then
                echo "$backlog"; echo "drained"; echo "$((now - start))"; return 0
            fi
        fi

        if [ "$now" -ge "$((start + timeout))" ]; then
            tail=$(pending_orders)
            log "drain TIMEOUT after ${timeout}s, ${tail} orders still PENDING (in-flight=${inflight})"
            echo "$backlog"; echo "timeout"; echo "$((now - start))"; return 1
        fi
        sleep 2
    done
}

# ---------------------------------------------------------------- service logs
# >>> service-logs
# Archive the containers' own stdout/stderr into the run directory.
#
# Until this existed a run kept k6's view and Prometheus' view and nothing the service
# itself said. Two things that costs: a run that dies at the health timeout leaves no
# record of WHY the API never came up, and a run whose interpretation turns on which code
# path was live has nothing to confirm it — TO-2-fix-A logs `[OUTBOX] drain mode=WATERMARK`
# at startup and no other artifact records the arm.
#
# WHICH containers: the service under test and its database. The monitoring sidecars
# (prometheus, grafana, cadvisor, mongodb-exporter) are harness, not subject; their output
# says nothing about the variant and would be bulk in every run directory.
#
# WHY --since, and why it is not optional. reset.sh does `stop` + `up -d`, which RESTARTS
# the api container rather than recreating it, so json-file keeps the previous run's output
# too and an unscoped dump would silently concatenate it into this run's archive. (Between
# variants run-suite.sh's `down -v` removes the container, so this only bites a repeated
# direct bench.sh — which k6/README.md documents.) $T_RESET is taken just before reset.sh
# stops the service, so the restart and its startup banner fall inside the window.
#
# WHY gzip. The volume is structural, not incidental: every branch ships logback
# root=INFO, and ~12 of those INFO sites sit on the per-order path on TO (~17 on ES) —
# controller admit/accept, the command handlers, and one line per delivered publication.
# At ~180 B/line after docker's --timestamps prefix, the 71k-order capacity runs already in
# bench-results/ would write 150-215 MB next to a 3.8 MB run directory. Compressed that is
# ~10 MB. Read with `zless`/`zgrep`.
#
# Non-fatal throughout, and deliberately so: this runs from an EXIT trap, including the one
# that fires while bench.sh is already dying of something else. A failure here must never
# replace that exit status nor hide the original cause.
SERVICE_LOG_SVCS="${SERVICE_LOG_SVCS:-$API_SVC $DB_SVC}"

# $1 = destination directory, $2 = epoch second to start from ("" for everything).
capture_service_logs() {
    local dest="$1" since="${2:-}" svc
    if ! mkdir -p "$dest" 2>/dev/null; then
        log "logs: cannot create $dest — service logs not captured"
        return 0
    fi
    for svc in $SERVICE_LOG_SVCS; do
        local args=(logs --no-color --no-log-prefix --timestamps)
        if [ -n "$since" ]; then args+=(--since "$since"); fi
        # 2>&1 into the archive on purpose: if compose itself fails, its complaint is what
        # the file should contain rather than nothing at all. pipefail makes the pipeline
        # report the compose exit status and not gzip's.
        if dc "${args[@]}" "$svc" 2>&1 | gzip -c >"$dest/$svc.log.gz"; then
            log "logs: $svc -> $(basename "$dest")/$svc.log.gz"
        else
            log "logs: capture of '$svc' failed (non-fatal; see $dest/$svc.log.gz)"
        fi
    done
    return 0
}
# <<< service-logs
