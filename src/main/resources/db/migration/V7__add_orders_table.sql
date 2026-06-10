CREATE TABLE orders (
    order_id   VARCHAR(64)  PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
