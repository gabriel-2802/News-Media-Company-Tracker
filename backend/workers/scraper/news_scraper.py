"""
News scraper — multi-source, RSS-first metadata, Playwright fallback for JS sites.

Architecture:
  BaseFetcher  → HttpFetcher | PlaywrightFetcher | SmartFetcher
  BaseExtractor → TrafilaturaExtractor
  BaseStorage  → JsonlStorage
  Scraper      → orchestrates one source end-to-end

Robustness notes:
  * Storage deduplicates across runs (loads existing URLs on init).
  * robots.txt is fetched with a timeout and fails *open* with a warning,
    rather than silently disallowing an entire source.
  * One failing source never aborts the whole batch.
  * Playwright runs in a fresh subprocess per fetch (see PlaywrightFetcher)
    rather than a reused in-process browser — required so its sync API
    never shares a process with pika's asyncio-based transport.
  * HTTP retries honour Retry-After (429) and robots Crawl-delay is respected.
"""
from __future__ import annotations

import json
import logging
import os
import re
import subprocess
import sys
import time
import urllib.robotparser
from abc import ABC, abstractmethod
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit, urlunsplit

import feedparser
import requests
import trafilatura
from bs4 import BeautifulSoup
from dateutil import parser as dateutil_parser
from dateutil.tz import tzoffset

from env_config import require_env, require_float, require_int
from log_config import configure_logging

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

USER_AGENT = require_env("USER_AGENT")
BROWSER_UA = require_env("BROWSER_USER_AGENT")
REQUEST_TIMEOUT: tuple[int, int] = (require_int("REQUEST_TIMEOUT_CONNECT"), require_int("REQUEST_TIMEOUT_READ"))
RATE_LIMIT_SECONDS = require_float("RATE_LIMIT_SECONDS")
MAX_RETRIES = require_int("MAX_RETRIES")
RETRY_BACKOFF = require_float("RETRY_BACKOFF")
MAX_RETRY_SLEEP = require_float("MAX_RETRY_SLEEP")          # cap so a hostile Retry-After can't stall the run
JS_LOAD_WAIT_MS = require_int("JS_LOAD_WAIT_MS")
JS_MIN_CHARS = require_int("JS_MIN_CHARS")
OUTPUT_PATH = require_env("SCRAPER_OUTPUT_PATH")

configure_logging()
log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class SourceConfig:
    name: str
    base_url: str
    rss_url: str
    rss_browser_ua: bool = False


SOURCES: list[SourceConfig] = [
    SourceConfig(name="bbc",         base_url="https://www.bbc.co.uk",         rss_url="https://feeds.bbci.co.uk/news/world/rss.xml"),
    SourceConfig(name="guardian",    base_url="https://www.theguardian.com",    rss_url="https://www.theguardian.com/world/rss"),
    SourceConfig(name="aljazeera",   base_url="https://www.aljazeera.com",      rss_url="https://www.aljazeera.com/xml/rss/all.xml"),
    SourceConfig(name="npr",         base_url="https://www.npr.org",            rss_url="https://feeds.npr.org/1001/rss.xml"),
    SourceConfig(name="dw",          base_url="https://www.dw.com",             rss_url="https://rss.dw.com/rdf/rss-en-all"),
    SourceConfig(name="france24",    base_url="https://www.france24.com",       rss_url="https://www.france24.com/en/rss"),
    SourceConfig(name="cbc",         base_url="https://www.cbc.ca",             rss_url="https://www.cbc.ca/cmlink/rss-world",        rss_browser_ua=True),
    SourceConfig(name="abc_au",      base_url="https://www.abc.net.au",         rss_url="https://www.abc.net.au/news/feed/51120/rss.xml"),
    SourceConfig(name="euronews",    base_url="https://www.euronews.com",       rss_url="https://www.euronews.com/rss"),
    SourceConfig(name="sky",         base_url="https://news.sky.com",           rss_url="https://feeds.skynews.com/feeds/rss/world.xml"),
    SourceConfig(name="independent", base_url="https://www.independent.co.uk",  rss_url="https://www.independent.co.uk/news/world/rss"),
    SourceConfig(name="thehill",     base_url="https://thehill.com",            rss_url="https://thehill.com/feed"),
    # Politico: Cloudflare blocks RSS endpoint — omitted
    SourceConfig(name="vox",         base_url="https://www.vox.com",            rss_url="https://www.vox.com/rss/index.xml"),
    SourceConfig(name="time",        base_url="https://time.com",               rss_url="https://time.com/feed"),
    SourceConfig(name="rt",          base_url="https://www.rt.com",             rss_url="https://www.rt.com/rss/news"),
]

# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class RSSEntry:
    url: str
    title: str
    author: Optional[str]
    published_at: Optional[datetime]


@dataclass
class Article:
    url: str
    title: str
    author: Optional[str]
    published_at: Optional[datetime]
    body_text: str
    scraped_at: datetime
    source: str


@dataclass
class ScrapingStats:
    attempted: int = 0
    succeeded: int = 0
    failed: int = 0
    skipped: int = 0

    def __str__(self) -> str:
        return (
            f"attempted={self.attempted} succeeded={self.succeeded} "
            f"failed={self.failed} skipped={self.skipped}"
        )


# ---------------------------------------------------------------------------
# Exceptions
# ---------------------------------------------------------------------------


class FetchError(Exception):
    pass


class ExtractionError(Exception):
    pass


class _BrowserCrashed(Exception):
    """Internal — the Playwright subprocess was killed by a signal (e.g. a
    Chromium segfault), not a controlled failure. Usually transient."""


# ---------------------------------------------------------------------------
# Fetchers
# ---------------------------------------------------------------------------


class BaseFetcher(ABC):
    @abstractmethod
    def fetch(self, url: str) -> str: ...

    def close(self) -> None:
        """Release any held resources. No-op by default."""


class HttpFetcher(BaseFetcher):
    """Static HTTP fetcher with retry logic (honours Retry-After on 429)."""

    def __init__(
        self,
        user_agent: str = USER_AGENT,
        timeout: tuple[int, int] = REQUEST_TIMEOUT,
        max_retries: int = MAX_RETRIES,
        retry_backoff: float = RETRY_BACKOFF,
    ) -> None:
        self._timeout = timeout
        self._max_retries = max_retries
        self._retry_backoff = retry_backoff
        self._session = requests.Session()
        self._session.headers["User-Agent"] = user_agent

    def with_user_agent(self, user_agent: str) -> HttpFetcher:
        return HttpFetcher(user_agent, self._timeout, self._max_retries, self._retry_backoff)

    @staticmethod
    def _retry_after_seconds(value: Optional[str]) -> Optional[float]:
        """Parse a Retry-After header (either delta-seconds or an HTTP date)."""
        if not value:
            return None
        try:
            return max(0.0, float(value))
        except ValueError:
            pass
        try:
            dt = dateutil_parser.parse(value)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return max(0.0, (dt - datetime.now(timezone.utc)).total_seconds())
        except (ValueError, OverflowError):
            return None

    def fetch(self, url: str) -> str:
        last_exc: Exception = RuntimeError("no attempts made")
        for attempt in range(1, self._max_retries + 1):
            sleep_for = self._retry_backoff * attempt
            try:
                r = self._session.get(url, timeout=self._timeout)
                r.raise_for_status()
                # Without a charset in Content-Type, requests falls back to
                # ISO-8859-1 for text/*, which turns a UTF-8 BOM into "ï»¿"
                # before "<?xml" and makes feeds parse to zero entries
                # (seen on federalreserve.gov). Detect the real encoding.
                if "charset" not in r.headers.get("Content-Type", "").lower():
                    r.encoding = r.apparent_encoding
                return r.text.lstrip("﻿")
            except requests.HTTPError as exc:
                resp = exc.response
                status = resp.status_code if resp is not None else None
                if status in (404, 410):
                    raise FetchError(f"Permanent HTTP error for {url}: {exc}") from exc
                if status == 429 and resp is not None:
                    ra = self._retry_after_seconds(resp.headers.get("Retry-After"))
                    if ra is not None:
                        sleep_for = ra
                last_exc = exc
            except requests.RequestException as exc:
                last_exc = exc
            if attempt < self._max_retries:
                time.sleep(min(sleep_for, MAX_RETRY_SLEEP))
        raise FetchError(
            f"Failed {url} after {self._max_retries} attempts: {last_exc}"
        ) from last_exc


