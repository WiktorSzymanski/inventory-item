-- Cursor-based delivery for the NOTIFY path.
--
-- Why (measured on TO-2_capacity_20260821T184545Z, 24-core host, with the purge and V7 already in
-- place): the drain asks "is anything undelivered?" ~25x/s, and because this branch KEEPS UP the
-- answer is almost always no. Proving `no` means walking the entire `completion_date IS NULL`
-- region of idx_event_publication_completion_date -- and completing a publication writes
-- completion_date, an indexed column, so the update can never be HOT and every delivery leaves a
-- dead entry in exactly that region. At the cliff: heap blocks per index scan 5 -> ~200,
-- tup_returned 2.9M/s, commits FELL 2,798 -> 1,250, in-flight HTTP pinned at the 99-thread cap.
--
-- TO-1 does not show this, and NOT because its query lacks a LIMIT. It runs ~10 minutes behind, so
-- its NULL region is packed with LIVE undelivered rows: it finds work immediately and never pays
-- for an absence proof (4.1 heap blocks per scan, flat for an hour, on a 4.6 GB table -- against
-- this branch's ~200 on a 140 MB one). The cost is not table size, it is what you must walk past.
--
-- So the outbox's real choice is: be late and the scan is cheap, or be timely and pay an absence
-- proof over a graveyard. A cursor removes the choice -- "where did I get to" is a position, so the
-- scan STARTS past the graveyard instead of walking through it.
ALTER TABLE event_publication ADD COLUMN seq BIGSERIAL;

-- ONE index serves both delivery processes.
--
-- Partial on IS NULL so a completed row leaves the index entirely. Combined with `seq > :cursor`,
-- the drain's scan starts past every tombstone, which is the whole point: when the backlog is empty
-- the absence proof costs one index descent instead of a walk of the whole NULL region.
--
-- V7's note that "the index SHAPE is not the variable -- whether vacuum has kept up is" was
-- measured against a query that had to walk the whole NULL region either way. It still holds for
-- THAT query. This is a different access pattern: an ordered forward scan from a known position.
CREATE INDEX idx_event_publication_seq_incomplete
    ON event_publication (seq) WHERE completion_date IS NULL;

-- One row, holding the drain's position. Deliberately not the id: `id` is a random UUID and carries
-- no order, and publication_date is wall-clock from the JVM. seq is the only monotonic handle.
CREATE TABLE outbox_cursor (
    id         SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    position   BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO outbox_cursor (id, position) VALUES (1, 0);

-- One row updated up to ~25x/s. No indexed column changes, so every update is HOT and reuses the
-- page -- but the default trigger, `50 + 0.2 * n_live_tup`, is 50 dead tuples on a one-row table,
-- which is exactly the proportional-threshold trap V7 removed from event_publication.
ALTER TABLE outbox_cursor SET (
    autovacuum_vacuum_scale_factor = 0.0,
    autovacuum_vacuum_threshold    = 50
);
