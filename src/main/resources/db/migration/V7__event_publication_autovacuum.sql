-- Makes autovacuum on event_publication fire on an ABSOLUTE dead-tuple count instead of one
-- proportional to the table's size.
--
-- Why (measured on TO-2_capacity_W-base_20260819T184043Z): completing a publication writes
-- completion_date, an indexed column, so the update can never be HOT --
-- n_tup_hot_upd{relname="event_publication"} was 0 for the entire run. Every completion therefore
-- leaves a dead entry behind in the NULL region of idx_event_publication_completion_date, and
-- findIncompleteIds() has to walk every one of them that vacuum has not yet reclaimed. The cost of
-- that scan is a pure function of the unreclaimed backlog:
--
--     300k rows, 299k dead, xmin horizon pinned : 4,872 buffers
--     the same data, after VACUUM               :    17 buffers
--
-- That scan took event_publication's heap_blocks_hit from 160k/s to 5.0M/s and PostgreSQL from
-- 3.2 to 10 cores while commits FELL from 3,760/s to 1,070/s.
--
-- The default trigger is `50 + 0.2 * n_live_tup`, so how much bloat is tolerated scales with the
-- table, which is exactly backwards for a table that grows all run:
--
--     300,008 live rows -> 60,052 dead tuples tolerated before a vacuum
--         428 live rows ->     136 dead tuples tolerated before a vacuum
--
-- scale_factor 0 removes the coupling to table size; the threshold then bounds the NULL region's
-- bloat absolutely. Measured on a 30k-row table under continuous insert/complete churn, the
-- findIncompleteIds() scan settles at 1 buffer.
--
-- This is the direct lever. OutboxPurger attacks the same mechanism from the other side by keeping
-- n_live_tup small; the two are complementary, and this one keeps working if the purge falls behind.
--
-- NOTE: an earlier version of this migration also split idx_event_publication_completion_date into
-- two partial indexes. That was reverted: measured against the V1 index on identical data it was
-- 18 vs 17 buffers to read, 2544 kB vs 2544 kB on disk, and within noise on insert and update time.
-- The index SHAPE is not the variable -- whether vacuum has kept up is.
ALTER TABLE event_publication SET (
    autovacuum_vacuum_scale_factor = 0.0,
    autovacuum_vacuum_threshold    = 2000
);
