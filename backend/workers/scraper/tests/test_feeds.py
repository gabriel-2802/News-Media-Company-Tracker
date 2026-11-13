"""
Feed parsing, fetching and robots tests on fixtures that reproduce quirks seen on live
sources (no network). Run from backend/workers/:
    scraper/venv/bin/python -m unittest discover -s scraper/tests -t .
"""
from __future__ import annotations

import os
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

import requests
from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[4] / ".env")
os.environ.setdefault("SCRAPER_OUTPUT_PATH", "/dev/null")

from scraper import news_scraper as ns  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures"


def fixture(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def response(body: bytes, status: int = 200, content_type: str = "text/xml", headers: dict | None = None) -> requests.Response:
    r = requests.Response()
    r.status_code = status
    r._content = body
    r.headers["Content-Type"] = content_type
    r.headers.update(headers or {})
    r.url = "https://example.test/feed"
    r.encoding = "ISO-8859-1" if "charset" not in content_type else None   # what requests does for text/*
    return r


class FeedFormats(unittest.TestCase):
    def test_fed_feed_with_bom_and_no_charset_parses_through_the_fetcher(self):
        fetcher = ns.HttpFetcher(max_retries=1)
        with mock.patch.object(fetcher._session, "get", return_value=response(fixture("fed_bom_no_charset.xml"))):
            text = fetcher.fetch("https://www.federalreserve.gov/feeds/press_all.xml")
        self.assertTrue(text.startswith("<?xml"))
        entries = ns.parse_rss(text)
        self.assertEqual(len(entries), 2)
        self.assertIn("São Paulo", entries[0].title)                       # decoded as UTF-8, not Latin-1
        self.assertEqual(entries[0].published_at, datetime(2026, 10, 8, 16, 30, tzinfo=timezone.utc))
        self.assertEqual(entries[1].published_at, datetime(2026, 10, 7, 18, 0, tzinfo=timezone.utc))   # EDT -> UTC

    def test_drupal_feed_takes_the_date_from_time_element(self):
        entries = ns.parse_rss(fixture("esma_drupal_time_only.xml"))
        self.assertEqual([e.published_at for e in entries],
                         [datetime(2026, 10, 21, 12, 0, tzinfo=timezone.utc), None])

    def test_atom_alternate_link_id_fallback_and_skip(self):
        entries = ns.parse_rss(fixture("atom_updated_only.xml"))
        self.assertEqual([e.url for e in entries], [
            "https://fintech.example.test/2026/10/payments-series-b",          # rel=alternate, not self
            "https://fintech.example.test/2026/10/id-only",                    # http id as link
        ])                                                                     # urn id without link skipped
        self.assertEqual(entries[0].published_at, datetime(2026, 10, 20, 7, 15, tzinfo=timezone.utc))
        self.assertEqual(entries[0].author, "A. Writer")

    def test_rdf_with_dc_date(self):
        [entry] = ns.parse_rss(fixture("rdf_dc_date.xml"))
        self.assertEqual(entry.published_at, datetime(2026, 10, 30, 12, 15, tzinfo=timezone.utc))

    def test_tracking_params_and_fragments_dropped_identity_params_kept(self):
        entries = ns.parse_rss(fixture("tracking_and_duplicates.xml"))
        self.assertEqual([e.url for e in entries],
                         ["https://news.example.test/story?id=101", "https://news.example.test/story?id=102"])

    def test_html_error_page_is_not_a_feed(self):
        self.assertEqual(ns.parse_rss(b"<html><body>Access denied</body></html>"), [])


class Fetching(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(ns.time, "sleep")
        self.sleep = patcher.start()
        self.addCleanup(patcher.stop)

    def test_declared_charset_is_respected(self):
        fetcher = ns.HttpFetcher(max_retries=1)
        body = "<p>Société Générale</p>".encode("latin-1")
        with mock.patch.object(fetcher._session, "get", return_value=response(body, content_type="text/html; charset=ISO-8859-1")):
            self.assertEqual(fetcher.fetch("https://example.test/a"), "<p>Société Générale</p>")

    def test_404_is_permanent_without_retries(self):
        fetcher = ns.HttpFetcher(max_retries=3)
        with mock.patch.object(fetcher._session, "get", return_value=response(b"", status=404)) as get:
            with self.assertRaises(ns.FetchError):
                fetcher.fetch("https://example.test/gone")
        self.assertEqual(get.call_count, 1)

    def test_429_honours_retry_after_then_succeeds(self):
        fetcher = ns.HttpFetcher(max_retries=3, retry_backoff=1.0)
        replies = [response(b"", status=429, headers={"Retry-After": "7"}), response(b"<rss/>", content_type="text/xml; charset=utf-8")]
        with mock.patch.object(fetcher._session, "get", side_effect=replies):
            self.assertEqual(fetcher.fetch("https://example.test/feed"), "<rss/>")
        self.sleep.assert_called_once_with(min(7.0, ns.MAX_RETRY_SLEEP))

    def test_5xx_retries_then_fails(self):
        fetcher = ns.HttpFetcher(max_retries=2, retry_backoff=0.5)
        with mock.patch.object(fetcher._session, "get", return_value=response(b"", status=503)) as get:
            with self.assertRaises(ns.FetchError):
                fetcher.fetch("https://example.test/feed")
        self.assertEqual(get.call_count, 2)

    def test_retry_after_http_date(self):
        self.assertEqual(ns.HttpFetcher._retry_after_seconds("Wed, 21 Oct 2015 07:28:00 GMT"), 0.0)   # in the past
        self.assertIsNone(ns.HttpFetcher._retry_after_seconds("soon"))


class Robots(unittest.TestCase):
    def robots(self, reply):
        with mock.patch.object(ns.requests, "get", **reply):
            return ns._build_robots("https://bank.example.test")

    def test_disallow_rules_on_200_are_respected(self):
        rp = self.robots({"return_value": response(b"User-agent: *\nDisallow: /private/\n", content_type="text/plain")})
        self.assertFalse(rp.can_fetch(ns.USER_AGENT, "https://bank.example.test/private/report"))
        self.assertTrue(rp.can_fetch(ns.USER_AGENT, "https://bank.example.test/news/item"))

    def test_4xx_means_allow_all(self):
        rp = self.robots({"return_value": response(b"", status=404, content_type="text/plain")})
        self.assertTrue(rp.can_fetch(ns.USER_AGENT, "https://bank.example.test/anything"))

    def test_network_error_fails_open(self):
        rp = self.robots({"side_effect": requests.ConnectionError("down")})
        self.assertTrue(rp.can_fetch(ns.USER_AGENT, "https://bank.example.test/anything"))


class Extraction(unittest.TestCase):
    def test_article_body_without_navigation_or_banner(self):
        body = ns.TrafilaturaExtractor().extract(fixture("article_with_banner.html").decode(), "https://news.example.test/a")
        self.assertIn("consent order with the Office of the Comptroller", body)
        self.assertIn("civil money penalty of $15 million", body)
        self.assertNotIn("non-essential cookies", body)

    def test_banner_only_page_is_rejected(self):
        with self.assertRaises(ns.ExtractionError):
            ns.TrafilaturaExtractor().extract(fixture("banner_only.html").decode(), "https://www.bankofengland.co.uk/doc")


if __name__ == "__main__":
    unittest.main()
