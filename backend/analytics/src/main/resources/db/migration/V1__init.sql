-- Local read model of the provider's Story-[:AFFECTS]->Company events,
-- fed by event.upserted messages (and rebuildable from GET /api/events).
CREATE TABLE events (
    story_id      TEXT        NOT NULL,
    company_id    TEXT        NOT NULL,
    company_name  TEXT,
    symbol        TEXT,
    exchange      TEXT,
    currency      TEXT,
    story_title   TEXT,
    first_seen    TIMESTAMPTZ,
    impact_score  DOUBLE PRECISION,
    article_count INTEGER,
    sentiment     DOUBLE PRECISION,
    topics        TEXT[]      NOT NULL DEFAULT '{}',
    version       BIGINT      NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, company_id)
);
CREATE INDEX events_listing_first_seen_idx ON events (symbol, exchange, first_seen);

-- Removed events, so an older upsert arriving after the delete cannot revive them.
CREATE TABLE event_tombstones (
    story_id   TEXT   NOT NULL,
    company_id TEXT   NOT NULL,
    version    BIGINT NOT NULL,
    PRIMARY KEY (story_id, company_id)
);

-- Daily bars per listing (symbol + ISO 10383 MIC).
CREATE TABLE prices_daily (
    symbol    TEXT             NOT NULL,
    exchange  TEXT             NOT NULL,
    day       DATE             NOT NULL,
    open      DOUBLE PRECISION,
    high      DOUBLE PRECISION,
    low       DOUBLE PRECISION,
    close     DOUBLE PRECISION NOT NULL,
    volume    BIGINT,
    source    TEXT             NOT NULL,
    PRIMARY KEY (symbol, exchange, day)
);

-- Per-listing ingest bookkeeping.
CREATE TABLE ingest_state (
    symbol     TEXT NOT NULL,
    exchange   TEXT NOT NULL,
    last_day   DATE,
    last_run   TIMESTAMPTZ,
    last_error TEXT,
    PRIMARY KEY (symbol, exchange)
);

-- Market-model event study per event.
CREATE TABLE event_impact (
    story_id        TEXT             NOT NULL,
    company_id      TEXT             NOT NULL,
    symbol          TEXT             NOT NULL,
    exchange        TEXT             NOT NULL,
    event_day       DATE             NOT NULL,
    benchmark       TEXT             NOT NULL,
    alpha           DOUBLE PRECISION,
    beta            DOUBLE PRECISION,
    ar0             DOUBLE PRECISION,
    car1            DOUBLE PRECISION,
    car5            DOUBLE PRECISION,
    p_value         DOUBLE PRECISION,
    estimation_days INTEGER,
    complete        BOOLEAN          NOT NULL,   -- true once the [-5, +5] window is available
    computed_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, company_id)
);

-- Lease-based job lock: one replica runs a scheduled job at a time; a
-- crashed holder's lease simply expires.
CREATE TABLE job_locks (
    name         TEXT        PRIMARY KEY,
    owner        TEXT        NOT NULL,
    locked_until TIMESTAMPTZ NOT NULL
);
