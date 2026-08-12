-- Per-item artificial reservation cost. Set at item creation, read on every reserve, and
-- slept through inside the order transaction -- the counterpart to additional_bytes, which
-- inflates payload size but not the time a reserve takes. It exists so the TO-vs-ES comparison
-- can be run against expensive aggregate logic, not only the near-free arithmetic reserve does today.
--
-- Version 5 rather than the next free number, so the filename is identical on TO-1, TO-2 and
-- TO-4. TO-3 introduced the same column earlier as V2__reserve_delay.sql and is deliberately
-- NOT renumbered -- its database already records that version as applied. TO-2's V2 is its
-- NOTIFY trigger, which is why V2 could not be reused here. Flyway tolerates version gaps.
ALTER TABLE inventory_state
    ADD COLUMN reserve_delay_ms INT NOT NULL DEFAULT 0 CHECK (reserve_delay_ms >= 0);