class PlaywrightFetcher(BaseFetcher):
    """
    Headless browser fetcher for JS-heavy pages.

    Each fetch runs Playwright in a fresh, short-lived subprocess
    (playwright_fetch_worker.py) rather than in-process. Modern pika wraps
    its "blocking" connection around an asyncio-based transport internally,
    so this worker process already has an asyncio event loop alive by the
    time a message callback runs; Playwright's sync API spins up its own
    background thread with its own asyncio loop, and two independent
    asyncio setups sharing a process is a known-fragile combination — it
    was observed to break Playwright's internal dispatcher after the
    RabbitMQ consumer loop had been running a while, and an in-process
    restart of the browser/driver did not recover it. A subprocess
    guarantees no shared asyncio/threading state at all, at the cost of a
    fresh browser launch per JS-rendered fetch — acceptable since fetches
    are already rate-limited between articles.
    """

    # Native crashes (segfaults etc.) in a fresh Chromium launch are usually
    # transient flakiness, not a persistent problem with the URL — worth one
    # retry before giving up on the article.
    _MAX_CRASH_RETRIES = 1

    def __init__(
        self,
        user_agent: str = BROWSER_UA,
        load_wait_ms: int = JS_LOAD_WAIT_MS,
        timeout_ms: float = REQUEST_TIMEOUT[1] * 1000,
        subprocess_timeout_s: float = 60.0,
        max_crash_retries: int = _MAX_CRASH_RETRIES,
    ) -> None:
        self._user_agent = user_agent
        self._load_wait_ms = load_wait_ms
        self._timeout_ms = timeout_ms
        self._subprocess_timeout_s = subprocess_timeout_s
        self._max_crash_retries = max_crash_retries
        self._workers_dir = Path(__file__).resolve().parent.parent

    def fetch(self, url: str) -> str:
        attempt = 0
        while True:
            try:
                return self._fetch_once(url)
            except _BrowserCrashed as exc:
                if attempt >= self._max_crash_retries:
                    raise FetchError(
                        f"Browser fetch failed for {url}: {exc} (after {attempt + 1} attempt(s))"
                    ) from exc
                attempt += 1
                log.warning(
                    "Browser crashed fetching %s (%s) — retrying (attempt %d/%d)",
                    url, exc, attempt + 1, self._max_crash_retries + 1,
                )

    def _fetch_once(self, url: str) -> str:
        try:
            result = subprocess.run(
                [
                    sys.executable, "-m", "scraper.playwright_fetch_worker",
                    url, self._user_agent, str(self._load_wait_ms), str(self._timeout_ms),
                ],
                capture_output=True,
                text=True,
                timeout=self._subprocess_timeout_s,
                cwd=self._workers_dir,
            )
        except subprocess.TimeoutExpired as exc:
            raise FetchError(f"Browser fetch timed out for {url}: {exc}") from exc

        if result.returncode < 0:
            # Negative returncode = killed by signal (e.g. -11 = SIGSEGV) —
            # a native crash, not a controlled failure. Let fetch() retry it.
            raise _BrowserCrashed(f"killed by signal {-result.returncode}")

        if result.returncode != 0:
            error = self._extract_error(result.stdout) or result.stderr.strip() or f"exit code {result.returncode}"
            raise FetchError(f"Browser fetch failed for {url}: {error}")

        try:
            payload = json.loads(result.stdout)
        except json.JSONDecodeError as exc:
            raise FetchError(f"Browser fetch produced invalid output for {url}: {exc}") from exc

        return str(payload["html"])

    @staticmethod
    def _extract_error(stdout: str) -> Optional[str]:
        try:
            return json.loads(stdout).get("error")
        except (json.JSONDecodeError, AttributeError):
            return None

    def close(self) -> None:
        """No persistent process to tear down — each fetch is its own subprocess."""


