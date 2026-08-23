-- The notification carries the EVENT, not a pointer to it.
--
-- V2 sent `NEW.id` and the consumer read the row back. That read is the whole reason this branch
-- has a V7 (autovacuum tuning) and a V8 (a seq cursor over a partial index): completing a
-- publication writes an indexed column, so every delivery leaves a dead entry in exactly the
-- region the next "is anything undelivered?" query must walk. Three migrations to make a query
-- cheap that only exists because the notification was a doorbell instead of a letter.
--
-- Sending the payload removes the query. The consumer claims the row and invokes the listener from
-- the notification alone; `event_publication` is still written, still the durable record, and still
-- read by the fallback sweep -- but not on the delivery path.
--
-- V10 rather than the next free number: TO-2-fix-A already owns V9__event_publication_xact_id.sql,
-- and a filename must mean the same thing on every sibling branch (see V6's header).
CREATE OR REPLACE FUNCTION notify_event_publication_inserted() RETURNS trigger AS $$
DECLARE
    msg text;
BEGIN
    -- serialized_event is embedded as a JSON *string*, not as ::json. It is always Jackson output
    -- so ::json would parse -- but a cast that can raise sits inside the writer's transaction, and
    -- a malformed payload would abort the business write rather than merely fail to deliver.
    -- Escaping costs ~10% of the message and cannot fail.
    msg := json_build_object(
        'id',         NEW.id,
        'eventType',  NEW.event_type,
        'listenerId', NEW.listener_id,
        'event',      NEW.serialized_event
    )::text;

    -- pg_notify's payload limit is 8000 bytes and exceeding it raises, which -- because NOTIFY is
    -- queued at commit -- would fail the ALREADY-DECIDED business transaction. So the size test is
    -- mandatory, not defensive. An oversize event simply gets no notification: the row is still
    -- committed and IncompleteEventRepublisher delivers it on its next pass, at the cost of one
    -- sweep interval of latency. `outbox.sweep.rescued` counts those, so the fallback is visible
    -- without instrumenting the trigger.
    --
    -- This is reachable in the benchmark: PAYLOAD_BYTES pads InventoryCreatedEvent at seed time,
    -- and the C10/C11 campaign cells set it to 1 MiB. It is NOT reachable on the order path, which
    -- sends no additionalBytesSize.
    IF octet_length(msg) <= 7900 THEN
        PERFORM pg_notify('event_publication_notify', msg);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
