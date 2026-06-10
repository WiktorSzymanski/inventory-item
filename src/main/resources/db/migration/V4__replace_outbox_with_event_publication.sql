DROP TABLE IF EXISTS outbox;

CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID         NOT NULL PRIMARY KEY,
    listener_id      VARCHAR(512) NOT NULL,
    event_type       VARCHAR(512) NOT NULL,
    serialized_event TEXT         NOT NULL,
    publication_date TIMESTAMPTZ  NOT NULL,
    completion_date  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_event_publication_completion_date
    ON event_publication (completion_date);

CREATE INDEX IF NOT EXISTS idx_event_publication_pub_date_listener
    ON event_publication (publication_date, listener_id);
