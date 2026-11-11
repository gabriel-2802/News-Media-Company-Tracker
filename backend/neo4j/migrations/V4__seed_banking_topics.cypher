// ═══════════════════════════════════════════════════════════════════════════
//  Phase 4 — banking topic taxonomy.
//
//  Must match LABELS in backend/workers/classifier/training/taxonomy.py
//  (the classifier's label2id.json). The provider rejects topics that are
//  not seeded here, so a label added to the model needs a migration too.
//  Every Topic gets an `id` (Spring Data Neo4j entity identity).
// ═══════════════════════════════════════════════════════════════════════════

UNWIND [
  "earnings_results",
  "m_and_a_investments",
  "monetary_policy_rates",
  "regulation_legal_enforcement",
  "debt_capital_markets",
  "dividends_buybacks",
  "analyst_ratings",
  "leadership_personnel",
  "fx_currencies",
  "macro_economy",
  "markets_stock_moves",
  "company_product_news",
  "other"
] AS topicName
MERGE (t:Topic {name: topicName})
  ON CREATE SET t.id = randomUUID();
