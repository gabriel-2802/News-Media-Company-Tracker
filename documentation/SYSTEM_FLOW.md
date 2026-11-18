# System Flow

How data moves through the banking/fintech news backend, from a feed item to an investor's chart and alert. The diagrams are Mermaid (rendered by GitHub and most Markdown viewers). Component details live in the [README](../README.md); endpoints in [PROVIDER_API.md](PROVIDER_API.md), [MANAGER_API.md](MANAGER_API.md) and [CHARTS_API.md](CHARTS_API.md).

1. [System overview](#1-system-overview)
2. [Article pipeline: feed to story](#2-article-pipeline-feed-to-story)
3. [Events: from articles to Story→Company events](#3-events-from-articles-to-storycompany-events)
4. [Event consumers: alerts and market analytics](#4-event-consumers-alerts-and-market-analytics)
5. [Investor read paths](#5-investor-read-paths)
6. [Authentication](#6-authentication)
7. [Topic notifications](#7-topic-notifications)

---

## 1. System overview

Three Spring Boot services and three Python workers. Every service owns its datastore; nothing reads another service's database. Workers write only through the provider's API. There is no clustering step: every saved article is its own story (see [§2](#2-article-pipeline-feed-to-story)).

```mermaid
flowchart TB
    client([Investor app / API client])

    manager["<b>Manager</b> :9000<br/>auth, users, watchlists,<br/>notifications, alerts"]
    provider["<b>Provider</b> :8080<br/>articles, sources, topics,<br/>stories, companies, events"]
    analytics["<b>Analytics</b> :8090<br/>prices, event studies,<br/>chart APIs"]

    rabbit{{"RabbitMQ<br/>news_monitor exchange"}}

    subgraph workers[Python workers]
        scraper[Scraper]
        classifier["Classifier<br/>FinBERT + rules"]
        enricher["Enricher<br/>companies + sentiment"]
    end

    pg[("Postgres<br/>news_monitor.users")]
    neo[(Neo4j)]
    market[("Postgres<br/>market")]
    feeds[/27 RSS / Atom feeds/]
    twelve[/Twelve Data prices/]

    client --> manager & provider & analytics

    manager --- pg
    provider --- neo
    analytics --- market

    provider -->|"① scrape.jobs"| rabbit
    rabbit -->|"② jobs and article.saved fan-out"| workers
    scraper --> feeds
    workers ==>|"③ reads and writes via REST"| provider
    provider -->|"④ event.upserted"| rabbit
    rabbit -->|"⑤ analytics.events"| analytics
    rabbit -->|"⑤ manager.events"| manager
    analytics -->|"⑥ impact write-back"| provider
    analytics --> twelve
```

① the provider queues a scrape job per due source · ② the scraper saves articles and publishes `article.saved`, which fans out to the classifier and enricher (the provider gives each saved article its own story) · ③ every worker reads and writes only through the provider · ④ the provider publishes every changed Story→Company event · ⑤ analytics and the manager consume it (charts, ticker alerts) · ⑥ analytics writes event-study results back. Service-to-service reads (manager → provider/analytics for the watchlist, analytics → provider for its event feed) are in [§5](#5-investor-read-paths) and [§4](#4-event-consumers-alerts-and-market-analytics).

| Store | Owner | Holds |
| --- | --- | --- |
| Neo4j | Provider | Sources, articles, topics, stories, companies, tickers, events (`AFFECTS`) — the system of record |
| Postgres `news_monitor` (schema `users`) | Manager | Users, roles, subscriptions and watchlists, notifications, alert dedup |
| Postgres `market` | Analytics | Event read model, daily prices, event studies, company names, job leases |

---

## 2. Article pipeline: feed to story

A scrape job per source; saving an article also creates its own one-article story, then one `article.saved` message fans out to two independent workers. They finish in any order; the provider merges their results.

```mermaid
sequenceDiagram
    autonumber
    participant S as Provider scheduler
    participant Q as RabbitMQ
    participant SC as Scraper
    participant F as Source site
    participant P as Provider (Neo4j)
    participant CL as Classifier
    participant EN as Enricher

    S->>Q: scrape.jobs {name, retry_count} (every 5 min for sources whose interval elapsed, or trigger-scrape)
    Q->>SC: scrape job
    SC->>P: GET /api/news-sources/{name}
    SC->>F: fetch RSS / Atom feed
    loop each new entry (robots.txt allowed, not already stored)
        SC->>F: fetch page (static → browser UA → Playwright)
        SC->>SC: extract body (trafilatura, banner stripping)
        SC->>P: POST /api/articles (also creates the article's own story)
        SC->>Q: article.saved {url, source_name}
    end
    Note over SC,Q: feed failure → failure count + re-queue (max 3 attempts)

    par fan-out on the news_monitor topic exchange
        Q->>CL: article.classify
        CL->>P: GET /api/articles/by-url
        CL->>CL: FinBERT multi-label + rule layer
        CL->>P: PATCH /api/articles/topics (scored topics)
    and
        Q->>EN: article.enrich
        EN->>P: GET /api/articles/by-url
        EN->>EN: link companies, FinBERT sentiment
        EN->>P: PATCH /api/articles/enrichment (sentiment + mentions)
    end
```

Clustering was removed: on these specialised sources 92% of clustered stories held one article (see the README's [Stories section](../README.md#stories-one-per-article)). Worker failure handling: a provider outage is retried 5 times with backoff (60–240 s), after which the message is requeued and the worker stops; any other error requeues the message and the worker carries on.

---

## 3. Events: from articles to Story→Company events

An **event** is `(:Story)-[:AFFECTS {firstSeen, impactScore, articleCount, sentiment}]->(:Company)`. The provider recomputes a story's events whenever the enricher or the classifier writes, so the result does not depend on which worker finishes first.

```mermaid
flowchart TD
    enrich["PATCH /api/articles/enrichment<br/>(enricher)"] --> refresh
    topics["PATCH /api/articles/topics<br/>(classifier)"] --> refresh

    refresh["EventService: refresh the story's events<br/>one transaction, story lock first,<br/>retried on deadlock (Neo4jWrites)"]
    refresh --> calc["Per company mentioned in the story<br/>(not regulators or central banks):<br/>impactScore = 0.25 breadth + 0.15 coverage<br/>+ 0.20 salience + 0.25 topic weight<br/>+ 0.15 abs(sentiment) + 0.10 official source"]
    calc --> write[("Neo4j AFFECTS<br/>created / updated / removed")]
    write --> commit{committed?}
    commit -->|yes| pub["Publish event.upserted per changed event<br/>EventMessage + deleted flag + version (epoch ms)"]
    commit -->|rolled back| none[nothing published]
    pub --> aq[(analytics.events)]
    pub --> mq[(manager.events)]
```

`version` makes consumers order-independent: an older or duplicate message never overwrites a newer one, and a removal leaves a tombstone.

---

## 4. Event consumers: alerts and market analytics

The same `event.upserted` message feeds two independent consumers.

```mermaid
sequenceDiagram
    autonumber
    participant P as Provider
    participant Q as RabbitMQ
    participant M as Manager
    participant PG as Postgres (users)
    participant A as Analytics
    participant MK as Postgres (market)
    participant TD as Twelve Data

    P->>Q: event.upserted (after commit)

    par manager.events → ticker alerts
        Q->>M: EventMessage
        M->>M: skip if deleted, unlisted, or first seen > 72 h ago
        M->>PG: watchers of the symbol (TICKER subscriptions)
        loop each watcher
            alt impact ≥ minImpact and topic filter matches
                M->>PG: INSERT ticker_alerts ON CONFLICT DO NOTHING
                opt row inserted (first time for user + story + company)
                    M->>PG: TICKER_ALERT notification "SYM: title (impact, sentiment, topics)"
                end
            end
        end
    and analytics.events → read model
        Q->>A: EventMessage
        A->>MK: upsert events if version is newer (tombstone on delete)
    end

    Note over A,TD: scheduled, one replica at a time (job_locks)
    A->>TD: daily bars after the US close (MARKET_INGEST_CRON)
    A->>MK: prices_daily
    A->>A: event study every 30 min: market model vs KBE, AR day 0, CAR[-1,+1], CAR[-5,+5]
    A->>P: PATCH /api/events/{storyId}/{companyId}/impact (does not re-publish)
    A->>MK: event_impact (only after the write-back succeeded, else retried next run)
```

Recovery: `POST /analytics/api/admin/events/rebuild` replays the provider's `GET /api/events` into the read model (without `since` it also removes events that disappeared upstream); messages for a stopped manager or analytics wait in their durable queues.

---

## 5. Investor read paths

```mermaid
flowchart LR
    client([Investor app])

    subgraph M[Manager — JWT required]
        wl["GET /api/users/me/watchlist"]
        wlput["PUT /api/users/me/watchlist/{symbol}<br/>minImpact, topics (max 50)"]
        notif["GET /api/users/me/notifications<br/>ARTICLE + TICKER_ALERT"]
    end

    subgraph A[Analytics — public, cached]
        overlay["/charts/companies/{symbol}/overlay<br/>price bars + event markers"]
        others["price, events, sentiment, news-volume,<br/>impact path, topic volume, heatmap"]
        quotes["/charts/quotes<br/>/charts/latest-events"]
    end

    subgraph P[Provider — public reads]
        cgraph["/api/companies/{symbol}/graph<br/>company ↔ story network"]
        reads["articles, stories, topics,<br/>companies, events"]
    end

    client --> wl & wlput & notif
    client --> overlay & others
    client --> cgraph & reads

    wlput -->|validate symbol, cached 10 min| reads
    wl -->|2 s timeout, nulls if down| quotes
```

---

## 6. Authentication

The manager is the only token issuer (RS256, private key). Provider and analytics hold only the public key. Services log in with a shared `systemCode` and get `ROLE_SYSTEM`.

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant W as Worker / Analytics
    participant M as Manager (private + public key)
    participant P as Provider (public key)

    U->>M: POST /api/auth/login {email, password}
    M-->>U: JWT (ROLE_USER / ROLE_ADMIN)
    W->>M: POST /api/auth/login {systemCode}
    M-->>W: JWT (ROLE_SYSTEM, subject "system")
    W->>P: write call + Bearer token
    P->>P: verify signature with public key, read roles
    alt token expired (401)
        W->>M: log in again
        W->>P: retry once
    end
    Note over M: the manager signs its own ROLE_SYSTEM token at startup for calls to the provider
```

Reads on the provider and analytics are public; writes need `ROLE_ADMIN` or `ROLE_SYSTEM`. Everything under the manager's `/api/users/**` needs a token.

---

## 7. Topic notifications

The older, article-level notifications (subscriptions to a topic) use a separate direct queue, not the topic exchange. Story subscriptions were removed with clustering, since a story no longer gains articles.

```mermaid
sequenceDiagram
    autonumber
    participant U as User
    participant M as Manager
    participant P as Provider
    participant Q as article.notifications
    participant W as Classifier

    U->>M: POST /api/users/me/subscriptions {type: TOPIC, targetId}
    M->>P: POST /api/subscriptions/topic/{name} (count +1, unknown target → 400, provider down → 502)
    W->>P: PATCH /api/articles/topics
    opt the topic has a subscription
        P->>Q: {name, articleUrl, type}
        Q->>M: ArticleNotificationListener
        M->>M: ARTICLE notification for every subscribed user
    end
```
