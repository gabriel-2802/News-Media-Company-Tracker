# Plan: News Monitor → Banking / Fintech Investor News Backend

**Date:** 2026-11-10
**Scope:** backend only (manager, provider, workers, datastores). No frontend work.

## Decisions taken

| Question | Decision |
|---|---|
| Wipe scope | **Everything**: Neo4j, Qdrant, Postgres (users, roles, subscriptions, notifications). Re-seed roles and an admin. |
| Market universe | **Global English (US / UK / EU)**: banks, fintech/payments, regulators, central banks |
| Classifier training | **Public datasets only** (no LLM labelling). A small hand-labelled set of our own articles is used **for evaluation only** |
| Extras in scope | Entity and ticker linking · price data with event overlays · FinBERT sentiment · ticker watchlists and alerts |
| Service layout | Microservices: every HTTP service in Java (Spring Boot), Python only for the ML/scraping workers. Charts in a separate `analytics` service with its own `market` database on the existing Postgres server |
| Regulation gap | Fix with a rule layer on top of the model (stays public-data only), not LLM labelling |
| Commits | No Claude attribution in commit messages |

## Progress log

| Phase | Status | Commit(s) | Notes |
|---|---|---|---|
| 0. Baseline and wipe | Done | `6ca0d42`, `b046dd8` | Neo4j migrations squashed to a schema-only V1. Qdrant was already empty (the old API key had been revoked; replaced). |
| 1. Remove political data | Done | `d13dfb1` | `NewsSource` gained `category`, `region`, `paywalled`, `language`. |
| 2. Banking sources | Done | `428343a` | 41 candidates → 27 PASS seeded via generated V2. Per-source intervals (15/30/60 min) with an atomic Cypher claim. |
| 3. Graph model | Done | `6846a66` | Multi-label `HAS_TOPIC {score}`, Company/Ticker, Cypher-managed MENTIONS/AFFECTS. |
| 4. Classifier | Done | `1fe08a9`, `33b3954` | TFNT macro-F1 0.906, Reuters 0.915 (0.902 / 0.913 with rules). In-domain evaluation still needs ~300 hand labels. |
| 5. Enricher | Done | `1736add`, Phase 5 commit | 356 companies / 345 tickers (V5 + V6 correction). Enricher worker (dictionary linking + sentence-level FinBERT), `GET /api/companies`, `PATCH /api/articles/enrichment`, derived Story→Company events with impact score. Verified end to end with all three workers. |
| Refinement (live run) | Done | Refinement commit | First real scrape (140 articles, 7 sources) through all workers. Fixed: deadlocks between concurrent story/enrichment writes (500s), common-phrase company names ("financial institutions"), cookie-banner article bodies, central-bank releases forced to regulation; added earnings / ratings / dividend / leadership / central-bank monetary rules. |
| 9. Clustering re-validation | Done (moved ahead of 6–8) | Phase 9 commit | Publish-date window, same-publisher + 0.75 rule for official releases, bank/fintech guard, top-5 candidates, base threshold 0.60. On 140 real articles: biggest story 21 → 6 articles, max span 111 → 1 day, mega-story events gone. `make recluster` rebuilds stories. |
| 6. Analytics service | Done (prices need an API key) | Phase 6 commit | Spring Boot service with its own `market` DB; provider publishes `event.upserted` after commit, `GET /api/events` and the impact write-back; version-guarded read model; pluggable daily-price ingest (Twelve Data); market-model event study with write-back. Verified end to end: rebuild 234/234 events, 90 live updates via RabbitMQ, event study + authenticated write-back on synthetic prices (β 1.22 vs 1.2, AR −3.85% vs −4% planted). |
| 6b. Live market data | Done | Live-test commit | With a Twelve Data key: two live bugs fixed (Nasdaq lookup: XNAS rejected, now US-by-country; errors are HTTP 4xx, not 200). 315 US listings / 153,981 bars, no gaps; 59 event studies cross-checked against an independent NumPy/SciPy implementation (max diff 2·10⁻¹³) and verified on Neo4j; unchanged results no longer re-written. |
| 7. Chart APIs | Done | Phase 7 commit | Analytics `/api/charts`: price (downsampling), events (setMarkers-ready, snapped to day 0), overlay, sentiment, news volume, event impact with AR path, topic volume, sector heatmap, quotes; ETag + 60 s cache; provider company graph. 23 analytics + 19 provider tests; live on real data, markers match event-study day 0 and CAR for all 59 studied events. |
| 8. Watchlists and alerts | Done | `994c6e8` | Manager `V7`: TICKER subscriptions with `min_impact` / topic filter, `ticker_alerts` dedup, structured alert notifications. `PUT/DELETE/GET /api/users/me/watchlist` (symbols validated against provider companies; quotes + latest event from analytics, degrading to null when analytics is down). Manager consumes `event.upserted` (`manager.events`) and alerts once per (user, story, company). 9 manager tests (first in the module) + 1 analytics; live: real watchlist with quotes in 0.21 s, duplicate delivery → one alert, real provider re-publish of the AXP enforcement event → one topic-filtered alert. |
| 10. Testing and verification | Done | Phase 10 commit | `make test` (55 Java + 86 worker tests), `make e2e`, `make check-sources` + nightly workflow. New: provider event-version ordering; analytics rebuild paging/idempotency and a broker test with the provider's message shape; scraper feed/fetch/robots/extraction fixtures; 50-headline linker golden set; classifier smoke test. Found and fixed: Atom `urn:` ids taken as article URLs; "New York Community Bancorp" linked to an unrelated "Community Bancorp"; "The Fed" missed at a sentence start; watchlist rejected `NDA FI`; provider tests leaked `event.upserted` test events into analytics (18 rows removed). Live: E2E all checks passed in 112 s (4 new articles fully processed, alert received, overlay for JPM/MS/C/GS/BAC); 27/27 seeded sources PASS; analytics and manager images build. |
| Tooling | Done | `fc7b69c` | Worker venvs on Python 3.11 (CLT Python 3.9 crashed Playwright helpers). |

