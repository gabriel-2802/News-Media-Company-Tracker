# Provider API

REST API for registering news sources and ingesting/querying scraped articles, stories, topics, and subscriptions. Backed by Neo4j.

- **Base URL:** `http://localhost:8080/news-provider` (default; port via `server.port`, prefix via `server.servlet.context-path`)
- **Content type:** `application/json` for all request/response bodies
- **Interactive docs:** Swagger UI at `/news-provider/swagger-ui.html`, raw OpenAPI spec at `/news-provider/v3/api-docs`

## Table of contents

- [Authentication](#authentication)
- [News Sources](#news-sources)
  - [`GET /api/news-sources`](#get-apinews-sources)
  - [`GET /api/news-sources/{sourceName}`](#get-apinews-sourcessourcename)
  - [`POST /api/news-sources`](#post-apinews-sources)
  - [`PUT /api/news-sources/{sourceName}`](#put-apinews-sourcessourcename)
  - [`PATCH /api/news-sources/{sourceName}/failure`](#patch-apinews-sourcessourcenamefailure)
  - [`PATCH /api/news-sources/{sourceName}/reset`](#patch-apinews-sourcessourcenamereset)
- [Articles](#articles)
  - [`GET /api/articles`](#get-apiarticles)
  - [`GET /api/articles/source/{sourceName}`](#get-apiarticlessourcesourcename)
  - [`GET /api/articles/story/{storyId}`](#get-apiarticlesstorystoryid)
  - [`GET /api/articles/topic/{topicName}`](#get-apiarticlestopictopicname)
  - [`POST /api/articles`](#post-apiarticles)
  - [`GET /api/articles/exists`](#get-apiarticlesexists)
  - [`GET /api/articles/by-url`](#get-apiarticlesby-url)
  - [`PATCH /api/articles/topic`](#patch-apiarticlestopic)
  - [`PATCH /api/articles/topics`](#patch-apiarticlestopics)
  - [`PATCH /api/articles/enrichment`](#patch-apiarticlesenrichment)
  - [`POST /api/articles/trigger-scrape`](#post-apiarticlestrigger-scrape)
  - [`GET /api/articles/search`](#get-apiarticlessearch)
- [Stories](#stories)
  - [`GET /api/stories`](#get-apistories)
  - [`GET /api/stories/recent`](#get-apistoriesrecent)
  - [`POST /api/stories`](#post-apistories)
  - [`GET /api/stories/by-article`](#get-apistoriesby-article)
  - [`PATCH /api/stories/{storyId}/attach`](#patch-apistoriesstoryidattach)
  - [`GET /api/stories/exists`](#get-apistoriesexists)
  - [`GET /api/stories/search`](#get-apistoriessearch)
- [Topics](#topics)
  - [`GET /api/topics`](#get-apitopics)
  - [`GET /api/topics/exists`](#get-apitopicsexists)
- [Companies](#companies)
  - [`GET /api/companies`](#get-apicompanies)
- [Events](#events)
  - [`GET /api/events`](#get-apievents)
  - [`PATCH /api/events/{storyId}/{companyId}/impact`](#patch-apieventsstoryidcompanyidimpact)
- [Subscriptions](#subscriptions)
  - [`POST /api/subscriptions/story/{storyId}`](#post-apisubscriptionsstorystoryid)
  - [`DELETE /api/subscriptions/story/{storyId}`](#delete-apisubscriptionsstorystoryid)
  - [`POST /api/subscriptions/topic/{topicName}`](#post-apisubscriptionstopictopicname)
  - [`DELETE /api/subscriptions/topic/{topicName}`](#delete-apisubscriptionstopictopicname)
- [RabbitMQ side effects](#rabbitmq-side-effects)
- [Objects](#objects)
  - [`NewsSourceDto`](#newssourcedto)
  - [`NewsSourceRequest`](#newssourcerequest)
  - [`ArticleDto`](#articledto)
  - [`ArticleRequest`](#articlerequest)
  - [`TopicSetRequest`](#topicsetrequest)
  - [`TopicsSetRequest`](#topicssetrequest)
  - [`EnrichmentRequest`](#enrichmentrequest)
  - [`CompanyDto`](#companydto)
  - [`StoryDto`](#storydto)
  - [`AttachArticleRequest`](#attacharticlerequest)
  - [`TopicDto`](#topicdto)
  - [`SubscriptionDto`](#subscriptiondto)
  - [`ErrorResponse`](#errorresponse)
- [Error handling](#error-handling)

---

## Authentication

All `GET` endpoints are public — no token required. Every mutating endpoint (`POST`/`PUT`/`PATCH`/`DELETE`) requires a `Bearer` JWT in the `Authorization` header, scoped to one or more roles:

```
Authorization: Bearer <token>
```

The Provider does not issue tokens itself — it only **validates** JWTs signed with the same `jwt.secret` as the [manager service](../manager/MANAGER_API.md), which is the sole issuer. Callers authenticate against the manager and reuse that token here:

- **Human callers (ROLE_ADMIN):** `POST /api/auth/login` on the manager with an admin account's email/password.
- **Service callers (ROLE_SYSTEM):** `POST /api/auth/login` on the manager with `{"systemCode": "<shared secret>"}` and no password. The manager itself generates one such system token once at startup (for its own outbound calls into `/api/subscriptions/**`), and each Python worker (`workers/provider_client.py`) does the same at construction, transparently re-logging-in if the Provider ever responds `401` (expired token).

### Required roles by endpoint

| Endpoint                                         | Roles                   |
| ------------------------------------------------ | ----------------------- |
| `POST /api/news-sources`                       | ROLE_ADMIN              |
| `PUT /api/news-sources/{sourceName}`           | ROLE_ADMIN              |
| `PATCH /api/news-sources/{sourceName}/failure` | ROLE_ADMIN, ROLE_SYSTEM |
| `PATCH /api/news-sources/{sourceName}/reset`   | ROLE_ADMIN, ROLE_SYSTEM |
| `POST /api/articles`                           | ROLE_ADMIN, ROLE_SYSTEM |
| `PATCH /api/articles/topic`                    | ROLE_ADMIN, ROLE_SYSTEM |
| `PATCH /api/articles/topics`                   | ROLE_ADMIN, ROLE_SYSTEM |
| `PATCH /api/articles/enrichment`               | ROLE_ADMIN, ROLE_SYSTEM |
| `POST /api/articles/trigger-scrape`            | ROLE_ADMIN              |
| `POST /api/stories`                            | ROLE_SYSTEM             |
| `PATCH /api/stories/{storyId}/attach`          | ROLE_SYSTEM             |
| `POST /api/subscriptions/story/{storyId}`      | ROLE_ADMIN, ROLE_SYSTEM |
| `DELETE /api/subscriptions/story/{storyId}`    | ROLE_ADMIN, ROLE_SYSTEM |
| `POST /api/subscriptions/topic/{topicName}`    | ROLE_ADMIN, ROLE_SYSTEM |
| `DELETE /api/subscriptions/topic/{topicName}`  | ROLE_ADMIN, ROLE_SYSTEM |
| Every`GET` endpoint (all controllers)          | none — public          |

### Error responses

Auth failures short-circuit before reaching a controller, but still return the same [`ErrorResponse`](#errorresponse) shape as the rest of the API:

| Status | Cause                                                  | `message`                                              |
| ------ | ------------------------------------------------------ | -------------------------------------------------------- |
| 401    | Missing, malformed, or expired/invalid-signature token | `"Missing or invalid authentication token."`           |
| 403    | Valid token, but missing the required role             | `"You do not have permission to perform this action."` |

### Trying it in Swagger UI

Swagger UI (`/news-provider/swagger-ui.html`) exposes an **Authorize** button — paste a raw token (no `Bearer ` prefix) obtained from the manager's `/api/auth/login` to exercise protected endpoints via "Try it out".

---

## News Sources

Base path: `/api/news-sources`

### `GET /api/news-sources`

List all registered news sources, paginated, including each source's article count.

**Query parameters**

| Name  | Type | Default | Description                |
| ----- | ---- | ------- | -------------------------- |
| page  | int  | `0`   | Zero-based page index      |
| count | int  | `20`  | Number of sources per page |

**Responses**

| Status | Body                                 | Description                    |
| ------ | ------------------------------------ | ------------------------------ |
| 200    | [`NewsSourceDto[]`](#newssourcedto) | Sources retrieved successfully |

---

### `GET /api/news-sources/{sourceName}`

Get a single registered news source by name, including its article count.

**Path parameters**

| Name       | Type   | Description                                   |
| ---------- | ------ | --------------------------------------------- |
| sourceName | string | Name of the news source, e.g.`banking-dive` |

**Responses**

| Status | Body                               | Description                        |
| ------ | ---------------------------------- | ---------------------------------- |
| 200    | [`NewsSourceDto`](#newssourcedto) | News source retrieved successfully |
| 400    | [`ErrorResponse`](#errorresponse) | News source does not exist         |

---

### `POST /api/news-sources`

**Requires:** `ROLE_ADMIN`

Register a new news source to be scraped. The service validates the source before saving:

1. Fails if a source with the same `baseUrl`, `name`, or `rssUrl` already exists.
2. Fails if `rssUrl`'s domain doesn't match `baseUrl`'s domain.
3. Fails if either `baseUrl` or `rssUrl` is unreachable (server performs a live HTTP reachability check).

**Request body:** [`NewsSourceRequest`](#newssourcerequest)

**Responses**

| Status | Body                               | Description                                                                                                                    |
| ------ | ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| 201    | [`NewsSourceDto`](#newssourcedto) | News source created successfully                                                                                               |
| 400    | [`ErrorResponse`](#errorresponse) | Validation failed, a source with the same name/baseUrl/rssUrl already exists, domains don't match, or the URLs are unreachable |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication)                                                                   |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`                                                                                                      |

---

### `PUT /api/news-sources/{sourceName}`

**Requires:** `ROLE_ADMIN`

Replaces a news source's data with the desired final state. Fails if the new name/baseUrl/rssUrl already belongs to a different source, or if either URL is unreachable.

**Path parameters**

| Name       | Type   | Description                                   |
| ---------- | ------ | --------------------------------------------- |
| sourceName | string | Name of the news source, e.g.`banking-dive` |

**Request body:** [`NewsSourceRequest`](#newssourcerequest)

**Responses**

| Status | Body                               | Description                                                                                                                                  |
| ------ | ---------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| 200    | [`NewsSourceDto`](#newssourcedto) | News source updated successfully                                                                                                             |
| 400    | [`ErrorResponse`](#errorresponse) | Validation failed, the source does not exist, the new name/baseUrl/rssUrl already belongs to a different source, or the URLs are unreachable |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication)                                                                                 |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`                                                                                                                    |

---

### `PATCH /api/news-sources/{sourceName}/failure`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Increments the consecutive scrape-failure counter for the given source. Used by the scraper to track sources that repeatedly fail to be reached. If the incremented count reaches **3**, the source is automatically disabled (`isDisabled = true`).

**Path parameters**

| Name       | Type   | Description                                   |
| ---------- | ------ | --------------------------------------------- |
| sourceName | string | Name of the news source, e.g.`banking-dive` |

**Responses**

| Status | Body                               | Description                                                  |
| ------ | ---------------------------------- | ------------------------------------------------------------ |
| 200    | [`NewsSourceDto`](#newssourcedto) | Failure count incremented successfully                       |
| 400    | [`ErrorResponse`](#errorresponse) | News source does not exist                                   |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                    |

---

### `PATCH /api/news-sources/{sourceName}/reset`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Resets a source's failure counter to `0` and re-enables it (`isDisabled = false`) if it had been disabled.

**Path parameters**

| Name       | Type   | Description                                   |
| ---------- | ------ | --------------------------------------------- |
| sourceName | string | Name of the news source, e.g.`banking-dive` |

**Responses**

| Status | Body                               | Description                                                  |
| ------ | ---------------------------------- | ------------------------------------------------------------ |
| 200    | [`NewsSourceDto`](#newssourcedto) | Failure count reset successfully                             |
| 400    | [`ErrorResponse`](#errorresponse) | News source does not exist                                   |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                    |

---

## Articles

Base path: `/api/articles`

### `GET /api/articles`

List all articles across all sources, paginated.

**Query parameters**

| Name  | Type | Default | Description                 |
| ----- | ---- | ------- | --------------------------- |
| page  | int  | `0`   | Zero-based page index       |
| count | int  | `20`  | Number of articles per page |

**Responses**

| Status | Body                           | Description                     |
| ------ | ------------------------------ | ------------------------------- |
| 200    | [`ArticleDto[]`](#articledto) | Articles retrieved successfully |

---

### `GET /api/articles/source/{sourceName}`

List articles published by a specific news source, paginated.

**Path parameters**

| Name       | Type   | Description             |
| ---------- | ------ | ----------------------- |
| sourceName | string | Name of the news source |

**Query parameters**

| Name  | Type | Default | Description                 |
| ----- | ---- | ------- | --------------------------- |
| page  | int  | `0`   | Zero-based page index       |
| count | int  | `20`  | Number of articles per page |

**Responses**

| Status | Body                           | Description                     |
| ------ | ------------------------------ | ------------------------------- |
| 200    | [`ArticleDto[]`](#articledto) | Articles retrieved successfully |

---

### `GET /api/articles/story/{storyId}`

List articles that belong to a given story cluster, paginated.

**Path parameters**

| Name    | Type   | Description             |
| ------- | ------ | ----------------------- |
| storyId | string | ID of the story cluster |

**Query parameters**

| Name  | Type | Default | Description                 |
| ----- | ---- | ------- | --------------------------- |
| page  | int  | `0`   | Zero-based page index       |
| count | int  | `20`  | Number of articles per page |

**Responses**

| Status | Body                           | Description                     |
| ------ | ------------------------------ | ------------------------------- |
| 200    | [`ArticleDto[]`](#articledto) | Articles retrieved successfully |

---

### `GET /api/articles/topic/{topicName}`

List articles tagged with a given topic, paginated.

**Path parameters**

| Name      | Type   | Description                         |
| --------- | ------ | ----------------------------------- |
| topicName | string | Name of the topic, e.g.`regulation_legal_enforcement` |

**Query parameters**

| Name  | Type | Default | Description                 |
| ----- | ---- | ------- | --------------------------- |
| page  | int  | `0`   | Zero-based page index       |
| count | int  | `20`  | Number of articles per page |

**Responses**

| Status | Body                           | Description                     |
| ------ | ------------------------------ | ------------------------------- |
| 200    | [`ArticleDto[]`](#articledto) | Articles retrieved successfully |

---

### `POST /api/articles`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Persist a scraped article. Validated before saving:

1. Fails if an article with the same `url` already exists.
2. Fails if `sourceName` does not reference a registered news source.
3. Fails if `url` does not belong to the referenced source's `baseUrl`.

**Request body:** [`ArticleRequest`](#articlerequest)

**Responses**

| Status | Body                               | Description                                                                                                           |
| ------ | ---------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| 201    | [`ArticleDto`](#articledto)       | Article created successfully                                                                                          |
| 400    | [`ErrorResponse`](#errorresponse) | Validation failed, article URL already exists, source does not exist, or the URL does not match the source's base URL |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication)                                                          |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                                                                             |

---

### `GET /api/articles/exists`

Check whether an article with the given URL has already been ingested. Used by the scraper to skip already-seen articles.

**Query parameters**

| Name | Type   | Description                                                                   |
| ---- | ------ | ----------------------------------------------------------------------------- |
| url  | string | Canonical URL to check, e.g.`https://www.bankingdive.com/news/regional-lender-acquires-rival/` |

**Responses**

| Status | Body        | Description            |
| ------ | ----------- | ---------------------- |
| 200    | `boolean` | Existence check result |

---

### `GET /api/articles/by-url`

Get a single article by its canonical URL.

**Query parameters**

| Name | Type   | Description                  |
| ---- | ------ | ---------------------------- |
| url  | string | Canonical URL of the article |

**Responses**

| Status | Body                               | Description                    |
| ------ | ---------------------------------- | ------------------------------ |
| 200    | [`ArticleDto`](#articledto)       | Article retrieved successfully |
| 400    | [`ErrorResponse`](#errorresponse) | Article does not exist         |

---

### `PATCH /api/articles/topic`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Tags an article with a single topic (score 1.0), replacing any topics it already had. Kept for compatibility; the banking classifier uses [`PATCH /api/articles/topics`](#patch-apiarticlestopics). If a [subscription](#subscriptions) exists for the topic, publishes a notification message — see [RabbitMQ side effects](#rabbitmq-side-effects).

**Request body:** [`TopicSetRequest`](#topicsetrequest)

**Responses**

| Status | Body                               | Description                                                  |
| ------ | ---------------------------------- | ------------------------------------------------------------ |
| 200    | [`ArticleDto`](#articledto)       | Topic set successfully                                       |
| 400    | [`ErrorResponse`](#errorresponse) | Article does not exist                                       |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                    |

---

### `PATCH /api/articles/topics`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Replaces all of an article's topics with a scored multi-label set, as produced by the banking topic classifier. Duplicate names keep the highest score. Every topic must already exist (seeded by the `V4__seed_banking_topics` migration); if any is unknown, nothing is written. A notification is published for each topic that has a [subscription](#subscriptions).

**Request body:** [`TopicsSetRequest`](#topicssetrequest)

**Responses**

| Status | Body                               | Description                                                       |
| ------ | ---------------------------------- | ----------------------------------------------------------------- |
| 200    | [`ArticleDto`](#articledto)       | Topics set; `topics` lists them highest score first               |
| 400    | [`ErrorResponse`](#errorresponse) | Article or a topic does not exist, or a score is outside [0, 1]   |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication)      |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                         |

---

### `PATCH /api/articles/enrichment`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Stores the enricher worker's output for one article: sets `sentimentLabel` / `sentimentScore` and **replaces** the article's `MENTIONS` relationships with the given set (an empty list removes them). Every company id must exist; if any is unknown nothing is written. Afterwards the story-company events (`(:Story)-[:AFFECTS]->(:Company)`) of the article's story are recomputed; if the article is not clustered yet, they are created when it joins a story.

**Request body:** [`EnrichmentRequest`](#enrichmentrequest)

**Responses**

| Status | Body                               | Description                                                     |
| ------ | ---------------------------------- | --------------------------------------------------------------- |
| 204    | (empty)                            | Enrichment stored and events refreshed                          |
| 503    | [`ErrorResponse`](#errorresponse) | Temporary database conflict after internal retries; retry the request |
| 400    | [`ErrorResponse`](#errorresponse) | Article or a company does not exist, or validation failed       |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication)    |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                       |

**Events derived from enrichment** (`AFFECTS` properties): `firstSeen` (earliest article of the story mentioning the company), `articleCount`, `sentiment` (mean company sentiment) and `impactScore` in [0, 1] = 0.25 × min(sourceCount / 5, 1) + 0.15 × min(mentioning articles / 3, 1) + 0.20 × strongest salience + 0.25 × max(topic weight × topic score) + 0.15 × |sentiment| + 0.10 if a regulator or central bank published part of the story, capped at 1. Topic weights: enforcement and M&A 1.0, earnings 0.8, monetary policy / leadership / debt 0.6, dividends / analyst / company news 0.5, markets 0.4, FX / macro 0.3, other 0.2. Also recomputed when an article joins a story (`PATCH /api/stories/{storyId}/attach`) or gets new topics.

---

### `POST /api/articles/trigger-scrape`

**Requires:** `ROLE_ADMIN`

Publishes a scrape job for every registered news source onto the `scrape.jobs` queue, to be picked up by the scraper worker. Queues every source, regardless of its interval. Separately, the scheduler checks every 5 minutes and queues only enabled sources whose own `scrapeIntervalMinutes` has elapsed.

**Responses**

| Status | Body                               | Description                                                  |
| ------ | ---------------------------------- | ------------------------------------------------------------ |
| 202    | `integer`                        | Number of scrape jobs queued                                 |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`                                    |

---

### `GET /api/articles/search`

Full-text search over article titles and body text, ordered by relevance. Matching is prefix-based per term (e.g. `"sen bud"` matches text containing words starting with `sen` and `bud`).

**Query parameters**

| Name  | Type   | Default | Description                       |
| ----- | ------ | ------- | --------------------------------- |
| q     | string | —      | Free-text search query (required) |
| page  | int    | `0`   | Zero-based page index             |
| count | int    | `20`  | Number of articles per page       |

**Responses**

| Status | Body                           | Description                              |
| ------ | ------------------------------ | ---------------------------------------- |
| 200    | [`ArticleDto[]`](#articledto) | Matching articles retrieved successfully |

---

## Stories

Base path: `/api/stories`

### `GET /api/stories`

List all story clusters, paginated, ordered by most recently updated. Each story is hydrated with (up to 100) of its articles.

**Query parameters**

| Name  | Type | Default | Description                |
| ----- | ---- | ------- | -------------------------- |
| page  | int  | `0`   | Zero-based page index      |
| count | int  | `20`  | Number of stories per page |

**Responses**

| Status | Body                       | Description                    |
| ------ | -------------------------- | ------------------------------ |
| 200    | [`StoryDto[]`](#storydto) | Stories retrieved successfully |

---

### `GET /api/stories/recent`

List stories that had activity within the last N days. Used to identify live clustering candidates.

**Query parameters**

| Name | Type | Default | Description              |
| ---- | ---- | ------- | ------------------------ |
| days | int  | `7`   | Look-back window in days |

**Responses**

| Status | Body                       | Description                           |
| ------ | -------------------------- | ------------------------------------- |
| 200    | [`StoryDto[]`](#storydto) | Recent stories retrieved successfully |

---

### `POST /api/stories`

**Requires:** `ROLE_SYSTEM`

Creates a new, empty story cluster with the given title.

**Query parameters**

| Name  | Type   | Description                                                            |
| ----- | ------ | ---------------------------------------------------------------------- |
| title | string | Title for the new story cluster, e.g.`Regional lender agrees to acquire rival in $2.1B deal` |

**Responses**

| Status | Body                               | Description                                                  |
| ------ | ---------------------------------- | ------------------------------------------------------------ |
| 201    | [`StoryDto`](#storydto)           | Story created successfully                                   |
| 400    | [`ErrorResponse`](#errorresponse) | Title is blank                                               |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_SYSTEM`                                   |

---

### `GET /api/stories/by-article`

Returns the story cluster that the article at the given URL is attached to.

**Query parameters**

| Name | Type   | Description                  |
| ---- | ------ | ---------------------------- |
| url  | string | Canonical URL of the article |

**Responses**

| Status | Body                               | Description                                             |
| ------ | ---------------------------------- | ------------------------------------------------------- |
| 200    | [`StoryDto`](#storydto)           | Story retrieved successfully                            |
| 400    | [`ErrorResponse`](#errorresponse) | Article does not exist, or is not attached to any story |

---

### `PATCH /api/stories/{storyId}/attach`

**Requires:** `ROLE_SYSTEM`

Creates a `BELONGS_TO` edge from the article to the story. Increments `articleCount` unconditionally, and `sourceCount` only if the article's source hasn't previously contributed to this story. Idempotent — re-attaching the same article has no effect. If a [subscription](#subscriptions) exists for the story, publishes a notification message — see [RabbitMQ side effects](#rabbitmq-side-effects).

**Path parameters**

| Name    | Type   | Description            |
| ------- | ------ | ---------------------- |
| storyId | string | ID of the target story |

**Request body:** [`AttachArticleRequest`](#attacharticlerequest)

**Responses**

| Status | Body                               | Description                                                  |
| ------ | ---------------------------------- | ------------------------------------------------------------ |
| 200    | [`StoryDto`](#storydto)           | Article attached successfully                                |
| 400    | [`ErrorResponse`](#errorresponse) | Story or article does not exist                              |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_SYSTEM`                                   |

---

### `GET /api/stories/exists`

Check whether a story with the given ID exists.

**Query parameters**

| Name    | Type   | Description              |
| ------- | ------ | ------------------------ |
| storyId | string | ID of the story to check |

**Responses**

| Status | Body        | Description            |
| ------ | ----------- | ---------------------- |
| 200    | `boolean` | Existence check result |

---

### `GET /api/stories/search`

Full-text search over story titles, ordered by relevance. Matching is prefix-based per term.

**Query parameters**

| Name  | Type   | Default | Description                       |
| ----- | ------ | ------- | --------------------------------- |
| q     | string | —      | Free-text search query (required) |
| page  | int    | `0`   | Zero-based page index             |
| count | int    | `20`  | Number of stories per page        |

**Responses**

| Status | Body                       | Description                             |
| ------ | -------------------------- | --------------------------------------- |
| 200    | [`StoryDto[]`](#storydto) | Matching stories retrieved successfully |

---

## Topics

Base path: `/api/topics`

### `GET /api/topics`

List every topic that has been assigned to at least one article, along with its article count.

**Responses**

| Status | Body                       | Description                   |
| ------ | -------------------------- | ----------------------------- |
| 200    | [`TopicDto[]`](#topicdto) | Topics retrieved successfully |

---

### `GET /api/topics/exists`

Check whether a topic with the given name already exists.

**Query parameters**

| Name | Type   | Description                                  |
| ---- | ------ | -------------------------------------------- |
| name | string | Name of the topic to check, e.g.`regulation_legal_enforcement` |

**Responses**

| Status | Body        | Description            |
| ------ | ----------- | ---------------------- |
| 200    | `boolean` | Existence check result |

---

## Companies

Base path: `/api/companies`

### `GET /api/companies`

Public. Returns the whole company universe (a few hundred entries: banks, fintechs, regulators, central banks) with aliases and listings, sorted by name. Seeded by the `V5__seed_company_universe` migration. Used by the enricher for entity linking and, later, by the analytics service as its ticker universe.

**Query parameters**

| Name | Type | Required | Description                                                         |
| ---- | ---- | -------- | ------------------------------------------------------------------- |
| kind | enum | no       | Only `BANK`, `FINTECH`, `REGULATOR`, `CENTRAL_BANK` or `OTHER`     |

**Responses**

| Status | Body                           | Description          |
| ------ | ------------------------------ | -------------------- |
| 200    | [`CompanyDto[]`](#companydto) | Companies retrieved  |
| 400    | [`ErrorResponse`](#errorresponse) | Unknown `kind`   |

---

## Events

Base path: `/api/events`. An event is a `(:Story)-[:AFFECTS]->(:Company)` relationship, derived by the provider (see `PATCH /api/articles/enrichment`). Every committed change is also published as `event.upserted` (see [RabbitMQ side effects](#rabbitmq-side-effects)).

### `GET /api/events`

Public. Events ordered by last update, for consumers rebuilding their copy (the analytics service).

| Name  | Type   | Required | Description                                  |
| ----- | ------ | -------- | -------------------------------------------- |
| since | string | no       | ISO-8601 datetime; omit for all events       |
| page  | int    | no       | Zero-based, default 0                        |
| count | int    | no       | 1–1000, default 200                          |

Each item: `storyId`, `companyId`, `companyName`, `symbol` / `exchange` / `currency` (primary listing, null if unlisted), `storyTitle`, `firstSeen`, `impactScore`, `articleCount`, `sentiment`, `topics`, `deleted` (always false here), `version` (epoch ms; 0 for events last changed before versioning existed).

### `PATCH /api/events/{storyId}/{companyId}/impact`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`. Written by the analytics service after an event study; does not publish `event.upserted`.

```json
{"benchmark": "KBE", "abnormalReturn": -0.018, "car1": -0.031, "car5": null, "beta": 1.12, "pValue": 0.04, "estimationDays": 220}
```

Stored on the `AFFECTS` relationship as `benchmark`, `abnormalReturn`, `car1`, `car5`, `beta`, `pValue`, `estimationDays`, `impactComputedAt`. Returns 204, or 400 if the event does not exist.

---

## Subscriptions

Base path: `/api/subscriptions`

A subscription tracks interest in a story or a topic via a `count` — each subscribe call creates it at `count = 1` or increments an existing one; each unsubscribe call decrements it, deleting the subscription once `count` reaches `0`. Modeled as a `Subscription` node with a `SUBSCRIBES_TO` relationship to either a `Story` or a `Topic` node.

Every endpoint in this controller requires `ROLE_ADMIN` or `ROLE_SYSTEM`.

### `POST /api/subscriptions/story/{storyId}`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Creates a subscription to the given story with `count = 1`, or increments the count if one already exists.

**Path parameters**

| Name    | Type   | Description                     |
| ------- | ------ | ------------------------------- |
| storyId | string | ID of the story to subscribe to |

**Responses**

| Status | Body                                   | Description                                                  |
| ------ | -------------------------------------- | ------------------------------------------------------------ |
| 200    | [`SubscriptionDto`](#subscriptiondto) | Subscribed successfully                                      |
| 400    | [`ErrorResponse`](#errorresponse)     | Story does not exist                                         |
| 401    | [`ErrorResponse`](#errorresponse)     | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse)     | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                    |

---

### `DELETE /api/subscriptions/story/{storyId}`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Decrements the subscription count for the given story. The subscription is deleted once the count reaches `0`.

**Path parameters**

| Name    | Type   | Description                         |
| ------- | ------ | ----------------------------------- |
| storyId | string | ID of the story to unsubscribe from |

**Responses**

| Status | Body                               | Description                                                  |
| ------ | ---------------------------------- | ------------------------------------------------------------ |
| 204    | —                                 | Unsubscribed successfully                                    |
| 400    | [`ErrorResponse`](#errorresponse) | No subscription exists for this story                        |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                    |

---

### `POST /api/subscriptions/topic/{topicName}`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Creates a subscription to the given topic with `count = 1`, or increments the count if one already exists.

**Path parameters**

| Name      | Type   | Description                                         |
| --------- | ------ | --------------------------------------------------- |
| topicName | string | Name of the topic to subscribe to, e.g.`regulation_legal_enforcement` |

**Responses**

| Status | Body                                   | Description                                                  |
| ------ | -------------------------------------- | ------------------------------------------------------------ |
| 200    | [`SubscriptionDto`](#subscriptiondto) | Subscribed successfully                                      |
| 400    | [`ErrorResponse`](#errorresponse)     | Topic does not exist                                         |
| 401    | [`ErrorResponse`](#errorresponse)     | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse)     | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                    |

---

### `DELETE /api/subscriptions/topic/{topicName}`

**Requires:** `ROLE_ADMIN` or `ROLE_SYSTEM`

Decrements the subscription count for the given topic. The subscription is deleted once the count reaches `0`.

**Path parameters**

| Name      | Type   | Description                           |
| --------- | ------ | ------------------------------------- |
| topicName | string | Name of the topic to unsubscribe from |

**Responses**

| Status | Body                               | Description                                                  |
| ------ | ---------------------------------- | ------------------------------------------------------------ |
| 204    | —                                 | Unsubscribed successfully                                    |
| 400    | [`ErrorResponse`](#errorresponse) | No subscription exists for this topic                        |
| 401    | [`ErrorResponse`](#errorresponse) | Missing/invalid token — see[Authentication](#authentication) |
| 403    | [`ErrorResponse`](#errorresponse) | Token lacks`ROLE_ADMIN`/`ROLE_SYSTEM`                    |

---

## RabbitMQ side effects

The Provider is a RabbitMQ producer only — it never consumes. Full topology, queue names, and consumers are documented in the README's [Message Queue Topology](../README.md#message-queue-topology-rabbitmq) section. Summary of what the HTTP API above triggers:

| Endpoint                                                                              | Condition                                   | Queue                     | Message                                                               |
| ------------------------------------------------------------------------------------- | ------------------------------------------- | ------------------------- | --------------------------------------------------------------------- |
| `POST /api/articles/trigger-scrape` | always, once per registered news source     | `scrape.jobs`           | `{"name": "<sourceName>", "retry_count": 0}`                        |
| `ScrapeScheduler` (every 5 min, internal) | once per enabled source whose `scrapeIntervalMinutes` has elapsed | `scrape.jobs` | `{"name": "<sourceName>", "retry_count": 0}` |
| Event refresh (enrichment, story attach, topic writes) | after commit, once per created/updated/removed event | `news_monitor` exchange, key `event.upserted` → `analytics.events` | `EventMessage`: event fields + `deleted` + `version` |
| `PATCH /api/articles/topics` | once per topic in the request that has a subscription | `article.notifications` | `{"name": "<topicName>", "articleUrl": "<url>", "type": "TOPIC"}` |
| `PATCH /api/articles/topic`                                                         | only if a subscription exists for the topic | `article.notifications` | `{"name": "<topicName>", "articleUrl": "<url>", "type": "TOPIC"}`   |
| `PATCH /api/stories/{storyId}/attach`                                               | only if a subscription exists for the story | `article.notifications` | `{"name": "<story title>", "articleUrl": "<url>", "type": "STORY"}` |

---

## Objects

### `NewsSourceDto`

Response shape for a news source.

| Field        | Type    | Description                                                                 |
| ------------ | ------- | --------------------------------------------------------------------------- |
| name         | string  | Source's unique name                                                        |
| baseUrl      | string  | Source's homepage/base URL                                                  |
| rssUrl       | string  | Source's RSS feed URL                                                       |
| category     | enum    | `NEWS`, `TRADE_PRESS`, `REGULATOR`, `CENTRAL_BANK` or `FILING`              |
| region       | enum    | `US`, `UK`, `EU` or `GLOBAL`                                                |
| paywalled    | boolean | Whether article bodies sit behind a paywall (headline-only ingestion)       |
| language     | string  | ISO 639-1 language code, e.g. `en`                                          |
| scrapeIntervalMinutes | int | Minutes between scheduled scrapes (5–1440, default 60)              |
| lastScrapeQueuedAt | datetime | When the scheduler last queued this source (null = never)       |
| failureCount | int     | Consecutive scrape-failure count                                            |
| disabled     | boolean | Whether the source is currently disabled                                    |
| articleCount | long    | Total number of articles ingested from this source                          |
| notes        | string  | Free-text notes about this source                                           |

```json
{
  "name": "banking-dive",
  "baseUrl": "https://www.bankingdive.com",
  "rssUrl": "https://www.bankingdive.com/feeds/news/",
  "category": "TRADE_PRESS",
  "region": "US",
  "paywalled": false,
  "language": "en",
  "scrapeIntervalMinutes": 30,
  "lastScrapeQueuedAt": "2026-11-11T16:05:00Z",
  "failureCount": 0,
  "disabled": false,
  "articleCount": 42,
  "notes": null
}
```

### `NewsSourceRequest`

Request body for registering/updating a news source.

| Field     | Type    | Required | Constraints                                                     |
| --------- | ------- | -------- | --------------------------------------------------------------- |
| name      | string  | yes      | not null, not empty                                             |
| baseUrl   | string  | yes      | not null, not empty                                             |
| rssUrl    | string  | yes      | not null, not empty                                             |
| category  | enum    | yes      | `NEWS`, `TRADE_PRESS`, `REGULATOR`, `CENTRAL_BANK`, `FILING`    |
| region    | enum    | yes      | `US`, `UK`, `EU`, `GLOBAL`                                      |
| paywalled | boolean | no       | defaults to `false`                                             |
| language  | string  | no       | defaults to `en`                                                |
| scrapeIntervalMinutes | int | no | 5–1440, defaults to `60` |
| notes     | string  | no       | free text                                                       |

```json
{
  "name": "banking-dive",
  "baseUrl": "https://www.bankingdive.com",
  "rssUrl": "https://www.bankingdive.com/feeds/news/",
  "category": "TRADE_PRESS",
  "region": "US"
}
```

### `ArticleDto`

Response shape for an article.

| Field          | Type            | Description                                                        |
| -------------- | --------------- | ------------------------------------------------------------------ |
| author         | string          | Article author (may be null/blank)                                 |
| title          | string          | Article title                                                      |
| url            | string          | Canonical article URL                                              |
| bodyText       | string          | Full article body text                                             |
| publishedAt    | datetime        | ISO-8601 local date-time the article was published                 |
| topic          | string          | Highest-scoring topic, if tagged (kept for backwards compatibility)|
| topics         | `{name, score}[]` | All topics with classifier confidence, highest first             |
| sentimentLabel | string          | `positive` / `negative` / `neutral` (null until enriched)          |
| sentimentScore | number          | Signed sentiment in [-1, 1] (null until enriched)                  |
| language       | string          | ISO 639-1 code                                                     |
| source         | string          | Name of the source that published it                               |

```json
{
  "author": "Jane Doe",
  "title": "Regional lender fined over AML controls",
  "url": "https://www.bankingdive.com/news/example/",
  "bodyText": "Full article text goes here.",
  "publishedAt": "2026-07-01T14:30:00",
  "topic": "regulation_legal_enforcement",
  "topics": [
    {"name": "regulation_legal_enforcement", "score": 0.91},
    {"name": "leadership_personnel", "score": 0.34}
  ],
  "sentimentLabel": "negative",
  "sentimentScore": -0.62,
  "language": "en",
  "source": "banking-dive"
}
```

### `ArticleRequest`

Request body for saving an article.

| Field       | Type     | Required | Constraints                                                 |
| ----------- | -------- | -------- | ----------------------------------------------------------- |
| author      | string   | no       | —                                                          |
| title       | string   | yes      | not null, not empty                                         |
| url         | string   | yes      | not null, not empty; must belong to`sourceName`'s baseUrl |
| bodyText    | string   | yes      | not null, not empty                                         |
| publishedAt | datetime | yes      | not null (ISO-8601 local date-time)                         |
| sourceName  | string   | yes      | not null, not empty; must reference an existing news source |

```json
{
  "author": "Jane Doe",
  "title": "Regional lender agrees to acquire rival in $2.1B deal",
  "url": "https://www.bankingdive.com/news/regional-lender-acquires-rival/",
  "bodyText": "Full article text goes here.",
  "publishedAt": "2026-07-01T14:30:00",
  "sourceName": "banking-dive"
}
```

### `TopicSetRequest`

Request body for tagging an article with a topic.

| Field | Type   | Required | Constraints                             |
| ----- | ------ | -------- | --------------------------------------- |
| url   | string | yes      | not null, not empty; article must exist |
| topic | string | yes      | not null, not empty                     |

```json
{
  "url": "https://www.bankingdive.com/news/regional-lender-acquires-rival/",
  "topic": "m_and_a_investments"
}
```

### `TopicsSetRequest`

Request body for setting an article's scored topics.

| Field          | Type                | Required | Constraints                                  |
| -------------- | ------------------- | -------- | -------------------------------------------- |
| url            | string              | yes      | not null, not empty                          |
| topics         | `{name, score}[]`   | yes      | not empty                                    |
| topics[].name  | string              | yes      | an existing topic name                       |
| topics[].score | number              | yes      | 0.0 – 1.0                                    |

```json
{
  "url": "https://www.bankingdive.com/news/example/",
  "topics": [
    {"name": "regulation_legal_enforcement", "score": 0.91},
    {"name": "leadership_personnel", "score": 0.34}
  ]
}
```

### `EnrichmentRequest`

Request body for storing an article's enrichment.

| Field                  | Type      | Required | Constraints                                   |
| ---------------------- | --------- | -------- | --------------------------------------------- |
| url                    | string    | yes      | an existing article                           |
| sentimentLabel         | string    | yes      | `positive`, `negative` or `neutral`           |
| sentimentScore         | number    | yes      | -1.0 – 1.0 (P(positive) − P(negative))        |
| mentions               | array     | yes      | may be empty                                  |
| mentions[].companyId   | string    | yes      | an existing company id                        |
| mentions[].count       | int       | yes      | ≥ 1                                           |
| mentions[].salience    | number    | yes      | 0.0 – 1.0                                     |
| mentions[].inTitle     | boolean   | yes      |                                               |
| mentions[].sentiment   | number    | no       | -1.0 – 1.0, null if unknown                   |

```json
{
  "url": "https://www.fca.org.uk/news/press-releases/example",
  "sentimentLabel": "negative",
  "sentimentScore": -0.63,
  "mentions": [
    {"companyId": "6f1c…", "count": 5, "salience": 1.0, "inTitle": true, "sentiment": -0.58},
    {"companyId": "a92e…", "count": 2, "salience": 1.0, "inTitle": true, "sentiment": -0.93}
  ]
}
```

### `CompanyDto`

| Field   | Type          | Description                                                     |
| ------- | ------------- | --------------------------------------------------------------- |
| id      | string        | Stable UUID                                                     |
| name    | string        | Display name                                                    |
| kind    | enum          | `BANK`, `FINTECH`, `REGULATOR`, `CENTRAL_BANK`, `OTHER`         |
| country | string        | ISO 3166-1 alpha-2 (`EU` / `INT` for supranational bodies)      |
| lei     | string        | Legal Entity Identifier when verified by exact GLEIF match, else null |
| aliases | string[]      | Alternative names used for entity linking                       |
| tickers | `TickerDto[]` | `{symbol, exchange (ISO 10383 MIC), currency, primary}`, primary first |

```json
{
  "id": "6f1c…",
  "name": "Barclays",
  "kind": "BANK",
  "country": "GB",
  "lei": "213800LBQA1Y9L22JB70",
  "aliases": ["Barclays Bank"],
  "tickers": [
    {"symbol": "BARC", "exchange": "XLON", "currency": "GBP", "primary": true},
    {"symbol": "BCS", "exchange": "XNYS", "currency": "USD", "primary": false}
  ]
}
```

### `StoryDto`

Response shape for a story cluster.

| Field         | Type                           | Description                                                                        |
| ------------- | ------------------------------ | ---------------------------------------------------------------------------------- |
| id            | string                         | Unique identifier of the story                                                     |
| title         | string                         | Title derived from the first attached article                                      |
| createdAt     | datetime                       | When the story was first created                                                   |
| lastUpdated   | datetime                       | When the last article was attached                                                 |
| articleCount  | int                            | Total number of articles attached to this story                                    |
| sourceCount   | int                            | Number of distinct sources that contributed                                        |
| trendingScore | double                         | Precomputed trending score (recency + velocity + source diversity)                 |
| articles      | [`ArticleDto[]`](#articledto) | Attached articles; empty unless explicitly populated (e.g. by`GET /api/stories`) |

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Regional lender agrees to acquire rival in $2.1B deal",
  "createdAt": "2026-07-01T14:00:00Z",
  "lastUpdated": "2026-07-01T18:30:00Z",
  "articleCount": 12,
  "sourceCount": 5,
  "trendingScore": 3.14,
  "articles": []
}
```

### `AttachArticleRequest`

Request body for attaching an article to a story.

| Field      | Type   | Required | Constraints                             |
| ---------- | ------ | -------- | --------------------------------------- |
| articleUrl | string | yes      | not null, not empty; article must exist |

```json
{
  "articleUrl": "https://www.bankingdive.com/news/regional-lender-acquires-rival/"
}
```

### `TopicDto`

Response shape for a topic.

| Field        | Type   | Description                                         |
| ------------ | ------ | --------------------------------------------------- |
| name         | string | Topic name                                          |
| articleCount | long   | Number of articles currently tagged with this topic |

```json
{
  "name": "regulation_legal_enforcement",
  "articleCount": 17
}
```

### `SubscriptionDto`

Response shape for a subscription. Exactly one of `storyId`/`topicName` is populated, depending on what was subscribed to.

| Field     | Type   | Description                                                                  |
| --------- | ------ | ---------------------------------------------------------------------------- |
| id        | string | Subscription ID                                                              |
| storyId   | string | ID of the subscribed story, if this is a story subscription (else`null`)   |
| topicName | string | Name of the subscribed topic, if this is a topic subscription (else`null`) |
| count     | int    | Number of active subscriptions                                               |

```json
{
  "id": "3f1b2c3d-4e5f-6789-0abc-def123456789",
  "storyId": null,
  "topicName": "regulation_legal_enforcement",
  "count": 1
}
```

### `ErrorResponse`

Standard error shape returned by every non-2xx response.

| Field     | Type               | Description                                                 |
| --------- | ------------------ | ----------------------------------------------------------- |
| timestamp | datetime           | When the error occurred                                     |
| status    | int                | HTTP status code                                            |
| error     | string             | HTTP status reason phrase                                   |
| message   | string             | Human-readable error message                                |
| details   | map<string,string> | Field-level validation errors, if any (empty map otherwise) |

```json
{
  "timestamp": "2026-07-03T10:15:30",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "details": {
    "title": "must not be empty",
    "url": "must not be empty"
  }
}
```

---

## Error handling

All exceptions are mapped to an [`ErrorResponse`](#errorresponse) by a global exception handler:

| Exception                           | Status      | Notes                                                                                                                                    |
| ----------------------------------- | ----------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `MethodArgumentNotValidException` | 400         | `@Valid @RequestBody` failures — `details` populated with per-field messages                                                        |
| `ConstraintViolationException`    | 400         | `@RequestParam`/`@PathVariable` validation failures                                                                                  |
| `ResponseStatusException`         | (as thrown) | Explicit`throw new ResponseStatusException(...)`                                                                                       |
| `BusinessException`               | 400         | Domain rule violations (duplicate source/article, mismatched URLs, unreachable URLs, unknown source/story/topic, subscription not found) |
| Any other unhandled exception       | 500         | Generic`"Unexpected error occurred"` message, no internal details leaked                                                               |

Authentication/authorization failures don't go through this handler (Spring Security intercepts before the controller layer), but still produce the same `ErrorResponse` shape — see [Authentication](#authentication) for the 401/403 cases.
