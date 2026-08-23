#!/usr/bin/env bash
# Proves the one property the watermark arm rests on, against a real PostgreSQL.
#
# The unit tests (OutboxWatermarkDrainTest) pin the LOOP's contract: window bounds, paging, when
# the boundary is saved. They cannot pin the DATABASE's contract, which is the part that actually
# makes the arm strand-free:
#
#   a row with xact_id < W was written by a transaction with xid < W, and every such transaction
#   was already decided when W = pg_snapshot_xmin(pg_current_snapshot()) was read -- so no row
#   satisfying xact_id < W can appear after the read.
#
# That is what the seq cursor cannot say: `seq` is a BIGSERIAL handed out at INSERT, so a
# transaction can hold a low seq and commit long after higher ones (measured on TO-2: 58,623 rows
# stranded below the cursor in the first 20 minutes of a capacity run, against TO-1's 0).
#
# The scenario below is exactly the race that strands rows:
#   session A  BEGIN, INSERT (takes a LOW seq), then holds -- like a reserve transaction waiting on
#              an inventory_state row lock in statement 4 while its outbox row from statement 1 is
#              already numbered.
#   session B  INSERT and COMMIT (takes a HIGHER seq, becomes visible first).
#   drain      must NOT consume B while A is open -- consuming it is what advances a seq cursor
#              past A and strands it.
#
# Usage: scripts/verify_watermark_cursor.sh          (needs the `postgres` container running)
set -euo pipefail

CONTAINER="${DB_CONTAINER:-postgres}"
DB_USER="${DB_USER:-inventory}"
DB_NAME="${DB_NAME:-inventory}"
HOLD_SECONDS="${HOLD_SECONDS:-6}"

q() { docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAqc "$1" | tr -d '[:space:]'; }
fail() { echo "FAIL: $*" >&2; exit 1; }

docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAqc 'SELECT 1' >/dev/null 2>&1 \
    || fail "no PostgreSQL in container '$CONTAINER' (set DB_CONTAINER, or start the stack)"

q "SELECT 1 FROM information_schema.columns
   WHERE table_name = 'event_publication' AND column_name = 'xact_id'" | grep -q 1 \
    || fail "event_publication.xact_id is missing -- V9 has not run against this database"

echo "== setup: two marker publications, distinguishable from real traffic =="
TAG="watermark-proof-$$"
q "DELETE FROM event_publication WHERE listener_id = '$TAG'" >/dev/null

# ---- session A: INSERT, then hold the transaction open ------------------------------------------
docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -q >/dev/null 2>&1 <<SQL &
BEGIN;
INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date)
VALUES (gen_random_uuid(), '$TAG', 'A.held', '{}', now());
SELECT pg_sleep($HOLD_SECONDS);
COMMIT;
SQL
HOLDER=$!
trap 'kill $HOLDER 2>/dev/null || true' EXIT

sleep 2  # let A take its xid and its seq

# ---- session B: INSERT and COMMIT while A is still open -----------------------------------------
q "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date)
   VALUES (gen_random_uuid(), '$TAG', 'B.committed', '{}', now())" >/dev/null

A_SEQ=$(q "SELECT seq FROM event_publication WHERE listener_id = '$TAG' AND event_type = 'A.held'")
B_SEQ=$(q "SELECT seq FROM event_publication WHERE listener_id = '$TAG' AND event_type = 'B.committed'")
echo "   A holds seq=${A_SEQ:-<invisible, as expected while uncommitted>}  B committed at seq=$B_SEQ"

echo
echo "== 1. while A is open: the window must exclude B, even though B is committed and visible =="
W=$(q "SELECT pg_snapshot_xmin(pg_current_snapshot())::text")
VISIBLE=$(q "SELECT count(*) FROM event_publication WHERE listener_id = '$TAG'")
IN_WINDOW=$(q "SELECT count(*) FROM event_publication
               WHERE listener_id = '$TAG' AND xact_id < '$W'::xid8")
echo "   watermark=$W  rows visible=$VISIBLE  rows inside the window=$IN_WINDOW"

[ "$VISIBLE" = "1" ] || fail "expected B alone to be visible while A is open, saw $VISIBLE"
[ "$IN_WINDOW" = "0" ] || fail "B fell inside the window while A was still open -- a seq cursor \
would now advance past A and strand it, which is the whole bug"
echo "   OK: the boundary held B back. A seq cursor would have taken it and moved past A."

echo
echo "== 2. after A commits: both rows fall inside the window, in commit order =="
wait "$HOLDER" 2>/dev/null || true
trap - EXIT

W2=$(q "SELECT pg_snapshot_xmin(pg_current_snapshot())::text")
IN_WINDOW2=$(q "SELECT count(*) FROM event_publication
                WHERE listener_id = '$TAG' AND xact_id < '$W2'::xid8")
echo "   watermark=$W2  rows inside the window=$IN_WINDOW2"
[ "$IN_WINDOW2" = "2" ] || fail "expected both rows inside the window once A committed, saw $IN_WINDOW2"

# The ordering the drain pages by. Note what step 1 established: B was COMMITTED AND VISIBLE with
# the higher seq while A still held the lower one, so a seq cursor had every reason to take B and
# advance past A. Ordering by xact_id instead puts A first, where it belongs -- and the boundary
# is what kept B waiting until A was decided.
echo "   paging order (xact_id, seq):"
docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
    "SELECT event_type, seq, xact_id FROM event_publication
     WHERE listener_id = '$TAG' ORDER BY xact_id, seq"

q "DELETE FROM event_publication WHERE listener_id = '$TAG'" >/dev/null
echo
echo "PASS: the window never contains a row whose transaction is still open."
