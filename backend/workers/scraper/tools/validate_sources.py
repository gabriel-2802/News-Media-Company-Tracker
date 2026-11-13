"""
Validate candidate news sources before they are seeded into Neo4j.

Reuses the production scraper's building blocks (HttpFetcher, parse_rss,
TrafilaturaExtractor, robots.txt handling) so a source that passes here is
scraped the same way in production.

For every candidate in data/source_candidates.json it checks:
  * feed reachable and parseable (bot UA, then browser UA, then feed
    autodiscovery from the homepage's <link rel="alternate"> tags)
  * freshness   - newest item age, items in the freshness window
  * volume      - items per day (drives the per-source scrape interval)
  * robots.txt  - sample article URLs allowed for our UA
  * extraction  - share of sampled articles yielding >= MIN_BODY_CHARS
  * paywall     - subscribe/login interstitial markers in article HTML
  * relevance   - share of sampled items mentioning banking/finance terms

Verdicts:
  PASS           seeded into data/sources.json + the V2 Neo4j migration
  HEADLINE_ONLY  feed is fine but bodies are paywalled/unextractable; the
                 scraper currently drops body-less articles, so these are
                 reported but not seeded
  FAIL           unusable (see `reasons`)

Run from backend/workers/:
    scraper/venv/bin/python -m scraper.tools.validate_sources            # validate + report
    scraper/venv/bin/python -m scraper.tools.validate_sources --write    # + regenerate seeds
    scraper/venv/bin/python -m scraper.tools.validate_sources --only fed-press,boe-news
"""
from __future__ import annotations

import argparse
import json
import logging
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional
from urllib.parse import urljoin, urlparse

from dotenv import load_dotenv

REPO_ROOT = Path(__file__).resolve().parents[4]
load_dotenv(REPO_ROOT / ".env")

# Imported after load_dotenv: news_scraper reads required env vars at import.
from scraper.news_scraper import (  # noqa: E402
    BROWSER_UA,
    USER_AGENT,
    ExtractionError,
    FetchError,
    HttpFetcher,
    PlaywrightFetcher,
    RSSEntry,
    SmartFetcher,
    TrafilaturaExtractor,
    _build_robots,
    discover_feeds,
    parse_rss,
)

log = logging.getLogger("validate_sources")

CANDIDATES_PATH = REPO_ROOT / "data" / "source_candidates.json"
SOURCES_PATH = REPO_ROOT / "data" / "sources.json"
REPORT_DIR = REPO_ROOT / "data" / "source_validation"
MIGRATION_PATH = REPO_ROOT / "backend" / "neo4j" / "migrations" / "V2__seed_banking_sources.cypher"

SAMPLE_SIZE = 10
MIN_BODY_CHARS = 400
MIN_EXTRACTION_RATE = 0.70
MIN_RELEVANCE_NEWS = 0.40          # general-finance feeds must be mostly on-topic
MIN_RELEVANCE_TRADE = 0.20         # trade press is on-topic by construction; sanity floor only
FRESH_HOURS_DEFAULT = 72
FRESH_HOURS_OFFICIAL = 24 * 14     # regulators / central banks publish less often
PER_REQUEST_DELAY = 1.5            # politeness delay between requests to one host
MAX_PARALLEL_SOURCES = 8

OFFICIAL_CATEGORIES = {"REGULATOR", "CENTRAL_BANK", "FILING"}

PAYWALL_MARKERS = [
    "subscribe to continue", "subscribe to read", "already a subscriber",
    "to continue reading", "sign in to continue", "log in to continue",
    "create a free account to continue", "this article is for subscribers",
    "become a member to read", "unlock this article", "start your free trial",
    "you have reached your limit", "premium content",
]

