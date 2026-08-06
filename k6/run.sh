#!/bin/sh
# Back-compat shim for `docker compose --profile load-test up k6`.
#
# The supported entry point is now:   SCENARIO=steady ./k6/bench/bench.sh
#
# This path runs a load phase ONLY. It does not reset the database, does not warm up,
# does not wait for the backlog to drain, does not snapshot Prometheus, and produces no
# verdict — so the numbers it yields are not comparable across branches or across runs.
# It exists so an old muscle-memory invocation still does something recognisable.

if [ -z "$SCENARIO" ]; then
    echo "================================================================" >&2
    echo " DEPRECATED: bare 'docker compose --profile load-test up k6'."    >&2
    echo " Not thesis-grade: no clean slate, no warmup, no drain, no dump." >&2
    echo " Use instead:  SCENARIO=steady ./k6/bench/bench.sh"               >&2
    echo " Running SCENARIO=legacy (old ramp shape, MAX_RPS / RAMP_DURATION)." >&2
    echo "================================================================" >&2
    SCENARIO=legacy
fi

OUT_DIR="${OUT_DIR:-/reports/legacy}"
export SCENARIO OUT_DIR
mkdir -p "$OUT_DIR"

# Knobs are forwarded explicitly with -e rather than relied on as system env vars: k6 2.0
# stopped populating __ENV from the process environment by default, so the compose
# `environment:` block alone would silently stop reaching the script on an image bump.
set -- run /scripts/main.js -e "SCENARIO=$SCENARIO" -e "OUT_DIR=$OUT_DIR"
[ -n "$BASE_URL" ]       && set -- "$@" -e "BASE_URL=$BASE_URL"
[ -n "$MAX_RPS" ]        && set -- "$@" -e "MAX_RPS=$MAX_RPS"
[ -n "$RAMP_DURATION" ]  && set -- "$@" -e "RAMP_DURATION=$RAMP_DURATION"
[ -n "$DURATION" ]       && set -- "$@" -e "DURATION=$DURATION"
[ -n "$VUS" ]            && set -- "$@" -e "VUS=$VUS"
[ -n "$ITEMS_PER_ORDER" ] && set -- "$@" -e "ITEMS_PER_ORDER=$ITEMS_PER_ORDER"
[ -n "$DISTINCT_ITEMS" ] && set -- "$@" -e "DISTINCT_ITEMS=$DISTINCT_ITEMS"
[ -n "$PAYLOAD_BYTES" ]  && set -- "$@" -e "PAYLOAD_BYTES=$PAYLOAD_BYTES"
[ -n "$RESERVE_DELAY_MS" ] && set -- "$@" -e "RESERVE_DELAY_MS=$RESERVE_DELAY_MS"

k6 "$@"

wget -q -O "/reports/report_$(date +%Y%m%d_%H%M%S).pdf" \
  "http://grafana-reporter:8686/api/v5/report/the-dashboard?from=now-${DURATION:-10m}&to=now"