class SmartFetcher(BaseFetcher):
    """
    Fetch strategy (in order):
      1. Static fetch with bot UA  — fast, works for most sites
      2. Static fetch with browser UA — bypasses UA-based bot blocks (e.g. CBC, NPR)
      3. Playwright headless browser — for JS-rendered pages that need execution
    """

    def __init__(
        self,
        http: HttpFetcher,
        browser: PlaywrightFetcher,
        min_chars: int = JS_MIN_CHARS,
    ) -> None:
        self._http            = http
        self._http_browser_ua = http.with_user_agent(BROWSER_UA)
        self._browser         = browser
        self._min_chars       = min_chars

    def _body_length(self, html: str, url: str) -> int:
        try:
            raw = trafilatura.bare_extraction(
                html, url=url, include_comments=False, include_tables=False, as_dict=True
            )
        except Exception:
            return 0
        if not isinstance(raw, dict):
            return 0
        return len(str(raw.get("text") or ""))

    def fetch(self, url: str) -> str:
        # 1. Try bot UA static fetch
        try:
            html = self._http.fetch(url)
            if self._body_length(html, url) >= self._min_chars:
                log.info("SmartFetcher %s: static (bot UA)", url)
                return html
        except FetchError:
            pass

        # 2. Try browser UA static fetch (bypasses UA-based blocks)
        try:
            html = self._http_browser_ua.fetch(url)
            if self._body_length(html, url) >= self._min_chars:
                log.info("SmartFetcher %s: static (browser UA)", url)
                return html
        except FetchError:
            pass

        # 3. Fall back to Playwright for JS-rendered pages
        log.info("SmartFetcher %s: Playwright", url)
        return self._browser.fetch(url)

    def close(self) -> None:
        self._browser.close()


# ---------------------------------------------------------------------------
# Extractor
# ---------------------------------------------------------------------------


class BaseExtractor(ABC):
    @abstractmethod
    def extract(self, html: str, url: str) -> str: ...


# Cookie / consent banners that trafilatura sometimes returns as the body
# (live run: 4 Bank of England document pages yielded only "Our use of
# cookies ..."; a CoinDesk page started with a TCF privacy notice).
# Banner phrases only: a bare "consent" would also drop enforcement text
# such as "agreed to a consent order".
_CONSENT_LINE = re.compile(
    r"\b(?:cookies?|cookie (?:policy|settings|preferences)|privacy (?:policy|notice|settings)|your privacy|"
    r"legitimate interest|Transparency and Consent Framework|consent (?:preferences|settings|to (?:the use of )?cookies)|"
    r"manage (?:cookie |consent )?(?:settings|preferences)|accept (?:all|recommended)(?: cookies| settings)?|reject all)\b",
    re.IGNORECASE,
)
MIN_ARTICLE_CHARS = 150


def strip_consent_boilerplate(text: str) -> str:
    """Drop lines that are cookie/consent/privacy-banner text."""
    kept = [line for line in text.splitlines() if not _CONSENT_LINE.search(line)]
    return "\n".join(line for line in kept if line.strip()).strip()


class TrafilaturaExtractor(BaseExtractor):
    def extract(self, html: str, url: str) -> str:
        body = self._extract(html, url)
        cleaned = strip_consent_boilerplate(body)
        if len(cleaned) < MIN_ARTICLE_CHARS:
            raise ExtractionError(f"Only cookie/consent boilerplate or too little text for {url}")
        return cleaned

    def _extract(self, html: str, url: str) -> str:
        try:
            raw = trafilatura.bare_extraction(
                html, url=url, include_comments=False, include_tables=False, as_dict=True
            )
        except Exception as exc:
            raise ExtractionError(f"Extraction failed for {url}: {exc}") from exc
        if raw is None:
            raise ExtractionError(f"No content extracted for {url}")
        if not isinstance(raw, dict):
            raise ExtractionError(f"Unexpected extraction result for {url}")
        body = str(raw.get("text") or "")
        if not body:
            raise ExtractionError(f"Empty body for {url}")
        return body


# ---------------------------------------------------------------------------
# Storage
# ---------------------------------------------------------------------------


class BaseStorage(ABC):
    @abstractmethod
    def save(self, article: Article) -> None: ...

    @abstractmethod
    def exists(self, url: str) -> bool: ...


