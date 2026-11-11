# Source validation report

Generated 2026-11-11T16:18Z by `scraper/tools/validate_sources.py` (sample 10 items, body ≥ 400 chars, extraction ≥ 70%).

**27 PASS · 4 HEADLINE_ONLY · 10 FAIL** out of 41 candidates.

| Source | Cat. | Region | Verdict | Items/day | Newest (h) | Extract | Relevance | Interval | Notes |
|---|---|---|---|---|---|---|---|---|---|
| bis-press | CENTRAL_BANK | GLOBAL | PASS | – | 40.3 | 100% | 100% | 60 min |  |
| boe-news | CENTRAL_BANK | UK | PASS | 0.4 | 28.3 | 80% | 80% | 60 min |  |
| ecb-press | CENTRAL_BANK | EU | PASS | 1.2 | 23.5 | 100% | 100% | 60 min |  |
| fed-press | CENTRAL_BANK | US | PASS | 0.4 | 43.2 | 100% | 100% | 60 min |  |
| cnbc-finance | NEWS | US | PASS | 2.2 | 1.8 | 100% | 80% | 60 min |  |
| coindesk | NEWS | GLOBAL | PASS | 17.1 | 0.2 | 70% | 70% | 30 min |  |
| dw-business | NEWS | EU | PASS | 0.7 | 1.1 | 100% | 40% | 60 min |  |
| guardian-banking | NEWS | UK | PASS | 0.9 | 14.8 | 100% | 100% | 60 min |  |
| boe-pra | REGULATOR | UK | PASS | 0.2 | 30.3 | 100% | 100% | 60 min |  |
| eba-news | REGULATOR | EU | PASS | 0.4 | 49.1 | 100% | 100% | 60 min |  |
| ecb-supervision | REGULATOR | EU | PASS | 0.4 | 136.3 | 100% | 100% | 60 min |  |
| esma-news | REGULATOR | EU | PASS | 0.4 | 55.4 | 100% | 100% | 60 min |  |
| fca-news | REGULATOR | UK | PASS | 1.1 | 23.1 | 100% | 90% | 60 min |  |
| fdic-press | REGULATOR | US | PASS | 0.3 | 141.2 | 100% | 100% | 60 min |  |
| fed-enforcement | REGULATOR | US | PASS | 0.1 | 144.2 | 100% | 100% | 60 min |  |
| occ-news | REGULATOR | US | PASS | 0.4 | 144.3 | 100% | 100% | 60 min |  |
| sec-press | REGULATOR | US | PASS | 0.5 | 24.0 | 100% | 100% | 60 min |  |
| aba-banking-journal | TRADE_PRESS | US | PASS | 21.5 | 19.0 | 100% | 70% | 30 min |  |
| american-banker | TRADE_PRESS | US | PASS | 12.6 | 1.3 | 100% | 90% | 30 min | feed autodiscovered: https://www.americanbanker.com/rss |
| banking-dive | TRADE_PRESS | US | PASS | 2.5 | 22.8 | 100% | 100% | 60 min |  |
| finance-magnates | TRADE_PRESS | GLOBAL | PASS | 21.3 | 0.1 | 100% | 90% | 30 min |  |
| finextra | TRADE_PRESS | GLOBAL | PASS | 0.6 | -1822.8 | 100% | 100% | 60 min |  |
| fintech-futures | TRADE_PRESS | GLOBAL | PASS | 8.1 | 1.5 | 90% | 80% | 30 min | feed autodiscovered: https://www.fintechfutures.com/rss.xml |
| fintech-global | TRADE_PRESS | GLOBAL | PASS | 69.8 | 1.8 | 100% | 80% | 15 min |  |
| payments-dive | TRADE_PRESS | US | PASS | 1.9 | 18.6 | 100% | 100% | 60 min |  |
| pymnts | TRADE_PRESS | US | PASS | 15.3 | 0.6 | 100% | 50% | 30 min |  |
| tearsheet | TRADE_PRESS | US | PASS | 1.2 | 27.2 | 100% | 90% | 60 min |  |
| ft-financials | NEWS | GLOBAL | HEADLINE_ONLY | 5.3 | 10.2 | 70% | 40% | 60 min | paywalled source: markers on 6/10 pages, extracted text is likely teaser only |
| investing-com | NEWS | GLOBAL | HEADLINE_ONLY | 240.0 | 0.2 | 0% | 50% | 15 min | body extraction 0% < 70% |
| seeking-alpha | NEWS | US | HEADLINE_ONLY | 168.0 | 0.1 | 29% | 86% | 15 min | body extraction 29% < 70% |
| sifted | TRADE_PRESS | EU | HEADLINE_ONLY | 4.6 | 0.0 | 100% | 20% | 60 min | paywalled source: markers on 10/10 pages, extracted text is likely teaser only |
| bbc-business | NEWS | UK | FAIL | 0.4 | 0.0 | 100% | 30% | 60 min | low banking relevance 30% < 40% |
| cityam | NEWS | UK | FAIL | 75.8 | 0.8 | – | – | 15 min | robots.txt disallows article URLs for our UA |
| marketwatch | NEWS | US | FAIL | 109.9 | 0.1 | – | – | 15 min | robots.txt disallows article URLs for our UA |
| this-is-money | NEWS | UK | FAIL | 14.8 | 1.4 | 90% | 10% | 30 min | low banking relevance 10% < 40% |
| yahoo-finance | NEWS | US | FAIL | – | – | – | – | – min | feed unusable (browser: Permanent HTTP error for https://finance.yahoo.com/atom.xml: 404 Client Error: Not Found for url: https://finance.yahoo.com/atom.xml) |
| cfpb-newsroom | REGULATOR | US | FAIL | 0.0 | 684.3 | 90% | 90% | 60 min | stale: no items in last 336h (newest 684.3h old) |
| sec-litigation | REGULATOR | US | FAIL | – | – | – | – | – min | feed unusable (browser: Failed https://www.sec.gov/atom.xml after 3 attempts: 403 Client Error: Forbidden for url: https://www.sec.gov/atom.xml) |
| bank-automation-news | TRADE_PRESS | US | FAIL | – | – | – | – | – min | feed unusable (browser: Failed https://bankautomationnews.com/atom.xml after 3 attempts: 403 Client Error: Forbidden for url: https://bankautomationnews.com/atom.xml) |
| financial-brand | TRADE_PRESS | US | FAIL | – | – | – | – | – min | feed unusable (browser: Failed https://thefinancialbrand.com/atom.xml after 3 attempts: 403 Client Error: Forbidden for url: https://thefinancialbrand.com/atom.xml) |
| the-banker | TRADE_PRESS | GLOBAL | FAIL | – | – | – | – | – min | feed unusable (browser: Failed https://www.thebanker.com/atom.xml after 3 attempts: 403 Client Error: Forbidden for url: https://www.thebanker.com/atom.xml) |
