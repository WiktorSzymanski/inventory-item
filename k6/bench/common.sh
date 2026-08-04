#!/usr/bin/env bash
# Shared helpers. Sourced by bench.sh / reset.sh / sweep.sh.
# Byte-identical on every variant branch: all per-branch values come from bench.env.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export REPO_ROOT

if [ -f "$REPO_ROOT/bench.env" ]; then
    # shellcheck disable=SC1091
    set -a; . "$REPO_ROOT/bench.env"; set +a
else
    echo "FATAL: $REPO_ROOT/bench.env not found (it is the only per-branch harness file)" >&2
    exit 1
fi

# .env is the SINGLE source of truth for the replica count: docker compose auto-loads it,
# so whatever REPLICAS says there is what actually runs. Sourcing it here (rather than
# duplicating the number in bench.env) removes the trap of two independent knobs for the
# same physical quantity, where editing one silently invalidated the run.
if [ -f "$REPO_ROOT/.env" ]; then
    # shellcheck disable=SC1091
    set -a; . "$REPO_ROOT/.env"; set +a
fi
EXPECTED_REPLICAS="${REPLICAS:-1}"
export EXPECTED_REPLICAS

: "${VARIANT:?bench.env must set VARIANT}"
: "${API_SVC:?bench.env must set API_SVC}"
: "${DB_SVC:?bench.env must set DB_SVC}"
: "${DB_USER:?bench.env must set DB_USER}"
: "${DB_NAME:?bench.env must set DB_NAME}"
: "${PROM_JOB:?bench.env must set PROM_JOB}"

# Compose invocation as a function over an array, not a string. A string variable used as
# `dc run ...` depends on unquoted word-splitting, which breaks outright under zsh and is
# fragile anywhere a path could contain a space.
DC_ARGS=(docker compose
         -f "$REPO_ROOT/docker-compose.yml"
         -f "$REPO_ROOT/docker-compose.bench.yml")
dc() { "${DC_ARGS[@]}" "$@"; }

PROM_URL="${PROM_URL:-http://localhost:9090}"
REPORTER_URL="${REPORTER_URL:-http://localhost:8686}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/actuator/health}"
# URL k6 uses from inside the compose network. Every branch now fronts its API with the
# same nginx load balancer, so the default is uniform; the API service is never addressed
# directly, because at REPLICAS>1 it has no single address.
BENCH_BASE_URL="${BENCH_BASE_URL:-http://nginx:8080}"
export PROM_URL REPORTER_URL HEALTH_URL BENCH_BASE_URL

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { printf '[%s] FATAL: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

# Single-value SQL query against the app database.
psql_q() {
    dc exec -T "$DB_SVC" psql -U "$DB_USER" -d "$DB_NAME" -tAqc "$1" 2>/dev/null | tr -d '[:space:]'
}

# Count of orders that have not yet reached a terminal state.
# Family-agnostic on purpose: ES writes CONFIRMED/REJECTED and TO writes COMPLETED/FAILED,
# but both schemas DEFAULT the status column to 'PENDING', so counting PENDING is the one
# query that means the same thing on all 11 branches.
#
# NOTE this is a SEQUENTIAL SCAN: `orders` is indexed only on its order_id primary key.
# It is therefore called exactly twice per run (once to size the backlog, once to confirm
# it cleared) and never in the polling loop -- see drain_wait.
pending_orders() {
    local n
    n=$(psql_q "SELECT count(*) FROM orders WHERE status = 'PENDING'")
    [ -n "$n" ] && echo "$n" || echo "-1"
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
# The polling signal is Prometheus, NOT Postgres. Prometheus is already scraping the API
# every 5s, so reading the in-flight difference costs the system under test nothing. The
# obvious alternative -- re-running the PENDING count every couple of seconds -- would
# seq-scan the orders table against the very database whose drain rate is being measured,
# and would get steadily more expensive as the table grows, biasing the tail of the drain
# window. Postgres is still consulted twice, for the exact entry and exit counts.
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