### Deviations from the original plan

- **Phase 2:** the hardened crawler does not imitate browsers or use a headless browser to get past 403/bot protection (sites that block us are excluded, not worked around). It still honours robots.txt and paywalls. Fixes that were made: encoding detection for charset-less feeds, feedparser, `<time datetime>` dates, tracking-only URL canonicalisation, feed discovery, per-article robots.txt in the worker.
- **Phase 2:** no `make validate-sources` target; the validator runs as `python -m scraper.tools.validate_sources` (see README).
- **Phase 3:** MENTIONS/AFFECTS are not mapped on Article/Story entities, because Spring Data Neo4j's `save()` deletes unloaded relationships.
- **Phase 4:** base model `ProsusAI/finbert`; max length 64 (not 256), bottom 6 layers frozen, 3 epochs. Needed because MPS training at full size ran at 4.4 s/step; the shorter setup trains in about 40 minutes. Attention dropout is 0 (MPS fused attention does not support it).
- **Phase 4:** added the rule layer (`classifier/rules.py`) after live headlines showed the model maps every Fed release to monetary policy and misses enforcement news.
- **Migration numbers:** V3 is the entity schema, V4 the topics, V5 the company universe, V6 a correction removing SEC note issuers/subsidiaries (e.g. Barclays Bank PLC filing for iPath ETNs) that an earlier V5 build seeded as banks.
- **Phase 5:** no spaCy/GLiNER NER. Linking is a dictionary matcher over curated names and aliases with precision rules (case-sensitive acronyms; common-word brands need case and financial context; ambiguous or all-generic names skipped). With a curated alias list, fuzzy NER mostly adds false links.
- **Phase 5:** Latin American Santander subsidiaries (Brasil, Chile) are not in the universe (outside US/UK/EU scope, and their names clash with the curated Santander).
- **Phase 5:** sentiment is scored per sentence (title, lead sentences, sentences naming a company) because FinBERT on a whole paragraph is dominated by one strongly worded sentence; labels use FinBERT's argmax.
- **Phase 5:** events are derived data recomputed by the provider (`EventService`) on enrichment, story attach and topic changes, rather than written by the enricher, so worker completion order does not matter. The impact formula is documented in PROVIDER_API.md.
- **Phase 6:** plain Postgres (no TimescaleDB) as decided; lease table instead of ShedLock (not yet confirmed for Spring Framework 7); Stooq is no longer usable (JavaScript bot check), so prices need a Twelve Data key; benchmarks configured for US exchanges only (KBE), so UK/EU events wait for a benchmark and a plan covering those exchanges.
- **Phase 7:** no macro-series endpoint (no FRED/ECB ingestion yet) and no story-timeline endpoint (article lists stay on the provider's `/api/articles/story/{id}`); caching is HTTP (ETag + `max-age`) rather than Caffeine/Redis, which is enough at this size.
- **Phase 8:** alerts are derived by the manager from `event.upserted` (the provider's existing event stream) instead of the provider publishing ticker matches on `article.notifications`, so user thresholds stay in the manager and the provider is unchanged. Dedup is per (user, story, company), not per (user, story): a merger story affecting two watched banks alerts for each. Events first seen more than 72 h ago do not alert (backfills, re-enrichment). Endpoints are `PUT/DELETE /api/users/me/watchlist/{symbol}` (plus `type: TICKER` on the generic subscriptions endpoint) rather than `/subscriptions/ticker/{symbol}`; symbols are validated against the cached `GET /api/companies` instead of a new exists endpoint; the topic filter is a comma-separated column rather than `text[]`; analytics gained `GET /api/charts/latest-events` for the watchlist.
- **Phase 10:** tests run against the local Docker infra (`make up`) instead of Testcontainers, and stubs (Mockito / `MockRestServiceServer`-style mocks, `unittest.mock`) instead of WireMock / VCR; feed fixtures are hand-made reproductions of the quirks seen live rather than recorded feeds (no third-party content in the repo). The E2E runs services and workers locally rather than via `make up-full`: the full container set (11 worker replicas loading FinBERT/MiniLM, three JVMs, Neo4j) does not fit a 5 GB Docker VM; the analytics and manager images were built to check their Dockerfiles. There is no reverse proxy in the compose file. The nightly workflow is written but has not run (the repository has no remote yet).
- **Known model gap (Phase 4):** "JPMorgan profit beats estimates" was classified `company_product_news` instead of `earnings_results` in the Phase 5 end-to-end run; an earnings rule or in-domain labels would fix it.

## Guiding principle: reuse, don't rebuild

| Need | Off-the-shelf choice |
|---|---|
| Topic classifier base | `ProsusAI/finbert` or `yiyanghkust/finbert-pretrain` (finance-domain BERT), fine-tuned |
| Topic training data | `zeroshot/twitter-financial-news-topic` (20 finance labels) + Reuters-21578 (`acq`, `earn`, `interest`, `money-fx`, …) |
| Sentiment | `ProsusAI/finbert` as is (pos/neg/neu, trained on Financial PhraseBank). No training needed |
| NER | spaCy `en_core_web_trf` (ORG entities), optionally GLiNER for long-tail fintech names |
| Entity → ticker | SEC `company_tickers.json` + SIC codes, GLEIF LEI API, FSB G-SIB list for the seed universe, `rapidfuzz` for alias matching |
| Prices | Pluggable adapter over plain REST (Spring `RestClient`): **Stooq CSV** or a free tier of **Twelve Data / FMP** (dev) → **Polygon / FMP / Twelve Data** paid (prod). No yfinance: it has no reliable Java client |
| Macro series | FRED API (fed funds, SOFR, 10Y, ECB deposit rate via ECB SDW) |
| Time-series store | **Plain Postgres**: a separate `market` database with its own credentials on the existing Postgres server, owned only by `analytics`. Volumes are small (about 250k daily bars, a few million intraday bars per year). TimescaleDB is a drop-in upgrade later if needed |
| Language split | **Every HTTP service in Java (Spring Boot)**: manager, provider, analytics. **Python only for the ML and scraping workers** behind RabbitMQ (scraper, classifier, clusterer, enricher) |
| Analytics service stack | **Spring Boot 3**, Spring Data JDBC + Flyway, Spring AMQP, `@Scheduled` + ShedLock, Apache Commons Math (regression and t-tests for event studies), springdoc-openapi, Caffeine |
| Single entry point | **Traefik** (or nginx) reverse proxy in compose, routing `/api/auth`, `/api/users` → manager, `/api/charts` → analytics, everything else → provider |
| Event-study maths | Standard market-model abnormal returns (numpy/pandas). No custom framework |
| Chart payload shape | Matches **TradingView Lightweight Charts** / ECharts conventions (unix-second `time`, `markers[]`) so any frontend can plot it directly |

---

## Target architecture (microservices, one datastore per service)

| Service | Status | Owns (only writer) | Interfaces |
|---|---|---|---|
| **manager** (Spring, Postgres `users`) | Existing, extended | Users, JWT issuing, subscriptions (now including tickers), notifications | HTTP; consumes `article.notifications` |
| **provider** (Spring, Neo4j) | Existing, extended | Articles, sources, topics, stories, companies, tickers, mentions | HTTP; publishes `scrape.jobs`, `article.notifications`, **`event.upserted`** |
| **analytics** (Spring, Postgres `market` database) | **New** | Prices, macro series, event read model, event impact | HTTP `/api/charts/*`; consumes `analytics.events` |
| scraper-worker | Existing | – (writes via provider) | `scrape.jobs` → `article.saved` |
| classifier-worker | Existing, new model | – | `article.classify` |
| cluster-worker | Existing, re-tuned | Qdrant centroids | `article.cluster` |
| enricher-worker | **New** | – (writes via provider) | `article.enrich` |

**How analytics gets events without calling the provider on every chart request:**
it keeps a local **read model** (CQRS-style).
- Whenever the provider creates or updates a Story×Company event (on enrichment, or on a story attach), it publishes `event.upserted` to the `news_monitor` exchange.
  - Payload: `{storyId, companyId, tickers[], t0, title, topics[], sentiment, sourceCount, impactScore, version}`.
- Queue `analytics.events` binds to that key, and analytics upserts the payload into its own `events` table. It applies a payload only if its `version` is newer than the stored one.
- The overlay join (price + markers) is then a single local SQL query, with no network hop.
- **Rebuild path:** `GET /api/events?since=` on the provider (paged) lets analytics replay the full event history after its DB is wiped or a schema changes.
- Analytics computes abnormal returns and writes them back with `PATCH /api/events/{storyId}/{companyId}/impact` on the provider, the same way workers write today. Alert thresholds in manager then use the final `impactScore`.

**Auth:** analytics validates manager-issued JWTs with the public key only, exactly like the provider. Chart reads are public; admin endpoints (backfill, rebuild) require `ROLE_ADMIN` or `ROLE_SYSTEM`. Analytics gets its own `ROLE_SYSTEM` token for its PATCH calls to the provider.

**Failure isolation:**
- If analytics is down, ingestion, classification and alerts keep working, and `event.upserted` messages queue up durably until it returns.
- If the provider is down, charts still serve from the read model.

---

## Phase 0: Safety net and wipe

1. Tag or back up the current state. The project folder is **not a git repo**, so `git init` and commit the baseline first so every later phase is reviewable and reversible.
2. Export anything worth keeping: `data/my_articles.json` already exists (general news, not reusable for banking). Dump the Neo4j `NewsSource` list for reference.
3. Wipe (destructive, run manually with confirmation):
   - `make purge-dbs`: Neo4j (all nodes including `__Migration`) and the Qdrant `story_centroids` collection
   - `make postgres-purge`: drops the `users` schema and Flyway history
   - `make rabbitmq-purge-scrape-jobs`, and purge `article.classify`, `article.cluster` and `scrape.jobs.dead`
4. **Squash migrations.** Because the Neo4j purge deletes `__Migration` nodes, the old `V2__seed_news_sources` (BBC, RT, …) and `V8__adding_topics` (HuffPost labels) would re-run. Replace `V1`–`V9` with a clean baseline:
   - `V1__baseline_schema.cypher`: constraints and indexes (Article.url, NewsSource.name, Topic.name, Story.id, Company.id, Ticker.symbol), full-text index
   - `V2__seed_banking_sources.cypher`: generated from the validated source list (Phase 2)
   - `V3__company_ticker_schema.cypher`: Company/Ticker/Story constraints and relationship indexes (Phase 3)
   - `V4__seed_banking_topics.cypher`: the new taxonomy (Phase 4)
   - `V5__seed_company_universe.cypher`: banks, fintechs, regulators and tickers (Phase 5)
   - Postgres: keep `V1`–`V6`, add `V7__ticker_subscriptions.sql` (Phase 8)

**Done when:** a fresh `make dev` comes up with empty stores and only the new seeds.

---

## Phase 1: Remove political orientation

| File | Change |
|---|---|
| `backend/provider/.../models/NewsSource.java` | Remove `politicalView`; add `category` (enum `NEWS`, `REGULATOR`, `CENTRAL_BANK`, `FILING`, `TRADE_PRESS`), `region` (`US`, `UK`, `EU`, `GLOBAL`), `paywalled` (bool), `language` |
| `dto/requests/NewsSourceRequest.java`, `dto/responses/NewsSourceDto.java` | Same field swap, with updated `@Schema` docs |
| `services/NewsService.java` | Drop the mapping of `politicalView` |
| `data/sources.json` | Rebuilt from scratch. Drop `politicalView`, `biasScores`, `notes` and the AllSides/MBFC links. Also fix the file: it is currently **invalid JSON** (top-level objects without an enclosing array) |
| `README.md`, `documentation/PROVIDER_API.md`, `backend/provider/postman/*.json` | Remove political fields and examples. Change example topic `"Politics"` to a banking topic |
| `backend/workers/clusterer/data/*` | Old general-news validation data stays as an archive; it is not used at runtime |

**Done when:** `grep -ri "political\|biasScores" backend data documentation` (excluding venvs and `target/`) returns nothing, and the provider builds and its tests pass.

---

## Phase 2: Banking sources (discover, test, then seed)

### 2.1 Candidate list (to validate, not assumed working)

| Group | Candidates |
|---|---|
| Trade press | Banking Dive, Payments Dive, American Banker (partly paywalled), Finextra, Fintech Futures, The Financial Brand, PYMNTS, Bank Automation News, Finance Magnates, The Banker (paywalled) |
| General finance | CNBC Finance RSS, MarketWatch, Yahoo Finance, Investing.com news, Seeking Alpha market news, FT (headline-only, paywall), CoinDesk (crypto and banking crossover) |
| US regulators / central bank | Federal Reserve press releases RSS, FDIC press releases, OCC news releases, CFPB newsroom, SEC press releases, FinCEN, Treasury |
| UK / EU regulators | Bank of England news RSS, PRA, FCA news, ECB press releases RSS, ECB Banking Supervision, EBA news, BIS press releases |
| Filings | SEC EDGAR Atom feed for 8-K filings filtered to the bank SIC codes (6021, 6022, 6029, 6035, 6036, 6199, 6211) |

Note: Reuters and Bloomberg no longer offer public RSS. Don't spend effort on scraping them.

### 2.2 Source validation harness (new script: `backend/workers/scraper/tools/validate_sources.py`)

This reuses the existing `news_scraper.py` fetch chain (static, then browser UA, then Playwright). For each candidate it checks:

| Check | Pass criterion |
|---|---|
| Feed reachable and parseable | HTTP 200, feedparser `bozo == 0` or a recoverable error |
| Freshness | ≥ 1 item in the last 72 h (regulators: last 14 days) |
| Volume | Items per day recorded (for scheduler tuning) |
| Body extraction | ≥ 70 % of the 10 latest items yield ≥ 400 chars of body via trafilatura |
| Paywall / bot wall | Detect login/subscribe interstitials and 403/429 errors. Mark `paywalled=true` (keep as headline-only) or reject |
| robots.txt | Allowed for our UA |
| Banking relevance | Share of items whose title+lead matches a banking keyword list, or that the Phase 4 classifier puts in a non-`other` bucket (≥ 40 % for general-finance feeds) |

Output: `data/source_validation_report.json` and a Markdown summary. Only sources that pass go into `data/sources.json`, and `V2__seed_banking_sources.cypher` is **generated** from that JSON (no hand-written duplicates). Add `make validate-sources`.

### 2.3 Scheduler tuning

Six hours is too slow for markets. Make the interval per source (`scrapeIntervalMinutes` on `NewsSource`; for example 15 min for trade press and filings, 60 min for regulators). `ScrapeScheduler` then enqueues the sources that are due instead of all of them.

**Done when:** at least 20 passing sources across US, UK and EU and every category, the validation report is committed, and a 24 h soak run shows the failure counter stable.

---

## Phase 3: Data model changes (Neo4j)

```
(:Article {…, sentimentLabel, sentimentScore, lang})
(:Article)-[:HAS_TOPIC {score}]->(:Topic)          // now multi-label, with a score
(:Article)-[:MENTIONS {count, salience, inTitle}]->(:Company)
(:Company {id, name, lei, country, kind: BANK|FINTECH|REGULATOR|CENTRAL_BANK, aliases[]})
(:Company)-[:LISTED_AS {primary}]->(:Ticker {symbol, exchange, currency})
(:Story)-[:AFFECTS {firstSeen, impactScore}]->(:Company)
(:NewsSource {…, category, region, paywalled})
```

- `Topic` is kept as the label (subscriptions and controllers keep working). Only the cardinality (multi) and the seed values change.
- **Event** = (Story × Company). Its timestamp is the earliest `publishedAt` in the story that mentions that company. This keeps "events" derived rather than a separate thing to maintain.
- `impactScore` (0–1) = weighted mix of: source count in the story, topic weight (enforcement, M&A and earnings rank high), |sentiment|, regulator-source flag, and (once available) |abnormal return|.

---

## Phase 4: Banking topic classifier (public data only)

### 4.1 Taxonomy (multi-label)

Mapped from the `twitter-financial-news-topic` labels plus Reuters-21578 categories:

| New banking topic | Source labels |
|---|---|
| `earnings_results` | TFNT *Earnings*, Reuters `earn` |
| `m_and_a_investments` | TFNT *M&A \| Investments*, Reuters `acq` |
| `monetary_policy_rates` | TFNT *Fed \| Central Banks*, Reuters `interest` |
| `regulation_legal_enforcement` | TFNT *Legal \| Regulation* |
| `debt_capital_markets` | TFNT *Treasuries \| Corporate Debt*, *IPO* |
| `dividends_buybacks` | TFNT *Dividend* |
| `analyst_ratings` | TFNT *Analyst Update* |
| `leadership_personnel` | TFNT *Personnel Change* |
| `fx_currencies` | TFNT *Currencies*, Reuters `money-fx` |
| `macro_economy` | TFNT *Macro* |
| `markets_stock_moves` | TFNT *Stock Movement*, *Stock Commentary*, *Markets* |
| `company_product_news` (fintech, payments, product launches) | TFNT *Company \| Product News*, *Financials* |
| `other` | Energy, Gold/Metals, General/Opinion, Politics |

**Known gap (be explicit):** no public dataset labels credit risk, liquidity/capital (Basel), bank runs, crypto or payments separately. With "public data only" these fold into the nearest bucket. If they matter later, the cheapest fix is a few hundred LLM-labelled examples, which is out of scope for now.

### 4.2 Training (`backend/workers/classifier/training/`)

1. `prepare_data.py`: download the datasets via `datasets`, apply the mapping above, deduplicate, and split stratified 80/10/10.
2. `train.py`: fine-tune `yiyanghkust/finbert-pretrain` (or `ProsusAI/finbert` body with a new head), `problem_type="multi_label_classification"`, max_len 256 on title + first 2 sentences. Training data is tweet/headline length, so **classify on title + lead at inference too** to limit domain shift.
3. Use class weights or focal loss for the long tail. Run 3–5 epochs and pick per-label thresholds on validation.
4. **Evaluation on our own data:** hand-label about 300 scraped articles (eval only, not training) and report macro-F1 and per-label F1. Ship gate: macro-F1 ≥ 0.70 on the public test set and ≥ 0.60 on the in-domain set.
5. Save the **complete** artifact: `config.json`, tokenizer files, `label2id.json` and `thresholds.json`. The current `cls_model_output/` only has `model.safetensors`, so `classifier.py` can't load it as is. Track the model with Git LFS or publish it to a private HF Hub repo instead of committing the binary.
6. Upgrade the pinned stack (`transformers<4.39`, `torch<2.2`, Python 3.9 venv) to current versions as part of this phase.

### 4.3 Worker changes

`classifier.py` returns `[(topic, score)]` above per-label thresholds. `classifier_worker.py` calls a new `PATCH /api/articles/topics` (plural, with scores). Provider: `Article.topic` becomes `List<TopicLink>` with a score relationship property. Notifications fire per topic.

---

## Phase 5: Entity, ticker and sentiment enrichment (new `enricher` worker)

- New queue `article.enrich` bound to `article.saved` on the existing `news_monitor` exchange (a third fan-out, same pattern as classify and cluster). Add it to `rabbitmq/setup.sh`.
- Pipeline per article:
  1. spaCy NER finds ORG spans.
  2. Alias matching (exact, then `rapidfuzz` ≥ 90) against the Company universe, with a disambiguation rule ("Citizens" alone does not equal Citizens Financial unless banking context is present).
  3. Salience: title mention > lead > body count.
  4. FinBERT sentiment on title + lead, plus **entity-level sentiment** on sentences mentioning each company (store the average on `MENTIONS`).
  5. `PATCH /api/articles/{id}/enrichment` on the provider.
- **Company universe seed** (`tools/build_universe.py` produces `V5__seed_company_universe.cypher`):
  - US: SEC `company_tickers.json` filtered by bank SIC codes (about 400 listed banks)
  - UK/EU: FSB G-SIB list + EBA-supervised large banks + major listed fintechs (GLEIF for LEIs, manual ticker mapping for about 60 names)
  - Regulators and central banks as `kind=REGULATOR|CENTRAL_BANK` (no ticker; used for event filters)

---

## Phase 6: Analytics service, part 1: skeleton, market data, event read model

- New Spring Boot module `backend/analytics/` (Maven, same layout as `backend/provider`: `controllers`, `services`, `repositories`, `config`, `security`, `dto`). A single deployable with three internal parts:
  - **API**: `/api/charts/*` controllers (Phase 7), Actuator `/health`, springdoc OpenAPI
  - **Ingest**: `@Scheduled` jobs for price backfill and updates, macro series and event impact, guarded by **ShedLock** (lock table in `market`) so scaling to several replicas never double-runs a job
  - **Consumer**: a Spring AMQP `@RabbitListener` on `analytics.events`
  - Copied from the provider: `JwtTokenValidator`, `JwtAuthenticationFilter`, `SecurityConfig`, `GlobalExceptionHandler`, `OpenApiConfig`. A shared `common-security` Maven module is optional later; copying is fine for three services
  - Gets its `ROLE_SYSTEM` token from manager at boot, the same way manager does for its own outbound calls
- Own datastore: database `market` on the existing `postgres` container, created by an init script with its own user (`analytics`), which has no grants on manager's `news_monitor` DB. Flyway migrations in `backend/analytics/src/main/resources/db/migration`. If chart load ever competes with manager, move `market` to its own server by changing only the JDBC URL.
- RabbitMQ (`rabbitmq/setup.sh`): queue `analytics.events` bound to `news_monitor` with routing key `event.upserted`, a dead-letter queue, and a TTL longer than any expected analytics outage.
- Provider: publish `event.upserted`, and add `GET /api/events?since=` (rebuild) and `PATCH /api/events/{storyId}/{companyId}/impact`.
- Makefile: `analytics` (run locally with `./mvnw spring-boot:run`), `logs-analytics`, `market-purge` (drops and recreates the `market` DB), `analytics-rebuild-events`. Add the `analytics` compose service and its env variables (`MARKET_DB_*`, `MARKET_DATA_PROVIDER`, API keys, `FRED_API_KEY`) to `env.example`.
- Tables (plain Postgres, B-tree on `(symbol, ts)`, `ts` as `timestamptz`; optionally range-partitioned by year for intraday):
  - `events(story_id, company_id, symbol, t0, title, topics[], sentiment, source_count, impact_score, version)`: the read model
  - `prices_daily(symbol, ts, open, high, low, close, adj_close, volume)`
  - `prices_intraday(symbol, ts, …)` at 15-minute bars, optional and limited to the watchlist universe
  - `macro_series(series_id, ts, value)` from FRED and ECB SDW
  - `event_impact(story_id, company_id, symbol, t0, ar_d0, car_m1_p1, car_m5_p5, benchmark, computed_at)`
- Ingest: a scheduled backfill (2 years daily) and incremental updates after market close per exchange, with batch inserts via `JdbcTemplate.batchUpdate` and `ON CONFLICT DO UPDATE`. The ticker universe comes from the provider's `GET /api/companies`, refreshed daily. Interface `MarketDataProvider` with `StooqProvider` / `TwelveDataProvider` (dev) and `PolygonProvider` / `FmpProvider` (prod), chosen with `@ConditionalOnProperty(market-data.provider=…)`. Rate limiting with Resilience4j `RateLimiter` and `Retry`.
- Event study in Java: daily log returns, market-model OLS (`SimpleRegression`) for α and β, AR and CAR over the windows, and significance from Commons Math `TDistribution`. About 150 lines, with unit tests against a spreadsheet-computed fixture.
- Benchmarks for abnormal returns: `KBE` / `KRE` (US banks), `XLF`, `EUFN` / `EXV1.DE` (EU banks), `^FTSE` for UK. Market-model β estimated over [-250, -30] trading days.
- The event-impact job runs when an `event.upserted` message first arrives for a Story×Company and again at t+5 trading days. The result is PATCHed back to the provider.

---

## Phase 7: Analytics service, part 2: chart and graph APIs (read-only, public)

All endpoints are served by the `analytics` service under `/api/charts/*` from its own `market` database only, with Java `record` DTOs and `@Schema` docs (the same style as the provider). Caching: Spring Cache with Caffeine (60 s TTL for intraday, 1 h for daily). Switch to Redis-backed Spring Cache only if several replicas need a shared cache. The reverse proxy routes `/api/charts` here.

| Endpoint | Purpose / payload |
|---|---|
| `GET /api/charts/companies/{ticker}/price?from&to&interval=1d\|15m` | OHLCV `[{time, open, high, low, close, volume}]` |
| `GET /api/charts/companies/{ticker}/events?from&to&topics&minImpact&sources` | Event markers `[{time, storyId, title, topics[], sentiment, impactScore, sourceCount, position, shape, color}]`, ready to pass to the chart library's `setMarkers()` |
| `GET /api/charts/companies/{ticker}/overlay?from&to&interval` | Price series and markers snapped to bar times in one call (the main investor chart) |
| `GET /api/charts/companies/{ticker}/sentiment?bucket=1d&from&to` | `[{time, avgSentiment, positive, negative, neutral, articleCount}]` |
| `GET /api/charts/companies/{ticker}/news-volume?bucket=1h\|1d` | Article counts per bucket (spike detection) |
| `GET /api/charts/events/{storyId}/impact?ticker` | Event study: price path from t-5 to t+5, AR/CAR vs benchmark, β, p-value |
| `GET /api/charts/topics/volume?bucket=1d&topics&region` | Stacked news volume per topic over time |
| `GET /api/charts/sector/heatmap?window=1d\|7d\|30d&region` | Matrix per bank: return, sentiment, news volume, top event |
| `GET /api/charts/macro/{seriesId}?from&to&withEvents=true` | Macro line (e.g. FEDFUNDS, ECB DFR) with central-bank decision markers |
| `GET /api/charts/stories/{storyId}/timeline` | Articles over time + linked tickers' prices (story drill-down) |
| `GET /api/companies/{ticker}/graph?depth=1&window=30d` (**provider**, not analytics) | Node-link JSON `{nodes, edges}` of company ↔ stories ↔ co-mentioned companies. It stays in the provider because it is a native Neo4j traversal; copying the graph into analytics would duplicate ownership |

Conventions: `time` in unix seconds UTC, ISO-8601 for `from` and `to`, server-side downsampling above 2,000 points, `ETag` headers. Add a Postman folder and `documentation/CHARTS_API.md`.

Story timeline and event markers come from the local `events` read model. Article lists for a drill-down link to the provider's existing `/api/articles/by-story` instead of being copied.

---

## Phase 8: Ticker watchlists and alerts

- Manager `V7__ticker_subscriptions.sql`: extend `subscriptions.type` with `TICKER` and add `min_impact` (default 0.5) and `topics_filter` (nullable text[]).
- Endpoints: `POST/DELETE /api/users/me/subscriptions/ticker/{symbol}` (validated via provider `GET /api/companies/{ticker}/exists`), plus `GET /api/users/me/watchlist` that returns tickers with the last price, 1-day change and latest event. That data comes from one batch call to analytics, `GET /api/charts/quotes?symbols=…`, with a short cache. If analytics is down, the watchlist degrades to tickers without prices instead of failing.
- Provider publishes on `article.notifications` when enrichment links an article to a subscribed company **and** the story's `impactScore ≥ min_impact`. Payload: `{type:"TICKER", name, articleUrl, storyId, topics, sentiment, impactScore}`.
- Deduplicate one alert per (user, story), so a 30-article story doesn't send 30 notifications.

---

## Phase 9: Clusterer re-validation

`all-MiniLM-L6-v2` and the 0.533 threshold were tuned on general news (SemEval-2022). Financial headlines are formulaic ("X beats Q3 estimates"), so false merges across different banks are likely.
- Add an entity guard: only attach to a story if the primary company overlaps (or both are company-less, e.g. macro).
- Re-check the threshold on about 200 financial article pairs from the new feed, using the existing `validate.py` / `build_validation_pairs.py`. Optionally compare `BAAI/bge-small-en-v1.5`.
- Shorten the story window from 5 to 3 days for company-specific stories.

---

## Phase 10: Testing and verification

| Layer | Tests |
|---|---|
| Provider | `@DataNeo4jTest` / Testcontainers for the enrichment, event and graph queries; test that `event.upserted` is published with an incrementing `version` |
| Analytics | JUnit 5 + Spring Boot Test + Testcontainers (Postgres, RabbitMQ), WireMock for the market-data APIs: consumer idempotency (duplicate and out-of-order versions), rebuild from `/api/events`, event-study maths against a hand-computed fixture, and contract tests on chart payload shapes |
| Manager | Ticker subscription CRUD and notification fan-out with dedup |
| Workers | pytest with recorded feed fixtures (VCR) for the scraper; golden tests for entity linking (50 tricky headlines); classifier smoke test (loads, labels and thresholds consistent) |
| End-to-end | `make up-full` (now including analytics, the `market` DB and the proxy), trigger a scrape, then assert within N minutes: articles have topics, sentiment, mentions and stories, `/overlay` returns markers for at least 5 seeded banks, and a test user receives a ticker alert |
| Sources | `make validate-sources` in CI nightly; alert if more than 20 % of sources fail |

---

## Suggested order and rough effort

| # | Phase | Depends on | Effort |
|---|---|---|---|
| 0 | Git baseline, wipe, migration squash | none | 0.5 d |
| 1 | Remove political data | 0 | 0.5 d |
| 2 | Source discovery and validation harness | 1 | 2 d |
| 3 | Neo4j model changes | 1 | 1 d |
| 4 | Classifier retrain and multi-label | 3 | 3 d |
| 5 | Enricher (NER, tickers, FinBERT) | 3 | 3 d |
| 6 | Analytics service skeleton, market data, event read model | 3 (event contract) | 3 d |
| 7 | Chart APIs in analytics | 5, 6 | 3 d |
| 8 | Watchlists and alerts | 5, 7 | 1.5 d |
| 9 | Clusterer re-validation | 5 | 1 d |
| 10 | Tests and E2E | all | 2 d |

Phases 2, 4 and 6 are independent and can run in parallel once 0, 1 and 3 have landed.

## Risks and open items

- **Market data cost:** free tiers (Stooq, Twelve Data, FMP) are rate-limited and partly delayed, which is fine for development. Budget for a paid feed (Polygon or FMP, about $30–80/mo) before production. Check the EU/UK exchange coverage of the chosen plan.
- **Shared Postgres server:** manager and analytics are isolated by database and credentials but share CPU and IO. Watch for this and split the server if a backfill slows down logins.
- **Paywalled premium sources** (FT, American Banker, The Banker) are headline-only. Respect ToS and robots.txt.
- **Public-only training data** is tweet-length and generic finance. Expect weaker results on long articles and no dedicated credit/liquidity/crypto labels (see 4.1).
- **Qdrant is Qdrant Cloud** (not in compose). The wipe deletes the remote collection, so check that `.env` points at the intended cluster first.
- `backend/workers/localhost+1-key.pem` and `backend/keys/jwt-private.pem` sit in the tree. Keep them out of the new git repo (`.gitignore`) and rotate the JWT keypair during the Postgres wipe.
