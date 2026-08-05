-- Per-item artificial reservation cost. Set at item creation, read on every reserve, and
-- slept through inside the order transaction — the counterpart to additionalBytesSize, which
-- inflates payload size but not the time a reserve takes. It exists so the TO-vs-ES comparison
-- can be run against expensive aggregate logic, not only the near-free arithmetic reserve does today.
ALTER TABLE inventory_state
    ADD COLUMN reserve_delay_ms INT NOT NULL DEFAULT 0 CHECK (reserve_delay_ms >= 0);
