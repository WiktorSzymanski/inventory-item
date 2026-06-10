CREATE TABLE reservations (
    item_id        VARCHAR(64)  NOT NULL REFERENCES inventory_state(item_id),
    reservation_id VARCHAR(64)  NOT NULL,
    quantity       INT          NOT NULL CHECK (quantity > 0),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (item_id, reservation_id)
);

ALTER TABLE inventory_state DROP COLUMN reservations;