class JsonlStorage(BaseStorage):
    """
    Append-only JSONL storage with an in-memory URL index so re-runs skip
    already-scraped articles instead of duplicating them.
    """

    def __init__(self, path: str = OUTPUT_PATH) -> None:
        self._path = path
        self._seen: set[str] = self._load_seen()
        if self._seen:
            log.info("Storage: loaded %d existing URLs from %s", len(self._seen), path)

    def _load_seen(self) -> set[str]:
        seen: set[str] = set()
        if not os.path.exists(self._path):
            return seen
        try:
            with open(self._path, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        url = json.loads(line).get("url")
                    except json.JSONDecodeError:
                        continue
                    if url:
                        seen.add(url)
        except OSError as exc:
            log.warning("Could not read existing storage %s: %s", self._path, exc)
        return seen

    def save(self, article: Article) -> None:
        d = asdict(article)
        for key in ("published_at", "scraped_at"):
            if isinstance(d[key], datetime):
                d[key] = d[key].isoformat()
        with open(self._path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(d, ensure_ascii=False) + "\n")
            fh.flush()
        self._seen.add(article.url)

    def exists(self, url: str) -> bool:
        return url in self._seen


# ---------------------------------------------------------------------------
# RSS parser (stateless, no ABC needed — one format)
# ---------------------------------------------------------------------------


def _text(tag: object) -> Optional[str]:
    if tag is None:
        return None
    s = getattr(tag, "string", None) or getattr(tag, "get_text", lambda: None)()
    return s.strip() if isinstance(s, str) and s.strip() else None


_TZINFOS: dict[str, tzoffset] = {
    "EDT": tzoffset("EDT", -4 * 3600),
    "EST": tzoffset("EST", -5 * 3600),
    "CDT": tzoffset("CDT", -5 * 3600),
    "CST": tzoffset("CST", -6 * 3600),
    "MDT": tzoffset("MDT", -6 * 3600),
    "MST": tzoffset("MST", -7 * 3600),
    "PDT": tzoffset("PDT", -7 * 3600),
    "PST": tzoffset("PST", -8 * 3600),
}


def _parse_date(date_str: Optional[str]) -> Optional[datetime]:
    if not date_str:
        return None
    try:
        return dateutil_parser.parse(date_str, tzinfos=_TZINFOS)
    except (ValueError, OverflowError):
        return None


_TRACKING_PARAMS = re.compile(r"^(utm_\w+|fbclid|gclid|mc_cid|mc_eid|cmpid|xtor|at_\w+)$", re.IGNORECASE)


def canonical_url(url: str) -> str:
    """Drop tracking query params and fragments, keep identity params.

    The old behaviour (url.split("?")[0]) collapsed every article of sites
    that identify articles by query string (e.g. ?id=123) into one URL.
    """
    parts = urlsplit(url.strip())
    query = [(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True)
             if not _TRACKING_PARAMS.match(k)]
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), ""))


def _struct_to_dt(st: object) -> Optional[datetime]:
    if not st:
        return None
    try:
        return datetime(*st[:6], tzinfo=timezone.utc)  # type: ignore[index]
    except (TypeError, ValueError):
        return None


_TIME_DATETIME = re.compile(r"<time[^>]*\bdatetime=[\"']([^\"']+)[\"']", re.IGNORECASE)


def _date_from_summary(summary: Optional[str]) -> Optional[datetime]:
    """Some CMS feeds (e.g. Drupal, used by ESMA) carry no pubDate and only
    embed the date as <time datetime="..."> inside the description HTML."""
    if not summary:
        return None
    m = _TIME_DATETIME.search(summary)
    return _parse_date(m.group(1)) if m else None


def parse_rss(feed: str | bytes) -> list[RSSEntry]:
    """Parse RSS 0.9x / 1.0 (RDF) / 2.0 and Atom via feedparser.

    feedparser handles BOMs, encodings, RDF feeds, namespaced dates
    (dc:date, updated, published) and recoverable malformed XML, which the
    previous BeautifulSoup parser missed (e.g. ESMA's feed parsed with no
    dates). Returns [] for documents with no entries.
    """
    if isinstance(feed, str):
        feed = feed.lstrip("﻿")
    parsed = feedparser.parse(feed)
    entries: list[RSSEntry] = []
    seen: set[str] = set()

    for item in parsed.entries:
        url = item.get("link") or ""
        if not url:
            for link in item.get("links", []):
                if link.get("rel", "alternate") == "alternate" and link.get("href"):
                    url = link["href"]
                    break
        if not url:
            guid = item.get("id") or ""
            url = guid if guid.startswith("http") else ""
        if not url:
            continue
        url = canonical_url(url)
        if url in seen:
            continue
        seen.add(url)

        published = (
            _struct_to_dt(item.get("published_parsed"))
            or _struct_to_dt(item.get("updated_parsed"))
            or _struct_to_dt(item.get("created_parsed"))
            or _parse_date(item.get("published") or item.get("updated"))
            or _date_from_summary(item.get("summary"))
        )
        entries.append(RSSEntry(
            url=url,
            title=(item.get("title") or "").strip(),
            author=item.get("author") or None,
            published_at=published,
        ))

    log.info("Parsed %d entries (%s)", len(entries), parsed.get("version") or "unknown format")
    return entries


