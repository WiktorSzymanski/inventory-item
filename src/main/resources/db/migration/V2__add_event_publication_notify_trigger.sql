-- NOTIFY fires only when the inserting transaction commits, so the push path inherits the
-- outbox's atomicity. The payload is just the publication id; the processor re-reads the row,
-- which keeps us clear of the ~8 kB pg_notify payload limit.
CREATE OR REPLACE FUNCTION notify_event_publication_inserted() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('event_publication_notify', NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_publication_notify
    AFTER INSERT ON event_publication
    FOR EACH ROW EXECUTE FUNCTION notify_event_publication_inserted();
