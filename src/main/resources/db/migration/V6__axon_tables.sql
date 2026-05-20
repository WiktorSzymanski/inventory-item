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
