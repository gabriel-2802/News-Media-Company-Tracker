"""
Extractor cleanup tests. Run from backend/workers/:
    scraper/venv/bin/python -m unittest discover -s scraper/tests -t .
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[4] / ".env")
os.environ.setdefault("SCRAPER_OUTPUT_PATH", "/dev/null")

from scraper.news_scraper import MIN_ARTICLE_CHARS, strip_consent_boilerplate  # noqa: E402

BOE_BANNER = (
    "Our use of cookies\n\nWe use necessary cookies to make our site work. We'd also like to use some "
    "non-essential cookies (including third-party cookies) to help us improve the site. By clicking "
    "'Accept recommended settings' on this banner, you accept our use of analytics cookies. For more "
    "information on how these cookies work please see our Cookie policy.\n"
)
ARTICLE = (
    "Kraken's xStocks added 200 UK and EU shares as the exchange expands tokenised equities.\n"
    "The company said demand from retail investors had grown sharply this year, and that it would add "
    "further markets in 2027."
)


class ConsentBoilerplate(unittest.TestCase):
    def test_banner_only_page_leaves_nothing(self):
        # Bank of England document pages: the whole "body" was the cookie banner.
        self.assertLess(len(strip_consent_boilerplate(BOE_BANNER)), MIN_ARTICLE_CHARS)

    def test_privacy_notice_before_article_is_removed(self):
        text = ("Your Privacy\nWe process your data ... based on legitimate interest.\n"
                "These choices will be signaled to our vendors participating in the Transparency and Consent "
                "Framework.\n" + ARTICLE)
        cleaned = strip_consent_boilerplate(text)
        self.assertNotIn("Privacy", cleaned)
        self.assertIn("xStocks added 200 UK and EU shares", cleaned)

    def test_enforcement_wording_is_kept(self):
        text = ("The bank agreed to a consent order with the OCC and will pay a civil money penalty.\n"
                "Payment vendors must obtain customer consent before sharing data.")
        self.assertEqual(strip_consent_boilerplate(text), text)

    def test_article_without_banner_is_unchanged(self):
        self.assertEqual(strip_consent_boilerplate(ARTICLE), ARTICLE)


if __name__ == "__main__":
    unittest.main()
