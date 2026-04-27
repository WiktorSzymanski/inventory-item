CREATE TABLE IF NOT EXISTS inventory_state (
    item_id       VARCHAR(64)  PRIMARY KEY,
    available_qty INT          NOT NULL CHECK (available_qty >= 0),
    version       BIGINT       NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbox (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id  VARCHAR(64)  NOT NULL,
    event_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
    event_type    VARCHAR(128) NOT NULL,
    payload_json  JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count INT          NOT NULL DEFAULT 0,
    last_error    TEXT
);

CREATE INDEX idx_outbox_status_created ON outbox (status, created_at)
    WHERE status = 'PENDING';
--
-- INSERT INTO inventory_state (item_id, available_qty, version)
-- VALUES ('ITEM-001', 1000, 0)
-- ON CONFLICT DO NOTHING;
