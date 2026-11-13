# Charts API (analytics service)

Read-only JSON for investor charts, served by the analytics service under `http://localhost:8090/analytics/api/charts`. Public (no token), like the provider's reads. Interactive docs: `/analytics/swagger-ui.html`.

## Conventions

- **Times** are unix seconds; daily data uses UTC midnight. Arrays can be passed to TradingView Lightweight Charts (`series.setData(bars)`, `series.setMarkers(markers)`) or ECharts without conversion.
- **Dates** in parameters are ISO `yyyy-MM-dd`. Without `from`/`to` the range is the 12 months up to the latest stored price. Ranges are limited to 5 years; `from` after `to` is a 400.
- **Listings** are addressed by symbol; add `exchange` (ISO 10383 MIC) only if the symbol trades on several exchanges (otherwise 400). Unknown symbols are 404.
- **Markers** sit on the first trading day on or after the event, the same day 0 the event study uses. Negative sentiment (< −0.15): red `arrowDown` `aboveBar`; positive (> 0.15): green `arrowUp` `belowBar`; otherwise grey `circle`. `severity` is `high` (impact ≥ 0.7), `medium` (≥ 0.4) or `low`. `text` is the first topic plus CAR[−1,+1] when computed.
- **Caching:** `Cache-Control: public, max-age=60` and an `ETag`; a request with a matching `If-None-Match` gets 304.
- **Errors:** `{timestamp, status, error, message, details}` with 400 (bad parameter), 404 (unknown listing / event, or not enough prices yet).
- Price data covers what the market-data plan covers (Twelve Data free plan: US listings). Events on other listings appear in event/sentiment/topic series but have no bars, quotes or impact.

## Endpoints

| Endpoint | Parameters | Returns |
| --- | --- | --- |
| `GET /companies/{symbol}/price` | `exchange`, `from`, `to`, `maxPoints` (50–5000, default 2000) | `{listing, bars: [{time, open, high, low, close, volume}], downsampled}`. Above `maxPoints`, consecutive bars are merged (first open, max high, min low, last close, summed volume) |
| `GET /companies/{symbol}/events` | `exchange`, `from`, `to`, `topics` (comma list, any match), `minImpact` (0–1) | `{listing, markers: [{time, position, shape, color, text, storyId, companyId, title, topics, impactScore, sentiment, articleCount, car1, car5, pValue, severity}]}` |
| `GET /companies/{symbol}/overlay` | as price + events | `{listing, bars, markers, downsampled}`; with downsampling, markers move to the bar bucket that contains them. The main investor chart |
| `GET /companies/{symbol}/sentiment` | `exchange`, `from`, `to`, `bucket` (`day`/`week`) | `[{time, avgSentiment, events, articles}]`; article-weighted mean of the company's event sentiment; weeks start Monday |
| `GET /companies/{symbol}/news-volume` | as sentiment | `[{time, events, articles}]` |
| `GET /events/{storyId}/{companyId}/impact` | | `{storyId, companyId, title, listing, benchmark, eventDay, alpha, beta, ar0, car1, car5, pValue, estimationDays, complete, path: [{offset, time, stockReturn, benchmarkReturn, ar, car}]}`; `path` covers trading days −5..+5 as far as prices exist, `car` cumulated from −5 |
| `GET /topics/volume` | `from`, `to`, `bucket` (default `week`), `topics` | `[{time, topics: {name: count}}]`; an event counts under each of its topics |
| `GET /sector/heatmap` | `window` (`1d`/`7d`/`30d`, default `7d`), `to` (default latest price day) | `{window, from, to, rows: [{listing, returnPct, avgSentiment, events, topEventTitle, topEventImpact}]}`; one row per listing with prices, most-covered first |
| `GET /quotes` | `symbols` (1–50, comma list) | `[{listing, time, last, previousClose, change, changePct}]`; unknown, ambiguous or unpriced symbols are left out |

The provider serves the company graph (a Neo4j traversal): `GET /news-provider/api/companies/{symbol}/graph?days=30&limit=50` → `{nodes: [{id, type (company/story), label, kind, symbol, firstSeen, articleCount}], edges: [{source, target, impactScore, sentiment, car1}]}`. See [PROVIDER_API.md](PROVIDER_API.md#companies).

## Examples

```bash
curl 'http://localhost:8090/analytics/api/charts/companies/JPM/overlay?from=2026-07-01'
curl 'http://localhost:8090/analytics/api/charts/sector/heatmap?window=30d'
curl 'http://localhost:8090/analytics/api/charts/quotes?symbols=JPM,GS,C'
```

## Verification (2026-11-13, real data)

Every endpoint was called for real listings (JPM, GS, C, COIN …): 20–350 ms per call (heatmap of 313 listings 343 ms), 304 on ETag replay. All 59 markers that have an event study sit exactly on the study's day 0 with the same CAR. Automated: `ChartApiIntegrationTests` (8, against the market database through HTTP) and `CompanyGraphIntegrationTests` (3, provider).
