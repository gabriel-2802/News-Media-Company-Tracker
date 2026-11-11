// ═══════════════════════════════════════════════════════════════════════════
//  Baseline schema — squashes the old V1–V9 (generic news era).
//
//  Schema only: no seed data. Banking sources, topics and the company
//  universe are seeded by later migrations (V2 sources, V3 topics,
//  V4 companies), generated in Phases 2, 4 and 5 of
//  documentation/FINTECH_TRANSFORMATION_PLAN.md.
//
//  Property names are camelCase to match the Spring Data Neo4j entities.
//  Every statement is idempotent (IF NOT EXISTS).
// ═══════════════════════════════════════════════════════════════════════════

// ── NewsSource ──────────────────────────────────────────────────────────────
CREATE CONSTRAINT news_source_name_unique IF NOT EXISTS
FOR (n:NewsSource) REQUIRE n.name IS UNIQUE;

CREATE CONSTRAINT news_source_rss_url_unique IF NOT EXISTS
FOR (n:NewsSource) REQUIRE n.rssUrl IS UNIQUE;

CREATE INDEX news_source_base_url_idx IF NOT EXISTS
FOR (n:NewsSource) ON (n.baseUrl);

// ── Article ─────────────────────────────────────────────────────────────────
CREATE CONSTRAINT article_url_unique IF NOT EXISTS
FOR (a:Article) REQUIRE a.url IS UNIQUE;

CREATE INDEX article_published_at_idx IF NOT EXISTS
FOR (a:Article) ON (a.publishedAt);

CREATE INDEX article_author_idx IF NOT EXISTS
FOR (a:Article) ON (a.author);

CREATE INDEX article_title_idx IF NOT EXISTS
FOR (a:Article) ON (a.title);

// ── Topic ───────────────────────────────────────────────────────────────────
CREATE CONSTRAINT topic_name_unique IF NOT EXISTS
FOR (t:Topic) REQUIRE t.name IS UNIQUE;

// ── Full-text search ────────────────────────────────────────────────────────
CREATE FULLTEXT INDEX story_title_fulltext_idx IF NOT EXISTS
FOR (s:Story) ON EACH [s.title];

CREATE FULLTEXT INDEX article_search_fulltext_idx IF NOT EXISTS
FOR (a:Article) ON EACH [a.title, a.bodyText];
