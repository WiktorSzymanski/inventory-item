CREATE TABLE orders (
    order_id   VARCHAR(64)  PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
