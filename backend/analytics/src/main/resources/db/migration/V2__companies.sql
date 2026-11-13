-- Names and listings of the provider's company universe, refreshed on every
-- price ingest, so chart endpoints (heatmap, quotes) can label listings that
-- have no events yet.
CREATE TABLE companies (
    symbol     TEXT    NOT NULL,
    exchange   TEXT    NOT NULL,
    company_id TEXT    NOT NULL,
    name       TEXT    NOT NULL,
    kind       TEXT,
    currency   TEXT,
    is_primary BOOLEAN NOT NULL,
    PRIMARY KEY (symbol, exchange)
);
CREATE INDEX companies_symbol_idx ON companies (symbol);
