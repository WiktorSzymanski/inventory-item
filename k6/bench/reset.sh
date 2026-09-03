#!/usr/bin/env bash
# Clean slate before a measured run: stop the API, drop every application collection,
# start the API, wait for health.
#
# The API MUST be stopped first, and MUST be restarted afterwards. Four independent
# reasons, any one of which is sufficient:
#   1. PessimisticCachingRepository keeps confirmed aggregate state in a Caffeine cache.
#      Dropping underneath a live API leaves it serving aggregates that no longer exist in
#      the event store.
#   2. Axon's TrackingEventProcessors cache their tokens in memory and would write them
#      back into the freshly emptied token_entry.
#   3. In-flight commands would re-insert documents into collections we just dropped.
#   4. The JIT profile from the previous run would carry over into the next measurement.
# The Micrometer counter reset that comes with the restart is a feature: every counter
# starts at 0, so a window delta equals the absolute total, and resets() becomes a clean
# "did the API restart mid-run" validity check.
set -euo pipefail

# shellcheck source=k6/bench/common.sh
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

log "reset: stopping $API_SVC"
dc stop "$API_SVC" >/dev/null

# Before anything touches the database. See wait_for_mongo_primary in common.sh for why the
# compose healthcheck and depends_on are both insufficient here.
log "reset: waiting for mongo PRIMARY"
wait_for_mongo_primary "${MONGO_PRIMARY_TIMEOUT:-60}"

log "reset: dropping application collections"
# Collections are discovered rather than named, exactly as the Postgres harness discovers
# tables from pg_tables. On the ES-*-mongo branches the set is domain_event_entry,
# snapshot_event_entry, token_entry, saga_entry, inventory_state and orders -- but nothing
# here needs to know that, and a branch that adds one is handled without a harness change.
#
# DROP, not deleteMany. Dropping releases the collection's WiredTiger files AND its indexes,
# so mongodb_dbstats_storageSize genuinely resets and stays a clean per-run "bytes written to
# disk" measure -- the same reason the Postgres harness uses TRUNCATE over DELETE. deleteMany
# would leave the storage allocated and every db_size delta would be meaningless.
#
# The indexes go with them, and that is correct rather than a loss: MongoIndexInitializer
# recreates every one at startup, and the API is restarted below. There is no equivalent of
# RESTART IDENTITY to worry about -- MongoTrackingToken is not indexed off a sequence.
#
# system.* is skipped: those are the server's own, cannot be dropped, and an error there
# would fail the reset.
dc exec -T "$DB_SVC" mongosh --quiet --eval '
  const dropped = [];
  db.getCollectionNames().forEach(function (name) {
    if (name.startsWith("system.")) return;
    db.getCollection(name).drop();
    dropped.push(name);
  });
  print("dropped: " + (dropped.length ? dropped.join(", ") : "(none)"));
' "$DB_NAME" >/dev/null

log "reset: starting $API_SVC"
# --no-deps does its real job here, which is keeping mongo from being restarted -- and on this
# branch it also keeps compose from re-running the one-shot mongo-init the api depends on.
# There is nothing else to name: the api publishes :8080 itself, so HEALTH_URL below probes
# the very container this line started, with no proxy in between.
dc up -d --no-deps "$API_SVC" >/dev/null

"$(dirname "${BASH_SOURCE[0]}")/wait-healthy.sh" "$HEALTH_URL" "${HEALTH_TIMEOUT:-180}"

# Counted AFTER the API restarted, so it is not expected to be 0: MongoIndexInitializer has
# recreated the collections and Axon's processors have written their initial tokens. What it
# proves is that nothing survived from the previous run -- a few dozen token documents, not
# the previous run's millions of events.
# Number(), because db.stats() returns a BSON Long and mongosh prints it as `Long('63')`.
# Only a log line, but a diagnostic that needs decoding is a diagnostic people stop reading.
REMAINING=$(mongo_q 'Number(db.stats().objects)')
log "reset: complete (documents after reset: ${REMAINING:-unknown})"