BANKING_TERMS = re.compile(
    r"\b(bank\w*|lend\w*|loan\w*|credit|deposit\w*|mortgage\w*|fintech|payment\w*|"
    r"fed(eral reserve)?|ecb|central bank|interest rate\w*|rate (cut|hike)\w*|basel|capital|liquidity|"
    r"regulat\w*|fdic|occ|cfpb|sec|fca|pra|eba|stress test\w*|earnings|dividend\w*|"
    r"merger\w*|acquisition\w*|m&a|ipo|bond\w*|treasur\w*|yield\w*|inflation|"
    r"stablecoin\w*|crypto\w*|wealth|asset manag\w*|insur\w*|fraud|aml|sanction\w*|"
    r"jpmorgan|citi\w*|wells fargo|goldman|morgan stanley|hsbc|barclays|lloyds|natwest|"
    r"santander|bnp|deutsche bank|ubs|ing|unicredit|revolut|monzo|klarna|stripe|paypal|visa|mastercard)\b",
    re.IGNORECASE,
)


@dataclass
class Candidate:
    name: str
    baseUrl: str
    rssUrl: str
    category: str
    region: str
    paywalled: bool = False
    language: str = "en"
    notes: Optional[str] = None


@dataclass
class Result:
    name: str
    category: str
    region: str
    verdict: str = "FAIL"
    reasons: list[str] = field(default_factory=list)
    rssUrl: Optional[str] = None
    rssUrlDiscovered: bool = False
    rssBrowserUa: bool = False
    articleBrowserUa: bool = False
    feedItems: int = 0
    newestItemAgeHours: Optional[float] = None
    itemsInFreshWindow: int = 0
    itemsPerDay: Optional[float] = None
    robotsAllowed: Optional[bool] = None
    sampled: int = 0
    extracted: int = 0
    extractionRate: Optional[float] = None
    medianBodyChars: Optional[int] = None
    paywallHits: int = 0
    relevanceRate: Optional[float] = None
    scrapeIntervalMinutes: Optional[int] = None
    sampleTitles: list[str] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Feed handling
# ---------------------------------------------------------------------------

def _try_feed(url: str, fetchers: list[tuple[str, HttpFetcher]]) -> tuple[Optional[list[RSSEntry]], bool, Optional[str]]:
    """Return (entries, used_browser_ua, error)."""
    last_err: Optional[str] = None
    for label, fetcher in fetchers:
        try:
            text = fetcher.fetch(url)
        except FetchError as exc:
            last_err = f"{label}: {exc}"
            continue
        try:
            entries = parse_rss(text)
        except Exception as exc:  # noqa: BLE001 — any parse failure means "not a feed"
            last_err = f"{label}: parse error {exc}"
            continue
        if entries:
            return entries, label == "browser", None
        last_err = f"{label}: 0 entries"
    return None, False, last_err


def _discover_feeds(base_url: str, fetchers: list[tuple[str, HttpFetcher]]) -> list[str]:
    # Same discovery the scraper uses: homepage <link rel=alternate>, then
    # conventional paths (/feed, /rss.xml, ...).
    return discover_feeds(base_url, fetchers[0][1])


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

def _aware(dt: Optional[datetime]) -> Optional[datetime]:
    if dt is None:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def _interval_for(items_per_day: Optional[float]) -> int:
    if items_per_day is None:
        return 60
    if items_per_day >= 24:
        return 15
    if items_per_day >= 6:
        return 30
    return 60


