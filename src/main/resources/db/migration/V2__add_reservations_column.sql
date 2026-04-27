ALTER TABLE inventory_state
    ADD COLUMN reservations JSONB NOT NULL DEFAULT '{}';
