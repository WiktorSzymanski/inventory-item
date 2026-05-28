CREATE TABLE saga_entry (
    saga_id         VARCHAR(255) NOT NULL PRIMARY KEY,
    revision        VARCHAR(255),
    saga_type       VARCHAR(255),
    serialized_saga TEXT
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
