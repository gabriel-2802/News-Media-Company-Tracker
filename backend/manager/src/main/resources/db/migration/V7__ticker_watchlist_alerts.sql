-- Phase 8: ticker watchlists and event alerts.

-- Alert settings of TICKER subscriptions (null for TOPIC/STORY).
ALTER TABLE subscriptions
    ADD COLUMN min_impact DOUBLE PRECISION,
    ADD COLUMN topics     VARCHAR(1000);

CREATE INDEX idx_subscriptions_type_target ON subscriptions (type, target_id);

-- Structured fields for alert notifications (null for article notifications).
ALTER TABLE notifications
    ADD COLUMN type         VARCHAR(20) NOT NULL DEFAULT 'ARTICLE',
    ADD COLUMN symbol       VARCHAR(20),
    ADD COLUMN story_id     VARCHAR(64),
    ADD COLUMN company_id   VARCHAR(64),
    ADD COLUMN impact_score DOUBLE PRECISION,
    ADD COLUMN sentiment    DOUBLE PRECISION;

-- One alert per user and event (story + company), however often the event is re-published.
CREATE TABLE ticker_alerts (
    user_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    story_id     VARCHAR(64) NOT NULL,
    company_id   VARCHAR(64) NOT NULL,
    symbol       VARCHAR(20) NOT NULL,
    impact_score DOUBLE PRECISION,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, story_id, company_id)
);
