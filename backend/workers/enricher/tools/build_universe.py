"""
Build the company universe the enricher links articles to.

Sources
  1. SEC company_tickers_exchange.json + per-company submissions: every
     NYSE/Nasdaq-listed company with a bank SIC code
     (6021 national, 6022 state, 6029 other commercial, 6035/6036 savings).
  2. data/company_universe_curated.json: large US banks with clean names and
     aliases, US fintechs, UK/EU banks and fintechs, regulators and central
     banks. Curated US tickers are verified against the SEC file.
  3. GLEIF: LEI looked up by exact legal name for curated entities. No
     match leaves lei empty; nothing is guessed.

Writes data/company_universe.json, data/company_universe_report.md and the
generated migration backend/neo4j/migrations/V5__seed_company_universe.cypher.

Run from backend/workers/ (needs SEC fair-access User-Agent from .env):
    scraper/venv/bin/python -m enricher.tools.build_universe
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Optional

import requests
from dotenv import load_dotenv

REPO_ROOT = Path(__file__).resolve().parents[4]
load_dotenv(REPO_ROOT / ".env")

CURATED = REPO_ROOT / "data" / "company_universe_curated.json"
OUT_JSON = REPO_ROOT / "data" / "company_universe.json"
OUT_REPORT = REPO_ROOT / "data" / "company_universe_report.md"
MIGRATION = REPO_ROOT / "backend" / "neo4j" / "migrations" / "V5__seed_company_universe.cypher"

SEC_TICKERS = "https://www.sec.gov/files/company_tickers_exchange.json"
SEC_SUBMISSIONS = "https://data.sec.gov/submissions/CIK{cik:010d}.json"
GLEIF = "https://api.gleif.org/api/v1/lei-records"

BANK_SICS = {"6021", "6022", "6029", "6035", "6036"}
EXCHANGE_MIC = {"NYSE": "XNYS", "Nasdaq": "XNAS"}
BANKISH = re.compile(r"banc|bank|financial|savings|trust|first|national|federal|community|capital", re.I)
SEC_DELAY = 0.15        # SEC fair access: <= 10 requests/second
GLEIF_DELAY = 1.1       # GLEIF: 60 requests/minute

LEGAL_SUFFIX = re.compile(
    r"[,\s]+(inc\.?|incorporated|corp\.?|corporation|co\.?|company|holdings?|group|plc|ltd\.?|limited|"
    r"n\.a\.|national association|& co\.?|/\w+/?)$",
    re.IGNORECASE,
)
KEEP_UPPER = {"USA", "US", "NA", "N.A.", "BNY", "FNB", "BOK", "PNC", "M&T", "MVB", "OFG", "CNB", "QNB", "BCB",
              "ESSA", "HMN", "BRT", "TFS", "WSFS", "UMB", "BOKF", "FB", "NBT", "CVB", "ACNB", "CCFNB"}


def http() -> requests.Session:
    ua = os.environ.get("USER_AGENT")
    if not ua:
        sys.exit("USER_AGENT missing from .env (SEC requires an identifying User-Agent)")
    s = requests.Session()
    s.headers.update({"User-Agent": ua, "Accept-Encoding": "gzip, deflate"})
    return s


def nice_name(sec_name: str) -> str:
    name = re.sub(r"\s*/\w+/?$", "", sec_name.strip())          # "WELLS FARGO & COMPANY/MN"
    words = []
    for w in name.split():
        words.append(w if w.upper() in KEEP_UPPER or any(c.isdigit() for c in w) else w.capitalize())
    return " ".join(words).replace(" And ", " and ").replace(" Of ", " of ").replace(" The ", " the ")


def short_alias(name: str) -> Optional[str]:
    prev, cur = None, name
    while prev != cur:
        prev, cur = cur, LEGAL_SUFFIX.sub("", cur).strip(" ,&")
    return cur if cur != name and len(cur) >= 4 else None


def sec_banks(s: requests.Session) -> tuple[list[dict], dict[str, dict]]:
    data = s.get(SEC_TICKERS, timeout=30).json()
    rows = [dict(zip(data["fields"], r)) for r in data["data"]]
    by_ticker = {r["ticker"]: r for r in rows}

    first_ticker: dict[int, dict] = {}
    for r in rows:                                   # file is ordered by size; keep each CIK's first common ticker
        if r["exchange"] in EXCHANGE_MIC and "-" not in r["ticker"] and r["cik"] not in first_ticker:
            first_ticker[r["cik"]] = r

    candidates = [r for r in first_ticker.values() if BANKISH.search(r["name"])]
    print(f"SEC: {len(rows)} tickers, {len(candidates)} bank-like NYSE/Nasdaq names to check", flush=True)

    banks = []
    for i, r in enumerate(candidates, 1):
        time.sleep(SEC_DELAY)
        try:
            sub = s.get(SEC_SUBMISSIONS.format(cik=r["cik"]), timeout=20).json()
        except (requests.RequestException, ValueError) as exc:
            print(f"  skip CIK {r['cik']}: {exc}", flush=True)
            continue
        if str(sub.get("sic")) in BANK_SICS:
            banks.append({
                "name": nice_name(r["name"]), "kind": "BANK", "country": "US", "lei": None,
                "aliases": [], "cik": r["cik"], "sic": str(sub["sic"]),
                "tickers": [{"symbol": r["ticker"], "exchange": EXCHANGE_MIC[r["exchange"]],
                             "currency": "USD", "primary": True}],
            })
        if i % 100 == 0:
            print(f"  checked {i}/{len(candidates)}, banks so far {len(banks)}", flush=True)
    return banks, by_ticker


def gleif_lei(s: requests.Session, legal_name: str, country: str) -> Optional[str]:
    time.sleep(GLEIF_DELAY)
    try:
        resp = s.get(GLEIF, params={"filter[entity.legalName]": legal_name, "page[size]": 10}, timeout=20)
        records = resp.json().get("data", [])
    except (requests.RequestException, ValueError):
        return None
    # GLEIF's legalName filter is not exact (it returns near-misses such as
    # subsidiaries, branches and funds), so require an exact normalised name
    # match, an ACTIVE entity and a GENERAL (not FUND/BRANCH) category.
    want = _norm(legal_name)
    exact = [
        r for r in records
        if _norm(r["attributes"]["entity"]["legalName"]["name"]) == want
        and r["attributes"]["entity"].get("status") == "ACTIVE"
        and r["attributes"]["entity"].get("category") in (None, "GENERAL")
    ]
    same_country = [r for r in exact if r["attributes"]["entity"]["legalAddress"].get("country") == country]
    pick = same_country or exact
    return pick[0]["attributes"]["lei"] if len(pick) == 1 else None


def _norm(name: str) -> str:
    import unicodedata
    ascii_name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", ascii_name.lower()).strip()


def cypher_str(v: Optional[str]) -> str:
    return "null" if v is None else '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'


def write_migration(entities: list[dict]) -> None:
    out = [
        "// GENERATED by backend/workers/enricher/tools/build_universe.py; do not edit by hand.",
        "// Sources: SEC (US banks by SIC code), data/company_universe_curated.json, GLEIF LEIs.",
        "// MERGE on Company.name / Ticker (symbol, exchange) keeps this idempotent;",
        "// ON CREATE SET assigns the app-level UUID id once.",
        "",
    ]
    for e in entities:
        out.append(
            f"MERGE (c:Company {{name: {cypher_str(e['name'])}}})\n"
            f"  ON CREATE SET c.id = randomUUID()\n"
            f"  SET c.kind = {cypher_str(e['kind'])}, c.country = {cypher_str(e['country'])}, "
            f"c.lei = {cypher_str(e.get('lei'))},\n"
            f"      c.aliases = [{', '.join(cypher_str(a) for a in e['aliases'])}];"
        )
        for t in e["tickers"]:
            out.append(
                f"MERGE (t:Ticker {{symbol: {cypher_str(t['symbol'])}, exchange: {cypher_str(t['exchange'])}}})\n"
                f"  ON CREATE SET t.id = randomUUID()\n"
                f"  SET t.currency = {cypher_str(t['currency'])}\n"
                f"WITH t MATCH (c:Company {{name: {cypher_str(e['name'])}}})\n"
                f"MERGE (c)-[l:LISTED_AS]->(t) SET l.primary = {str(bool(t['primary'])).lower()};"
            )
    MIGRATION.write_text("\n".join(out) + "\n")


def main() -> int:
    s = http()
    curated = json.loads(CURATED.read_text())["entities"]
    banks, by_ticker = sec_banks(s)
    problems: list[str] = []

    # Verify curated US tickers and remember their CIKs so SEC duplicates drop out.
    curated_ciks: set[int] = set()
    for e in curated:
        for t in e["tickers"]:
            if t["exchange"] not in ("XNYS", "XNAS"):
                continue
            row = by_ticker.get(t["symbol"])
            if row is None:
                problems.append(f"{e['name']}: ticker {t['symbol']} not in SEC file")
                continue
            curated_ciks.add(row["cik"])
            if EXCHANGE_MIC.get(row["exchange"]) != t["exchange"]:
                problems.append(f"{e['name']}: {t['symbol']} SEC exchange {row['exchange']} != curated {t['exchange']}")

    # SEC filers named after a curated company are its subsidiaries or note
    # issuers (e.g. "Barclays Bank Plc" files for iPath ETNs such as DJP),
    # not separate listed banks.
    curated_prefixes = tuple(e["name"].lower() for e in curated if len(e["name"]) >= 4)
    issuers = [b for b in banks if b["cik"] not in curated_ciks and b["name"].lower().startswith(curated_prefixes)]
    for b in issuers:
        problems.append(f"skipped {b['name']} ({b['tickers'][0]['symbol']}): subsidiary/issuer of a curated company")
    sec_only = [b for b in banks if b["cik"] not in curated_ciks and b not in issuers]
    print(f"GLEIF lookups for {sum(1 for e in curated if e.get('legalName'))} curated entities...", flush=True)
    entities = []
    for e in curated:
        lei = gleif_lei(s, e["legalName"], e["country"]) if e.get("legalName") else None
        if e.get("legalName") and lei is None:
            problems.append(f"{e['name']}: no unique ACTIVE GLEIF match for legal name {e['legalName']!r}")
        entities.append({k: v for k, v in e.items() if k != "legalName"} | {"lei": lei})
    for b in sec_only:
        alias = short_alias(b["name"])
        b["aliases"] = [alias] if alias else []
        entities.append({k: v for k, v in b.items() if k not in ("cik", "sic")})

    # Different companies can share a name (First Bancorp in PR and NC); the
    # migration MERGEs on name, so disambiguate with the primary ticker.
    names = [e["name"] for e in entities]
    dupes = sorted({n for n in names if names.count(n) > 1})
    for e in entities:
        if e["name"] in dupes and e["tickers"]:
            e["aliases"] = sorted(set(e["aliases"]) | {e["name"]})
            e["name"] = f"{e['name']} ({e['tickers'][0]['symbol']})"
    if dupes:
        problems.append(f"same-name companies disambiguated with their ticker: {dupes}")

    OUT_JSON.write_text(json.dumps(entities, indent=1, ensure_ascii=False) + "\n")
    write_migration(entities)

    kinds: dict[str, int] = {}
    for e in entities:
        kinds[e["kind"]] = kinds.get(e["kind"], 0) + 1
    report = [
        "# Company universe build report", "",
        f"{len(entities)} entities: " + ", ".join(f"{k} {v}" for k, v in sorted(kinds.items())),
        f"SEC bank-SIC companies found: {len(banks)} (of which {len(banks) - len(sec_only)} also curated)",
        f"Curated entities: {len(curated)}, with LEI: {sum(1 for e in entities if e.get('lei'))}", "",
        "## Problems to review", "",
    ] + [f"- {p}" for p in problems or ["none"]]
    OUT_REPORT.write_text("\n".join(report) + "\n")
    print("\n".join(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
