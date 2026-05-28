-- serialized_saga was TEXT but JdbcSagaStore uses setBytes/getBytes, which requires BYTEA.
-- Stale saga data (stored as \x<hex> text) is cleared before the column type change.
TRUNCATE TABLE association_value_entry;
TRUNCATE TABLE saga_entry;
ALTER TABLE saga_entry ALTER COLUMN serialized_saga TYPE BYTEA USING NULL;
