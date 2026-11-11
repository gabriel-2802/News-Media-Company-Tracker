// ═══════════════════════════════════════════════════════════════════════════
//  Phase 5 correction — remove SEC filers that are subsidiaries or note
//  issuers of curated companies, which an earlier V5 build seeded as banks
//  (e.g. "Barclays Bank Plc" files for iPath ETNs such as DJP).
//  build_universe.py now skips them, so on a fresh database V5 no longer
//  creates them and this migration is a no-op.
// ═══════════════════════════════════════════════════════════════════════════

MATCH (c:Company)
WHERE c.name IN ["Barclays Bank Plc", "Banco Santander, S.a.", "Banco Santander (brasil) S.a.", "Banco Santander Chile"]
DETACH DELETE c;

// Tickers left without any company.
MATCH (t:Ticker)
WHERE NOT EXISTS { (:Company)-[:LISTED_AS]->(t) }
DETACH DELETE t;
