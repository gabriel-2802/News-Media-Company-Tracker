# Team quickstart

Run the whole banking/fintech news monitor locally with one command. Full details: [README](../README.md).

## 1. One-time setup

Needs Docker, JDK 21 (`brew install openjdk@21`), Maven and Python 3.11 (`brew install python@3.11`).

```bash
make env              # creates .env from env.example
```

In `.env`, set your own `NEO4J_PASSWORD`, `RABBITMQ_PASSWORD`, `POSTGRES_PASSWORD`, `MARKET_DB_PASSWORD` and `SYSTEM_CODE` (any value; services and workers share it), and put your contact email in `USER_AGENT` (keep the quotes). For prices, add a free [Twelve Data](https://twelvedata.com) key: `MARKET_DATA_PROVIDER=twelvedata`, `TWELVEDATA_API_KEY=...`.

```bash
mkdir -p backend/keys      # JWT key pair, used by all services
openssl genpkey -algorithm RSA -out backend/keys/jwt-private.pem -pkeyopt rsa_keygen_bits:2048
openssl pkey -in backend/keys/jwt-private.pem -pubout -out backend/keys/jwt-public.pem
make setup-workers         # Python environments for the workers
```

The classifier model (`backend/workers/classifier/cls_model_output/`, about 440 MB) is not in git: copy it from a teammate, or train it (README, *Classifier Worker*).

## 2. Run

```bash
make run       # infra + manager, provider, analytics + 3 workers, with health checks (~1 min)
make status    # one line per component
make urls      # all URLs and the logins from your .env
make stop      # stop everything (data is kept)
```

Logs: `.run/logs/<name>.log`. Health check of the whole pipeline: `make e2e` (~3 min).

## 3. Swagger and UIs

| What | URL |
| --- | --- |
| Manager API (auth, users, watchlists, alerts) | http://localhost:9000/manager/swagger-ui.html |
| Provider API (news, stories, companies, events) | http://localhost:8080/news-provider/swagger-ui.html |
| Analytics API (charts, prices, event studies) | http://localhost:8090/analytics/swagger-ui.html |
| Neo4j Browser | http://localhost:7474 |
| RabbitMQ management | http://localhost:15672 |

Protected endpoints: log in with `POST /manager/api/auth/login`, then click **Authorize** in Swagger and paste the `token`. Chart and read endpoints are public. Example chart: `GET /analytics/api/charts/companies/JPM/overlay`.

## 4. Logins (local development)

| Where | Username | Password |
| --- | --- | --- |
| App admin (manager) | `admin@example.com` | `Admin123!` |
| Neo4j Browser | `neo4j` | `NEO4J_PASSWORD` in your `.env` |
| RabbitMQ | `RABBITMQ_USER` in `.env` (default `admin`) | `RABBITMQ_PASSWORD` |
| Postgres `news_monitor` (port 5433) | `POSTGRES_USER` (default `postgres`) | `POSTGRES_PASSWORD` |
| Postgres `market` | `analytics` | `MARKET_DB_PASSWORD` |

New users register with `POST /manager/api/auth/register`. Passing `adminCode` = `devcode123` (default) makes the account an admin. Both admin defaults are for local use only: set `APP_ADMIN_EMAIL`, `APP_ADMIN_PASSWORD` and `APP_ADMIN_REGISTER_CODE` in `.env` anywhere shared. Never commit `.env`.

## 5. Initial data

| Data | How it gets there |
| --- | --- |
| News sources (27), topics (13), companies (356) and tickers (343) | Automatic: Neo4j migrations on the first `make run` |
| News articles | Automatic: the provider queues due sources a minute after start, then every 15–60 min per source. Now: `make scrape-now` |
| Topics, sentiment, company links, events | Automatic: the classifier and enricher process every new article |
| Daily prices (US listings) | Needs the Twelve Data key. Now: `make prices-now`; then every weekday after the US close. The first full load (2 years, ~315 listings) takes about 45 min at 8 requests/min |
| Event studies (abnormal returns) | Automatic every 30 min once prices exist. Now: `make studies-now` |

Empty dashboards right after setup are normal: run `make scrape-now`, wait a few minutes, check `make status`.

## Troubleshooting

- `make run` stops at a red ✗ and names the missing piece (Docker, JDK 21, venvs, classifier model, keys).
- A component not healthy: see `.run/logs/<name>.log`, then `make stop && make run`.
- On macOS with the repo in iCloud Drive, sync can create `"<name> 2"` copies; keep the repo outside iCloud.
