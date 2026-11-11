"""
Banking topic taxonomy and the mappings from public datasets onto it.

Public data only (decision recorded in documentation/FINTECH_TRANSFORMATION_PLAN.md):
  * zeroshot/twitter-financial-news-topic (TFNT): ~21k finance headlines/tweets,
    20 single-label classes. Integer labels; names below are from the dataset
    card and were checked against sample texts.
  * Reuters-21578 ModApte split (via NLTK): ~10.8k newswire stories, natively
    multi-label.

Known gap: no public dataset labels credit risk, liquidity/capital, bank runs,
crypto or payments separately, so those fold into the nearest bucket.
"""
from __future__ import annotations

LABELS: list[str] = [
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
    "other",
]

LABEL2ID: dict[str, int] = {name: i for i, name in enumerate(LABELS)}

# TFNT integer label -> (dataset-card name, our label)
TFNT_LABELS: dict[int, tuple[str, str]] = {
    0: ("Analyst Update", "analyst_ratings"),
    1: ("Fed | Central Banks", "monetary_policy_rates"),
    2: ("Company | Product News", "company_product_news"),
    3: ("Treasuries | Corporate Debt", "debt_capital_markets"),
    4: ("Dividend", "dividends_buybacks"),
    5: ("Earnings", "earnings_results"),
    6: ("Energy | Oil", "other"),
    7: ("Financials", "company_product_news"),
    8: ("Currencies", "fx_currencies"),
    9: ("General News | Opinion", "other"),
    10: ("Gold | Metals | Materials", "other"),
    11: ("IPO", "debt_capital_markets"),
    12: ("Legal | Regulation", "regulation_legal_enforcement"),
    13: ("M&A | Investments", "m_and_a_investments"),
    14: ("Macro", "macro_economy"),
    15: ("Markets", "markets_stock_moves"),
    16: ("Politics", "other"),
    17: ("Personnel Change", "leadership_personnel"),
    18: ("Stock Commentary", "markets_stock_moves"),
    19: ("Stock Movement", "markets_stock_moves"),
}

# Reuters-21578 category -> our label. Unlisted categories (grain, crude, ...)
# count as "other" when a document has none of the mapped ones.
REUTERS_LABELS: dict[str, str] = {
    "earn": "earnings_results",
    "acq": "m_and_a_investments",
    "interest": "monetary_policy_rates",
    "money-supply": "monetary_policy_rates",
    "money-fx": "fx_currencies",
    "dlr": "fx_currencies",
    "yen": "fx_currencies",
    "dmk": "fx_currencies",
    "trade": "macro_economy",
    "gnp": "macro_economy",
    "cpi": "macro_economy",
    "jobs": "macro_economy",
    "ipi": "macro_economy",
    "bop": "macro_economy",
    "retail": "macro_economy",
    "reserves": "macro_economy",
    "income": "macro_economy",
    "instal-debt": "macro_economy",
    "housing": "macro_economy",
}
