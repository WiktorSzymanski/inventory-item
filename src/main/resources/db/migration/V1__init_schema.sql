CREATE TABLE inventory_state (
    item_id       VARCHAR(64)  PRIMARY KEY,
    available_qty INT          NOT NULL CHECK (available_qty >= 0),
    version       BIGINT       NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    item_id        VARCHAR(64)  NOT NULL REFERENCES inventory_state(item_id),
    reservation_id VARCHAR(64)  NOT NULL,
    quantity       INT          NOT NULL CHECK (quantity > 0),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (item_id, reservation_id)
);

CREATE TABLE orders (
    order_id       VARCHAR(64)  PRIMARY KEY,
    user_id        VARCHAR(64)  NOT NULL,
    items          JSONB        NOT NULL DEFAULT '{}',
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    failure_reason TEXT,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Spring Modulith 2 event publication registry
CREATE TABLE event_publication (
    id                     UUID         NOT NULL PRIMARY KEY,
    listener_id            VARCHAR(512) NOT NULL,
    event_type             VARCHAR(512) NOT NULL,
    serialized_event       TEXT         NOT NULL,
    publication_date       TIMESTAMPTZ  NOT NULL,
    completion_date        TIMESTAMPTZ,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMPTZ
);

CREATE INDEX idx_event_publication_completion_date
    ON event_publication (completion_date);

CREATE INDEX idx_event_publication_pub_date_listener
    ON event_publication (publication_date, listener_id);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash(serialized_event);
