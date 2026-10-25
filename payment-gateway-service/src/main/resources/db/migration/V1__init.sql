CREATE TABLE payments (
    id                BIGSERIAL PRIMARY KEY,
    payment_id        VARCHAR(32)   NOT NULL UNIQUE,
    idempotency_key   VARCHAR(128)  NOT NULL UNIQUE,
    request_hash      VARCHAR(64)   NOT NULL,
    order_id          VARCHAR(64)   NOT NULL,
    amount            NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency          VARCHAR(3)    NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    gateway_reference VARCHAR(64),
    failure_reason    VARCHAR(255),
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_payments_order_id ON payments (order_id);
CREATE INDEX idx_payments_status ON payments (status);

CREATE TABLE payment_events (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  VARCHAR(32)  NOT NULL REFERENCES payments (payment_id),
    event_type  VARCHAR(40)  NOT NULL,
    from_status VARCHAR(20),
    to_status   VARCHAR(20),
    detail      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_payment_events_payment_id ON payment_events (payment_id);

CREATE TABLE processed_callbacks (
    event_id    VARCHAR(128) PRIMARY KEY,
    payment_id  VARCHAR(32)  NOT NULL REFERENCES payments (payment_id),
    status      VARCHAR(20)  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);
