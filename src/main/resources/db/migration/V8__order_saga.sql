-- The saga's durable state, and the only structural difference between this branch's schema and
-- TO-3's.
--
-- TO-3 needs no such table because it has no intermediate state to keep: an order is reserved in
-- one transaction, so it is either wholly reserved or wholly not. Here a LINE is a transaction, so
-- "line 3 of 8 is done" is a fact that has to survive a crash, and this row is where it lives. It
-- is the hand-written counterpart to Axon's `saga_entry` on the ES branches — Spring Modulith ships
-- an outbox, resubmission and Moments, but no saga abstraction, so association and lifecycle are
-- columns here rather than framework state.
--
-- V8 rather than the next free number on this branch alone: TO-3 stops at V7, and keeping the
-- numbering monotonic across the TO family means a file name never collides with a DIFFERENT
-- migration on a sibling branch the way V2 already does (NOTIFY trigger on TO-2, reserve_delay
-- here).
CREATE TABLE order_saga (
    -- The association. Axon looks a saga up by association value through saga_entry/association
    -- value entry; here the order id IS the key, because an order has exactly one saga.
    order_id       VARCHAR(64)  PRIMARY KEY REFERENCES orders(order_id),
    correlation_id UUID         NOT NULL,

    -- The ordered line list, as a JSONB ARRAY: [{"itemId": "...", "quantity": n}, ...].
    --
    -- NOT the {"<itemId>": qty} object that `orders.items` uses. That shape mirrors the ES branch's
    -- orders projection and is right for it, but it is a map — it has no order and cannot hold the
    -- same item twice — and this saga's whole cursor is a POSITION in this list. Reusing the
    -- converter would silently renumber the steps of any order whose lines are not already sorted
    -- and unique.
    lines          JSONB        NOT NULL,

    -- One cursor, read in two directions. While RUNNING it is the next line to reserve, counting
    -- up; while COMPENSATING it counts back down as lines are released. In BOTH phases the lines
    -- currently held are exactly lines[0 .. current_index-1], which is why compensation needs no
    -- column of its own.
    current_index  INT          NOT NULL DEFAULT 0 CHECK (current_index >= 0),

    status         VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',

    -- The failure, twice: once as a human message that ends up on the order row, and once as the
    -- CLASSIFICATION that `orders_completed{reason}` is tagged with.
    --
    -- Both are needed because the two are read at different times by different code. The message is
    -- produced where the reserve failed; the metric is emitted N transactions later, once
    -- compensation has walked every line back, by a handler that has only this row to go on.
    -- Re-deriving the class from the message there would mean pattern-matching an exception's
    -- toString, which is exactly the kind of coupling that turns a reworded message into a silently
    -- mis-tagged histogram.
    failure_reason TEXT,
    failure_code   VARCHAR(32),

    -- The ADMISSION instant, captured on the HTTP thread before the accept transaction opens --
    -- not a timestamp taken inside it, and not when OrderCreatedEvent was delivered. Both
    -- `order_e2e_time` and `saga_lifetime` are measured from here, so it has to start where TO-3's
    -- equivalent nanoTime does or every end-to-end sample on this branch would be short by the
    -- accept transaction's own duration. The DEFAULT is a safety net for a hand-inserted row and is
    -- never what the application writes.
    started_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Guards a step that is delivered twice. The step handlers also carry an explicit
    -- (status, current_index) predicate, which is the primary guard; this catches the narrower race
    -- of two deliveries of the SAME step arriving concurrently, where both would pass that predicate.
    version        BIGINT       NOT NULL DEFAULT 0
);

-- Finds sagas that are still in flight without scanning the ended ones, which are the overwhelming
-- majority under load. Partial, so the index stays the size of the in-flight set rather than of the
-- table — the same reasoning V7 applies to event_publication's completion_date index, arrived at
-- from the other direction.
--
-- Nothing on the hot path uses it: every step handler reads by primary key. It exists for
-- operational queries — "is anything stuck?" — and for the recovery check in the branch's runbook.
CREATE INDEX idx_order_saga_unfinished ON order_saga (status) WHERE status <> 'ENDED';
