-- Money is always integer minor units (paise, cents). Never floating point.

CREATE TABLE merchants (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    api_key_hash  TEXT NOT NULL UNIQUE          -- SHA-256 of the API key; the key itself is never stored
);

CREATE TABLE payment_intents (
    id                TEXT PRIMARY KEY,
    merchant_id       TEXT NOT NULL REFERENCES merchants(id),
    amount            BIGINT NOT NULL CHECK (amount > 0),
    currency          TEXT NOT NULL,
    capture_method    TEXT NOT NULL CHECK (capture_method IN ('automatic', 'manual')),
    status            TEXT NOT NULL,
    amount_captured   BIGINT NOT NULL DEFAULT 0,
    amount_refunded   BIGINT NOT NULL DEFAULT 0,
    last_error_code   TEXT,
    version           INT NOT NULL DEFAULT 0,  -- optimistic locking: concurrent transitions can't both win
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CHECK (amount_captured <= amount),
    CHECK (amount_refunded <= amount_captured)
);
CREATE INDEX payment_intents_merchant ON payment_intents (merchant_id, created_at DESC);

CREATE TABLE refunds (
    id                 TEXT PRIMARY KEY,
    payment_intent_id  TEXT NOT NULL REFERENCES payment_intents(id),
    amount             BIGINT NOT NULL CHECK (amount > 0),
    created_at         TIMESTAMPTZ NOT NULL
);

-- Double-entry ledger: every transaction's entries sum to zero per currency.
CREATE TABLE ledger_transactions (
    id          TEXT PRIMARY KEY,
    type        TEXT NOT NULL,              -- capture | refund
    reference   TEXT NOT NULL,              -- payment intent or refund id
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  TEXT NOT NULL REFERENCES ledger_transactions(id),
    account         TEXT NOT NULL,          -- e.g. merchant:m_a:balance, platform:fees, network:card_clearing
    currency        TEXT NOT NULL,
    amount          BIGINT NOT NULL CHECK (amount <> 0)
);
CREATE INDEX ledger_entries_account ON ledger_entries (account, currency);

CREATE TABLE idempotency_keys (
    merchant_id      TEXT NOT NULL REFERENCES merchants(id),
    key              TEXT NOT NULL,
    fingerprint      TEXT NOT NULL,         -- hash of method + path + body: same key, different request = error
    status           TEXT NOT NULL CHECK (status IN ('in_progress', 'completed')),
    response_status  INT,
    response_body    TEXT,
    locked_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (merchant_id, key)
);

CREATE TABLE webhook_endpoints (
    id           TEXT PRIMARY KEY,
    merchant_id  TEXT NOT NULL REFERENCES merchants(id),
    url          TEXT NOT NULL,
    secret       TEXT NOT NULL,             -- needed to sign; a production system would encrypt this at rest
    created_at   TIMESTAMPTZ NOT NULL
);

-- Transactional outbox: events are written in the same transaction as the state change.
CREATE TABLE events (
    id           TEXT PRIMARY KEY,
    merchant_id  TEXT NOT NULL REFERENCES merchants(id),
    type         TEXT NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);
CREATE TABLE webhook_deliveries (
    id               BIGSERIAL PRIMARY KEY,
    event_id         TEXT NOT NULL REFERENCES events(id),
    endpoint_id      TEXT NOT NULL REFERENCES webhook_endpoints(id),
    status           TEXT NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed')),
    attempts         INT NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    last_error       TEXT,
    UNIQUE (event_id, endpoint_id)
);
CREATE INDEX webhook_deliveries_due ON webhook_deliveries (status, next_attempt_at);
