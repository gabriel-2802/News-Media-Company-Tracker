// ═══════════════════════════════════════════════════════════════════════════
//  Phase 3 — entity schema for the investor graph.
//
//  (:Company)-[:LISTED_AS {primary}]->(:Ticker)
//  (:Article)-[:MENTIONS {count, salience, inTitle, sentiment}]->(:Company)
//  (:Story)-[:AFFECTS {firstSeen, impactScore}]->(:Company)
//  (:Article)-[:HAS_TOPIC {score}]->(:Topic)          // now multi-label
//
//  Schema only; the company universe is seeded by a later migration.
//  Idempotent (IF NOT EXISTS).
// ═══════════════════════════════════════════════════════════════════════════

// ── Company ─────────────────────────────────────────────────────────────────
CREATE CONSTRAINT company_id_unique IF NOT EXISTS
FOR (c:Company) REQUIRE c.id IS UNIQUE;

CREATE CONSTRAINT company_lei_unique IF NOT EXISTS
FOR (c:Company) REQUIRE c.lei IS UNIQUE;

CREATE INDEX company_name_idx IF NOT EXISTS
FOR (c:Company) ON (c.name);

CREATE INDEX company_kind_idx IF NOT EXISTS
FOR (c:Company) ON (c.kind);

// Name + aliases search for the enricher's entity linking.
CREATE FULLTEXT INDEX company_name_fulltext_idx IF NOT EXISTS
FOR (c:Company) ON EACH [c.name, c.aliases];

// ── Ticker ──────────────────────────────────────────────────────────────────
CREATE CONSTRAINT ticker_symbol_exchange_unique IF NOT EXISTS
FOR (t:Ticker) REQUIRE (t.symbol, t.exchange) IS UNIQUE;

CREATE INDEX ticker_symbol_idx IF NOT EXISTS
FOR (t:Ticker) ON (t.symbol);

// ── Story ───────────────────────────────────────────────────────────────────
CREATE CONSTRAINT story_id_unique IF NOT EXISTS
FOR (s:Story) REQUIRE s.id IS UNIQUE;

CREATE INDEX story_last_updated_idx IF NOT EXISTS
FOR (s:Story) ON (s.lastUpdated);

// ── Relationship indexes for time-bounded chart queries ─────────────────────
CREATE INDEX affects_first_seen_idx IF NOT EXISTS
FOR ()-[r:AFFECTS]-() ON (r.firstSeen);

CREATE INDEX affects_impact_idx IF NOT EXISTS
FOR ()-[r:AFFECTS]-() ON (r.impactScore);
