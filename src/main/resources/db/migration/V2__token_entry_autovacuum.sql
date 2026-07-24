-- Safety net for token_entry TOAST bloat, kept identical to ES-3-optimistic so both variants run the
-- same schema and differ only in the aggregate lock.
--
-- Every rolled-back append still burns a non-transactional BIGSERIAL global_index, leaving permanent
-- gaps (frequent under the lock-free ES-3-optimistic command side, rare here). The tracking
-- processors' GapAwareTrackingToken records those gaps and the token (BYTEA -> TOAST) is rewritten
-- on every batch. At benchmark throughput this produced ~1M UPDATEs against ~35 rows and bloated the
-- token_entry TOAST into double-digit GB because default autovacuum could not keep pace.
--
-- The gap window is already tightened in AxonConfig.eventStorageEngine (max-gap-offset / gap-timeout)
-- so tokens stay small; this makes autovacuum aggressive enough that dead TOAST space is reclaimed for
-- reuse continuously and the table can never balloon again. scale_factor=0 + a low threshold means
-- "vacuum after N dead tuples regardless of table size"; cost_delay=0 removes throttling.
ALTER TABLE token_entry SET (
    autovacuum_vacuum_scale_factor = 0.0,
    autovacuum_vacuum_threshold = 50,
    autovacuum_vacuum_cost_delay = 0,
    autovacuum_analyze_scale_factor = 0.0,
    autovacuum_analyze_threshold = 50,
    toast.autovacuum_vacuum_scale_factor = 0.0,
    toast.autovacuum_vacuum_threshold = 50,
    toast.autovacuum_vacuum_cost_delay = 0
);
