-- A boundary the drain can PROVE is closed, for the third delivery arm (OUTBOX_CURSOR_WATERMARK).
--
-- Why (measured on TO-2_capacity_W-base_20260822T111030Z, INVALID, 20% completion): V8's cursor
-- advances to the highest `seq` it has SEEN. `seq` is a BIGSERIAL, assigned at INSERT, and on the
-- reserve path that INSERT is statement 1 of OrderWriteCommandHandler.write while the
-- inventory_state row locks are statement 4. So sequence order is not commit order: the drain reads
-- past rows whose transaction has not committed yet and leaves them BELOW its own cursor, where
-- only IncompleteEventRepublisher can reach them -- every 60 s, min-age 60 s.
--
-- That is not a rare leftover on a branch that keeps up, it is the steady state. At ~90 orders/s,
-- before anything else went wrong: backlog 494 against a cursor lag of 14, i.e. almost the entire
-- backlog stranded BELOW the cursor, and 58,623 rows rescued by t=1200 s. TO-1, whose poller runs
-- ten minutes behind and therefore never reads at the tip, stranded 0. Each 60 s rescue arrives as
-- one batch (121/s at t=715 s), and every delivered OrderCreatedEvent enqueues a reservation onto a
-- 200-thread pool with an unbounded queue -- so a batch becomes instantaneous 200-wide concurrency
-- over 100 inventory rows. Conflicts per order went 2.7% -> 13% -> 277%.
--
-- The fix is to stop guessing. Postgres will name the boundary: pg_snapshot_xmin() is the lowest
-- transaction id still in progress, and EVERY transaction below it is already decided. Stamp each
-- row with its own transaction id and the drain can consume exactly the rows it can prove complete.
--
-- The safety argument in one line: a row with xact_id < W was written by a transaction with
-- xid < W, and every such transaction was already decided when W was read -- so no row satisfying
-- xact_id < W can appear after the read. The window is closed, and nothing is left behind it.
--
-- xid8, not xid: 64-bit, no wraparound, and it has the comparison operators xid lacks.
-- The default is volatile, so this rewrites the table -- free here, the harness resets the DB per
-- run, and it is the same shape as V8's `seq BIGSERIAL`. Modulith's own INSERT names neither
-- column; both are filled by their defaults.
ALTER TABLE event_publication ADD COLUMN xact_id xid8 NOT NULL DEFAULT pg_current_xact_id();

-- The window read's index: an ordered forward walk of (xact_id, seq) from a known position, with
-- the same partial predicate as V8's so a completed row leaves the index entirely.
--
-- Kept ALONGSIDE idx_event_publication_seq_incomplete rather than replacing it: the seq arm still
-- pages by `seq > ?`, and the sweep's findIncompleteUpTo still filters `seq <= ?`, neither of which
-- a xact_id-leading index can serve. Two partial indexes on a table taking ~500 INSERTs/s is a real
-- write cost and it is part of the result -- read it off outbox_write_time, not off an assumption.
--
-- seq is the tie-break, not decoration: one transaction writes up to six publications (one
-- OrderCreated, four InventoryReserved, one OrderCompleted) and they all share its xact_id, so
-- xact_id alone is not a unique paging key.
CREATE INDEX idx_event_publication_xact_incomplete
    ON event_publication (xact_id, seq) WHERE completion_date IS NULL;

-- The watermark arm's position, in its own column.
--
-- Deliberately NOT reused `position`: the arms are selected by an env var against the same
-- database, and a seq stored where a transaction id is expected (or the reverse) would be read as a
-- valid position and silently skip or replay a stretch of the outbox. Separate columns make
-- flipping the knob mid-database a no-op for the arm that is not running.
ALTER TABLE outbox_cursor ADD COLUMN xact_position xid8 NOT NULL DEFAULT '0'::xid8;
