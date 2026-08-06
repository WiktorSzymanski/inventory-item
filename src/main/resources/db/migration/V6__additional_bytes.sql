-- Benchmark lever: per-item padding stored ON THE ROW, so every reserve's read-modify-write
-- carries it.
--
-- Without this column the k6 PAYLOAD_BYTES knob rides only on the seed-time
-- InventoryCreatedEvent and never touches TO's reserve path, while on ES the same bytes sit
-- on the aggregate and are rehydrated on every load, written into every snapshot, and
-- deep-copied per command. The sweep would then measure ES only, and TO's flat line would
-- read as robustness rather than as absence.
--
-- A 1 MiB value is TOASTed, so the main-heap row stays narrow and the bytes are rewritten
-- out-of-line on every update. That cost -- WAL volume and bloat -- is the measurement.
--
-- Version 6 rather than the next free number, so the filename is identical on all four TO
-- branches: TO-2 already uses V2 for its NOTIFY trigger and TO-3 for reserve_delay.
ALTER TABLE inventory_state
    ADD COLUMN additional_bytes TEXT NOT NULL DEFAULT '';