def validate(c: Candidate) -> Result:
    r = Result(name=c.name, category=c.category, region=c.region)
    bot = HttpFetcher(USER_AGENT)
    browser = bot.with_user_agent(BROWSER_UA)
    fetchers = [("bot", bot), ("browser", browser)]
    now = datetime.now(timezone.utc)

    # 1. Feed ----------------------------------------------------------------
    entries, used_browser, err = _try_feed(c.rssUrl, fetchers)
    feed_url = c.rssUrl
    if entries is None:
        time.sleep(PER_REQUEST_DELAY)
        for discovered in _discover_feeds(c.baseUrl, fetchers)[:6]:
            time.sleep(PER_REQUEST_DELAY)
            entries, used_browser, err2 = _try_feed(discovered, fetchers)
            if entries:
                feed_url = discovered
                r.rssUrlDiscovered = True
                break
            err = err2 or err
    if not entries:
        r.reasons.append(f"feed unusable ({err})")
        return r

    r.rssUrl = feed_url
    r.rssBrowserUa = used_browser
    r.feedItems = len(entries)

    # 2. Freshness / volume --------------------------------------------------
    dates = sorted((d for d in (_aware(e.published_at) for e in entries) if d), reverse=True)
    fresh_hours = FRESH_HOURS_OFFICIAL if c.category in OFFICIAL_CATEGORIES else FRESH_HOURS_DEFAULT
    if dates:
        r.newestItemAgeHours = round((now - dates[0]).total_seconds() / 3600, 1)
        r.itemsInFreshWindow = sum(1 for d in dates if now - d <= timedelta(hours=fresh_hours))
        span_days = max((dates[0] - dates[-1]).total_seconds() / 86400, 1 / 24)
        r.itemsPerDay = round(len(dates) / span_days, 1) if len(dates) > 1 else None
    if r.itemsInFreshWindow == 0:
        r.reasons.append(f"stale: no items in last {fresh_hours}h (newest {r.newestItemAgeHours}h old)"
                         if dates else "no publish dates in feed")
    r.scrapeIntervalMinutes = _interval_for(r.itemsPerDay)

    # 3. robots.txt ----------------------------------------------------------
    sample = entries[:SAMPLE_SIZE]
    r.sampleTitles = [e.title for e in sample[:3]]
    robots = _build_robots(c.baseUrl)
    allowed = [e for e in sample if robots.can_fetch(USER_AGENT, e.url)]
    r.robotsAllowed = len(allowed) == len(sample)
    if not allowed:
        r.reasons.append("robots.txt disallows article URLs for our UA")

    # 4. Extraction / paywall / relevance ------------------------------------
    # Same article fetch chain as the production worker (static, then
    # Playwright for JS-rendered pages), so verdicts match real scraping.
    extractor = TrafilaturaExtractor()
    article_fetcher = SmartFetcher(http=bot, browser=PlaywrightFetcher())
    bodies: list[int] = []
    relevant = 0
    for e in allowed:
        time.sleep(PER_REQUEST_DELAY)
        r.sampled += 1
        body = ""
        try:
            html = article_fetcher.fetch(e.url)
            if any(m in html.lower() for m in PAYWALL_MARKERS):
                r.paywallHits += 1
            body = extractor.extract(html, e.url)
        except (FetchError, ExtractionError):
            body = ""
        if len(body) >= MIN_BODY_CHARS:
            r.extracted += 1
            bodies.append(len(body))
        if BANKING_TERMS.search(f"{e.title} {body[:600]}"):
            relevant += 1

    if r.sampled:
        r.extractionRate = round(r.extracted / r.sampled, 2)
        r.relevanceRate = round(relevant / r.sampled, 2)
    if bodies:
        r.medianBodyChars = sorted(bodies)[len(bodies) // 2]

    if c.category not in OFFICIAL_CATEGORIES and r.relevanceRate is not None:
        floor = MIN_RELEVANCE_NEWS if c.category == "NEWS" else MIN_RELEVANCE_TRADE
        if r.relevanceRate < floor:
            r.reasons.append(f"low banking relevance {r.relevanceRate:.0%} < {floor:.0%}")

    extraction_ok = r.extractionRate is not None and r.extractionRate >= MIN_EXTRACTION_RATE

    # 5. Verdict -------------------------------------------------------------
    if r.reasons:
        r.verdict = "FAIL"
    elif extraction_ok:
        r.verdict = "PASS"
    else:
        r.verdict = "HEADLINE_ONLY"
        r.reasons.append(
            f"body extraction {r.extractionRate:.0%} < {MIN_EXTRACTION_RATE:.0%}"
            + (f", paywall markers on {r.paywallHits} pages" if r.paywallHits else "")
        )
    return _apply_paywall_rule(r, c)


def _apply_paywall_rule(r: Result, c: Candidate) -> Result:
    """A source known to be paywalled that still shows paywall markers is
    yielding teaser + subscribe-box text, not articles: never seed it as PASS."""
    if r.verdict == "PASS" and c.paywalled and r.paywallHits > 0:
        r.verdict = "HEADLINE_ONLY"
        r.reasons.append(f"paywalled source: markers on {r.paywallHits}/{r.sampled} pages, "
                         f"extracted text is likely teaser only")
    return r


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def _write_report(results: list[Result], report_dir: Optional[Path] = None) -> None:
    report_dir = report_dir or REPORT_DIR
    report_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%MZ")
    (report_dir / "report.json").write_text(
        json.dumps({"generatedAt": stamp, "results": [asdict(r) for r in results]}, indent=2) + "\n"
    )

    def pct(v: Optional[float]) -> str:
        return "–" if v is None else f"{v:.0%}"

    lines = [
        "# Source validation report",
        "",
        f"Generated {stamp} by `scraper/tools/validate_sources.py` "
        f"(sample {SAMPLE_SIZE} items, body ≥ {MIN_BODY_CHARS} chars, extraction ≥ {MIN_EXTRACTION_RATE:.0%}).",
        "",
        f"**{sum(r.verdict == 'PASS' for r in results)} PASS · "
        f"{sum(r.verdict == 'HEADLINE_ONLY' for r in results)} HEADLINE_ONLY · "
        f"{sum(r.verdict == 'FAIL' for r in results)} FAIL** out of {len(results)} candidates.",
        "",
        "| Source | Cat. | Region | Verdict | Items/day | Newest (h) | Extract | Relevance | Interval | Notes |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    order = {"PASS": 0, "HEADLINE_ONLY": 1, "FAIL": 2}
    for r in sorted(results, key=lambda x: (order[x.verdict], x.category, x.name)):
        notes = "; ".join(r.reasons)
        if r.rssUrlDiscovered:
            notes = (notes + "; " if notes else "") + f"feed autodiscovered: {r.rssUrl}"
        if r.rssBrowserUa:
            notes = (notes + "; " if notes else "") + "feed needs browser UA"
        lines.append(
            f"| {r.name} | {r.category} | {r.region} | {r.verdict} | {r.itemsPerDay if r.itemsPerDay is not None else '–'} "
            f"| {r.newestItemAgeHours if r.newestItemAgeHours is not None else '–'} | {pct(r.extractionRate)} "
            f"| {pct(r.relevanceRate)} | {r.scrapeIntervalMinutes or '–'} min | {notes} |"
        )
    (report_dir / "report.md").write_text("\n".join(lines) + "\n")


def _cypher_str(v: Optional[str]) -> str:
    if v is None:
        return "null"
    return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _write_seeds(results: list[Result], candidates: dict[str, Candidate]) -> None:
    passing = [r for r in results if r.verdict == "PASS"]
    passing.sort(key=lambda r: (r.category, r.region, r.name))
    sources = []
    for r in passing:
        c = candidates[r.name]
        sources.append({
            "name": c.name,
            "baseUrl": c.baseUrl,
            "rssUrl": r.rssUrl,
            "category": c.category,
            "region": c.region,
            "paywalled": c.paywalled,
            "language": c.language,
            "scrapeIntervalMinutes": r.scrapeIntervalMinutes,
            "rssBrowserUa": r.rssBrowserUa,
        })
    SOURCES_PATH.write_text(json.dumps(sources, indent=2) + "\n")

    out = [
        "// GENERATED by backend/workers/scraper/tools/validate_sources.py --write",
        "// from data/source_candidates.json; do not edit by hand. Only sources",
        "// with verdict PASS in data/source_validation/report.md are seeded.",
        "//",
        "// Every NewsSource gets an `id` here: Spring Data Neo4j uses the",
        "// generated id to tell new from persisted entities (see NewsSource.java).",
        "// MERGE on name keeps this idempotent; ON CREATE SET assigns id once.",
        "",
    ]
    for s in sources:
        out.append(
            f"MERGE (n:NewsSource {{name: {_cypher_str(s['name'])}}})\n"
            f"  ON CREATE SET n.id = randomUUID(), n.failureCount = 0, n.isDisabled = false\n"
            f"  SET n.baseUrl = {_cypher_str(s['baseUrl'])}, n.rssUrl = {_cypher_str(s['rssUrl'])},\n"
            f"      n.category = {_cypher_str(s['category'])}, n.region = {_cypher_str(s['region'])},\n"
            f"      n.paywalled = {str(s['paywalled']).lower()}, n.language = {_cypher_str(s['language'])},\n"
            f"      n.scrapeIntervalMinutes = {s['scrapeIntervalMinutes']};"
        )
    MIGRATION_PATH.write_text("\n".join(out) + "\n")

    browser_ua = sorted(s["name"] for s in sources if s["rssBrowserUa"])
    print(f"\nWrote {len(sources)} sources to {SOURCES_PATH.relative_to(REPO_ROOT)} "
          f"and {MIGRATION_PATH.relative_to(REPO_ROOT)}")
    print(f"RSS_BROWSER_UA_SOURCES={','.join(browser_ua)}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--write", action="store_true", help="regenerate data/sources.json and the V2 seed migration")
    ap.add_argument("--only", help="comma-separated candidate names to validate")
    ap.add_argument("--reuse-report", action="store_true",
                    help="skip network checks: re-apply verdict rules to data/source_validation/report.json "
                         "(after editing candidate metadata such as `paywalled`)")
    ap.add_argument("--seeded", action="store_true",
                    help="health check: validate only the sources currently seeded (data/sources.json)")
    ap.add_argument("--max-fail-ratio", type=float,
                    help="exit 1 when more than this share (0-1) of the validated sources is not PASS")
    ap.add_argument("--report-dir", type=Path,
                    help="write report.md/json here instead of data/source_validation/ (e.g. for nightly runs)")
    args = ap.parse_args()

    logging.getLogger().setLevel(logging.WARNING)
    raw = json.loads(CANDIDATES_PATH.read_text())
    candidates = {c["name"]: Candidate(**c) for c in raw}

    if args.reuse_report:
        saved = json.loads((REPORT_DIR / "report.json").read_text())["results"]
        results = [_apply_paywall_rule(Result(**x), candidates[x["name"]])
                   for x in saved if x["name"] in candidates]
        _write_report(results)
        print(f"Report re-applied: {(REPORT_DIR / 'report.md').relative_to(REPO_ROOT)}")
        if args.write:
            _write_seeds(results, candidates)
        return 0
    selected = list(candidates.values())
    if args.only:
        wanted = {n.strip() for n in args.only.split(",")}
        selected = [c for c in selected if c.name in wanted]
    if args.seeded:
        seeded = {s["name"] for s in json.loads(SOURCES_PATH.read_text())}
        selected = [c for c in selected if c.name in seeded]
    if args.write and (args.only or args.seeded):
        ap.error("--write needs the full candidate list (drop --only/--seeded)")

    def run(c: Candidate) -> Result:
        try:
            res = validate(c)
        except Exception as exc:  # noqa: BLE001 — one bad source must not kill the run
            res = Result(name=c.name, category=c.category, region=c.region,
                         reasons=[f"crashed: {type(exc).__name__}: {exc}"])
        print(f"{res.verdict:<13} {c.name:<22} {'; '.join(res.reasons)}", flush=True)
        return res

    # Group by host so two candidates on the same host never run in parallel.
    by_host: dict[str, list[Candidate]] = {}
    for c in selected:
        by_host.setdefault(urlparse(c.baseUrl).netloc, []).append(c)

    with ThreadPoolExecutor(max_workers=MAX_PARALLEL_SOURCES) as pool:
        nested = list(pool.map(lambda group: [run(c) for c in group], by_host.values()))
    results = [r for group in nested for r in group]

    report_dir = args.report_dir.resolve() if args.report_dir else REPORT_DIR
    _write_report(results, report_dir)
    print(f"\nReport: {report_dir / 'report.md'}")
    if args.write:
        _write_seeds(results, candidates)
    return health_exit_code(results, args.max_fail_ratio)


def health_exit_code(results: list[Result], max_fail_ratio: Optional[float]) -> int:
    """1 when the share of non-PASS sources exceeds max_fail_ratio (None: never fail)."""
    if max_fail_ratio is None or not results:
        return 0
    failing = [r.name for r in results if r.verdict != "PASS"]
    ratio = len(failing) / len(results)
    print(f"Source health: {len(failing)}/{len(results)} not PASS ({ratio:.0%}, limit {max_fail_ratio:.0%})"
          + (f": {', '.join(sorted(failing))}" if failing else ""))
    return 1 if ratio > max_fail_ratio else 0


if __name__ == "__main__":
    sys.exit(main())
