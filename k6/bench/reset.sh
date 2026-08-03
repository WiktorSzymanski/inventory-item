#!/usr/bin/env bash
# Clean slate before a measured run: stop the API, truncate every application table,
# start the API, wait for health.
#
# The API MUST be stopped first, and MUST be restarted afterwards. Four independent
# reasons, any one of which is sufficient:
#   1. PessimisticCachingRepository keeps confirmed aggregate state in a strong-reference,
#      never-evicted ConcurrentHashMap. Truncating underneath a live API leaves it serving
#      aggregates that no longer exist in the event store.
#   2. Axon's TrackingEventProcessors cache their tokens in memory and would write them
#      back into the freshly emptied token_entry.
#   3. In-flight commands would re-insert rows into tables we just truncated.
#   4. The JIT profile from the previous run would carry over into the next measurement.
# The Micrometer counter reset that comes with the restart is a feature: every counter
# starts at 0, so a window delta equals the absolute total, and resets() becomes a clean
# "did the API restart mid-run" validity check.
set -euo pipefail

# shellcheck source=k6/bench/common.sh
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

log "reset: stopping $API_SVC"
dc stop "$API_SVC" >/dev/null

log "reset: truncating application tables"
# Tables are discovered from the catalogue rather than named, which is what lets this
# script stay byte-identical across families whose schemas genuinely differ:
#   ES: domain_event_entry, snapshot_event_entry, token_entry, saga_entry,
#       association_value_entry, inventory_state, orders
#   TO: reservations, event_publication, inventory_state, orders
# TRUNCATE (not DELETE) drops the relation files outright, so pg_database_size_bytes
# genuinely resets and becomes a clean per-run "bytes written to disk" measure.
# RESTART IDENTITY also resets the BIGSERIAL global_index that Axon's
# GapAwareTrackingToken and createTailToken() are indexed off.
dc exec -T "$DB_SVC" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
DO $$
DECLARE t text;
BEGIN
  SELECT string_agg(format('%I.%I', schemaname, tablename), ', ')
    INTO t
    FROM pg_tables
   WHERE schemaname = 'public'
     AND tablename <> 'flyway_schema_history';
  IF t IS NOT NULL THEN
    RAISE NOTICE 'truncating: %', t;
    EXECUTE 'TRUNCATE TABLE ' || t || ' RESTART IDENTITY CASCADE';
  END IF;
END $$;
ANALYZE;
SQL

log "reset: starting $API_SVC (x${EXPECTED_REPLICAS}) and nginx"
# nginx is named explicitly because --no-deps suppresses it: it depends ON the api service,
# not the other way round, and it owns the published :8080 that HEALTH_URL points at.
# --no-deps still does its real job, which is keeping postgres from being restarted.
dc up -d --no-deps "$API_SVC" nginx >/dev/null

"$(dirname "${BASH_SOURCE[0]}")/wait-healthy.sh" "$HEALTH_URL" "${HEALTH_TIMEOUT:-180}"

# Health is observed THROUGH the load balancer, so a 200 only proves that one replica came
# up. Assert the full count here rather than letting evaluate.py's targets_scraped check
# return INVALID after an entire measured run has already been spent on a short stack.
ACTUAL_REPLICAS=$(dc ps -q "$API_SVC" | wc -l | tr -d '[:space:]')
[ "$ACTUAL_REPLICAS" = "$EXPECTED_REPLICAS" ] || \
    die "expected $EXPECTED_REPLICAS $API_SVC replicas (REPLICAS in .env), found $ACTUAL_REPLICAS"

REMAINING=$(psql_q "SELECT coalesce(sum(n_live_tup),0) FROM pg_stat_user_tables WHERE relname <> 'flyway_schema_history'")
log "reset: complete (live tuples after reset: ${REMAINING:-unknown})"
