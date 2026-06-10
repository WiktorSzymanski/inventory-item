ALTER TABLE event_publication
    ADD COLUMN IF NOT EXISTS status                 TEXT,
    ADD COLUMN IF NOT EXISTS completion_attempts    INT,
    ADD COLUMN IF NOT EXISTS last_resubmission_date TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON event_publication USING hash(serialized_event);