COMMON_FEED_PATHS = ("/feed", "/feed/", "/rss", "/rss.xml", "/feed.xml", "/atom.xml", "/index.xml", "/news/rss")


def discover_feeds(base_url: str, fetcher: BaseFetcher) -> list[str]:
    """Find candidate feed URLs for a site.

    1. <link rel="alternate" type="application/rss+xml|atom+xml"> on the homepage
    2. conventional feed paths (/feed, /rss.xml, ...) as a fallback
    Returns URLs in preference order (news/press-looking feeds first).
    """
    found: list[str] = []
    try:
        html = fetcher.fetch(base_url)
        soup = BeautifulSoup(html, "lxml")
        for tag in soup.find_all("link", attrs={"rel": "alternate"}):
            typ = (tag.get("type") or "").lower()
            href = tag.get("href")
            if href and ("rss" in typ or "atom" in typ):
                found.append(urljoin(base_url, href))
    except FetchError:
        pass
    found.sort(key=lambda u: 0 if re.search(r"news|press|rss|feed", u, re.IGNORECASE) else 1)
    for path in COMMON_FEED_PATHS:
        url = urljoin(base_url, path)
        if url not in found:
            found.append(url)
    return found


# ---------------------------------------------------------------------------
# Robots checker
# ---------------------------------------------------------------------------


def _build_robots(base_url: str, timeout: tuple[int, int] = REQUEST_TIMEOUT) -> urllib.robotparser.RobotFileParser:
    """
    Fetch and parse robots.txt with a timeout.

    Unlike RobotFileParser.read() (which uses urllib with no timeout and, on
    any failure, leaves the parser in a state where can_fetch() returns False
    for *every* URL), this:
      * bounds the fetch with a timeout,
      * respects explicit Disallow rules on a 200 response,
      * treats 4xx as "allow all" per RFC 9309,
      * on 5xx / network error, fails *open* (allow) with a warning so a
        transient robots problem doesn't silently drop the whole source.
    """
    rp = urllib.robotparser.RobotFileParser()
    robots_url = urljoin(base_url, "/robots.txt")
    rp.set_url(robots_url)
    try:
        resp = requests.get(robots_url, timeout=timeout, headers={"User-Agent": USER_AGENT})
        if resp.status_code == 200:
            rp.parse(resp.text.splitlines())
        elif 400 <= resp.status_code < 500:
            rp.parse([])  # RFC 9309: 4xx → unrestricted
        else:
            log.warning("robots.txt for %s returned HTTP %d; assuming allow-all", base_url, resp.status_code)
            rp.parse([])
    except requests.RequestException as exc:
        log.warning("Could not read robots.txt for %s (%s); assuming allow-all", base_url, exc)
        rp.parse([])  # fail open; parse([]) sets last_checked so can_fetch works
    return rp


# ---------------------------------------------------------------------------
# Scraper
# ---------------------------------------------------------------------------


