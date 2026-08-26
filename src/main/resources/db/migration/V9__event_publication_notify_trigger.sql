-- NOTIFY on every outbox insert. NOTHING on this branch acts on it.
--
-- TO-1-2 exists to price the notification channel on its own. TO-1 delivers by polling and TO-2
-- delivers on NOTIFY, so the two differ in the wake-up AND in everything the push path implies.
-- Here the poller keeps full ownership of delivery, unchanged, and the trigger plus a LISTEN
-- session are added alongside it — the difference against a plain TO-1 run is then the cost of
-- raising and receiving the notification and nothing else.
--
-- pg_notify inside an AFTER INSERT trigger queues the message in the inserting transaction and
-- PostgreSQL emits it only at COMMIT, so this cannot make a subscriber see a row that never
-- lands. The payload is the publication id, matching TO-2's V2 byte for byte, so the channel
-- carries the same traffic there and here — but see PostgresNotificationListener: this branch
-- reads none of it.
CREATE OR REPLACE FUNCTION notify_event_publication_inserted() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('event_publication_notify', NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_publication_notify
    AFTER INSERT ON event_publication
    FOR EACH ROW EXECUTE FUNCTION notify_event_publication_inserted();
