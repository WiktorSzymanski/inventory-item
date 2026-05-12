DROP TABLE IF EXISTS outbox;

ALTER TABLE inventory_state DROP COLUMN IF EXISTS version;

ALTER TABLE inventory_state
    ADD COLUMN IF NOT EXISTS last_event_revision BIGINT NOT NULL DEFAULT -1;
