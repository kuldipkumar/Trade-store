-- Trade store schema (docs/05-lld.md section 5, ADR-003, ADR-005).

-- Latest version of each trade: the system of record (A-36).
CREATE TABLE trades (
    trade_id         VARCHAR(64) PRIMARY KEY,
    version          INTEGER     NOT NULL CHECK (version >= 1),
    counter_party_id VARCHAR(64) NOT NULL,
    book_id          VARCHAR(64) NOT NULL,
    maturity_date    DATE        NOT NULL,
    created_date     DATE        NOT NULL,
    expired          BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at       TIMESTAMPTZ NOT NULL
);

-- The expiry job only scans unexpired trades (A-52).
CREATE INDEX idx_trades_unexpired_maturity ON trades (maturity_date) WHERE expired = FALSE;
-- GET /trades?expired= (US-403).
CREATE INDEX idx_trades_expired ON trades (expired, trade_id);

-- Transactional outbox: one row per outcome, written in the same transaction as any trade change,
-- relayed to MongoDB and then deleted (ADR-005).
CREATE TABLE outbox_events (
    id         BIGSERIAL   PRIMARY KEY,
    event_id   UUID        NOT NULL UNIQUE,
    trade_id   VARCHAR(64),
    outcome    VARCHAR(16) NOT NULL,
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
