-- Inventory projection (read model)
CREATE TABLE inventory_state (
    item_id             VARCHAR(64) PRIMARY KEY,
    available_qty       INT         NOT NULL CHECK (available_qty >= 0),
    last_event_revision BIGINT      NOT NULL DEFAULT -1,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Orders projection (read model)
CREATE TABLE orders (
    order_id       VARCHAR(64) PRIMARY KEY,
    user_id        VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    items          JSONB       NOT NULL DEFAULT '{}',
    failure_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Axon JDBC event store
CREATE TABLE domain_event_entry (
    global_index         BIGSERIAL    PRIMARY KEY,
    aggregate_identifier VARCHAR(255) NOT NULL,
    sequence_number      BIGINT       NOT NULL,
    type                 VARCHAR(255),
    event_identifier     VARCHAR(255) NOT NULL UNIQUE,
    meta_data            BYTEA,
    payload              BYTEA        NOT NULL,
    payload_revision     VARCHAR(255),
    payload_type         VARCHAR(255) NOT NULL,
    time_stamp           VARCHAR(255) NOT NULL,
    UNIQUE (aggregate_identifier, sequence_number)
);

CREATE TABLE snapshot_event_entry (
    aggregate_identifier VARCHAR(255) NOT NULL,
    sequence_number      BIGINT       NOT NULL,
    type                 VARCHAR(255) NOT NULL,
    event_identifier     VARCHAR(255) NOT NULL UNIQUE,
    meta_data            BYTEA,
    payload              BYTEA        NOT NULL,
    payload_revision     VARCHAR(255),
    payload_type         VARCHAR(255) NOT NULL,
    time_stamp           VARCHAR(255) NOT NULL,
    PRIMARY KEY (aggregate_identifier, sequence_number)
);

CREATE TABLE token_entry (
    processor_name VARCHAR(255) NOT NULL,
    segment        INTEGER      NOT NULL,
    token          BYTEA,
    token_type     VARCHAR(255),
    timestamp      VARCHAR(255),
    owner          VARCHAR(255),
    PRIMARY KEY (processor_name, segment)
);

-- Axon saga store (serialized_saga is BYTEA: JdbcSagaStore uses setBytes/getBytes)
CREATE TABLE saga_entry (
    saga_id         VARCHAR(255) NOT NULL PRIMARY KEY,
    revision        VARCHAR(255),
    saga_type       VARCHAR(255),
    serialized_saga BYTEA
);

CREATE TABLE association_value_entry (
    id                BIGSERIAL    PRIMARY KEY,
    association_key   VARCHAR(255) NOT NULL,
    association_value VARCHAR(255),
    saga_id           VARCHAR(255),
    saga_type         VARCHAR(255)
);

CREATE INDEX idx_asso_saga   ON association_value_entry (saga_id, association_key, association_value);
CREATE INDEX idx_asso_lookup ON association_value_entry (association_key, association_value);