class Scraper:
    """
    Orchestrates scraping for any number of sources.

    Inject custom fetcher / extractor / storage to extend behaviour:
        scraper = Scraper(article_fetcher=MyFetcher(), storage=MyStorage())

    Lifecycle: scrape_all() closes fetchers when done. If you call
    scrape_source() directly, use the Scraper as a context manager
    (`with Scraper() as s: ...`) or call close() yourself so the browser
    is released.
    """

    def __init__(
        self,
        feed_fetcher: Optional[BaseFetcher] = None,
        article_fetcher: Optional[BaseFetcher] = None,
        extractor: Optional[BaseExtractor] = None,
        storage: Optional[BaseStorage] = None,
        rate_limit: float = RATE_LIMIT_SECONDS,
        max_entries_per_source: Optional[int] = None,
    ) -> None:
        http = HttpFetcher()
        self._feed_fetcher    = feed_fetcher    or http
        self._article_fetcher = article_fetcher or SmartFetcher(http, PlaywrightFetcher())
        self._extractor       = extractor       or TrafilaturaExtractor()
        self._storage         = storage         or JsonlStorage()
        self._rate_limit      = rate_limit
        self._max_entries     = max_entries_per_source

    # -- context manager ---------------------------------------------------

    def __enter__(self) -> "Scraper":
        return self

    def __exit__(self, exc_type: object | None, exc: object | None, tb: object | None) -> None:
        self.close()

    def close(self) -> None:
        for fetcher in (self._article_fetcher, self._feed_fetcher):
            try:
                fetcher.close()
            except Exception as exc:  # pragma: no cover - best effort
                log.warning("Error closing fetcher: %s", exc)

    # -- scraping ----------------------------------------------------------

    def scrape_source(self, source: SourceConfig) -> ScrapingStats:
        log.info("=== Scraping: %s ===", source.name)
        stats = ScrapingStats()
        robots = _build_robots(source.base_url)

        # Respect Crawl-delay if it's stricter than our own rate limit.
        crawl_delay = robots.crawl_delay(USER_AGENT)
        rate = max(self._rate_limit, float(crawl_delay) if crawl_delay else 0.0)

        feed_fetcher = (
            self._feed_fetcher.with_user_agent(BROWSER_UA)
            if source.rss_browser_ua and isinstance(self._feed_fetcher, HttpFetcher)
            else self._feed_fetcher
        )
        try:
            feed_html = feed_fetcher.fetch(source.rss_url)
        except FetchError as exc:
            log.error("Cannot fetch RSS for %s: %s", source.name, exc)
            return stats

        time.sleep(rate)

        try:
            entries = parse_rss(feed_html)
        except Exception as exc:
            log.error("Cannot parse RSS for %s: %s", source.name, exc)
            return stats

        if not entries:
            log.warning("No entries for %s", source.name)
            return stats

        if self._max_entries is not None:
            entries = entries[: self._max_entries]

        for entry in entries:
            if not robots.can_fetch(USER_AGENT, entry.url):
                log.info("robots.txt disallows %s", entry.url)
                stats.skipped += 1
                continue
            if self._storage.exists(entry.url):
                stats.skipped += 1
                continue

            stats.attempted += 1
            log.info("[%s] Fetching %s", source.name, entry.url)

            try:
                html = self._article_fetcher.fetch(entry.url)
                body = self._extractor.extract(html, entry.url)
            except (FetchError, ExtractionError) as exc:
                log.warning("Failed (%s): %s", entry.url, exc)
                stats.failed += 1
                time.sleep(rate)
                continue
            except Exception as exc:  # unexpected — log with type, keep going
                log.warning("Unexpected error (%s): %s: %s",
                            entry.url, type(exc).__name__, exc)
                stats.failed += 1
                time.sleep(rate)
                continue

            article = Article(
                url=entry.url,
                title=entry.title,
                author=entry.author,
                published_at=entry.published_at,
                body_text=body,
                scraped_at=datetime.now(timezone.utc),
                source=source.name,
            )
            self._storage.save(article)
            stats.succeeded += 1
            log.info("  Saved: %s", (article.title or "")[:80])
            time.sleep(rate)

        return stats

    def scrape_all(self, sources: list[SourceConfig]) -> ScrapingStats:
        total = ScrapingStats()
        try:
            for source in sources:
                try:
                    s = self.scrape_source(source)
                except KeyboardInterrupt:
                    log.warning("Interrupted by user; stopping after %s.", source.name)
                    break
                except Exception as exc:
                    # One bad source must never abort the whole batch.
                    log.error("Source %s crashed: %s: %s",
                              source.name, type(exc).__name__, exc)
                    continue
                total.attempted += s.attempted
                total.succeeded += s.succeeded
                total.failed    += s.failed
                total.skipped   += s.skipped
        finally:
            self.close()
        log.info("Done. %s", total)
        return total


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    with Scraper() as scraper:
        scraper.scrape_all(SOURCES)


if __name__ == "__main__":
    main()