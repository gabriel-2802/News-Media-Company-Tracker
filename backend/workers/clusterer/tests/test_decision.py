"""
Clustering decision tests, built from cases seen on the first 140 real articles.

Run from backend/workers/:
    clusterer/venv/bin/python -m unittest discover -s clusterer/tests -t .
"""
from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone

from clusterer.decision import ArticleView, Policy, StoryView, accept, choose, merged_payload

P = Policy()
NOV = datetime(2026, 11, 6, 12, tzinfo=timezone.utc)


def story(score, first=NOV, last=NOV, sources=("boe-news",), official_only=True, companies=(), sid="s1"):
    return StoryView(sid, score, first, last, set(sources), official_only, set(companies))


class DateWindow(unittest.TestCase):
    def test_same_kind_of_release_months_apart_is_rejected(self):
        # BoE July vs November rate decisions: similarity 0.90, 99 days apart.
        july = ArticleView(NOV - timedelta(days=99), "boe-news", True)
        ok, reason = accept(july, story(0.90), P)
        self.assertFalse(ok)
        self.assertIn("before", reason)

    def test_article_within_window_of_story_span(self):
        a = ArticleView(NOV + timedelta(days=2), "guardian-banking", False)
        self.assertTrue(accept(a, story(0.70, official_only=False, sources=("guardian-banking",)), P)[0])


class OfficialReleases(unittest.TestCase):
    def test_ecb_decision_and_statement_same_day_merge(self):
        a = ArticleView(NOV, "ecb-press", True)
        self.assertTrue(accept(a, story(0.83, sources=("ecb-press",)), P)[0])

    def test_different_central_banks_do_not_merge(self):
        # ECB "Monetary policy decisions" vs "Federal Reserve issues FOMC statement": 0.63.
        fed = ArticleView(NOV, "fed-press", True)
        ok, reason = accept(fed, story(0.63, sources=("ecb-press",)), P)
        self.assertFalse(ok)
        self.assertIn("different publisher", reason)

    def test_same_publisher_formulaic_minutes_below_official_threshold(self):
        # "Minutes of the Money Market Committee" vs "Minutes of the SONIA Stakeholder Advisory Group": 0.65.
        a = ArticleView(NOV, "boe-news", True)
        self.assertFalse(accept(a, story(0.65), P)[0])

    def test_press_article_can_join_official_story(self):
        # e.g. the Guardian covering an FCA fine published by the FCA.
        a = ArticleView(NOV, "guardian-banking", False)
        self.assertTrue(accept(a, story(0.82, sources=("fca-news",)), P)[0])


class CompanyGuard(unittest.TestCase):
    def test_disjoint_banks_are_rejected(self):
        a = ArticleView(NOV, "banking-dive", False, frozenset({"jpm"}))
        s = story(0.80, official_only=False, sources=("banking-dive",), companies=("barclays",))
        ok, reason = accept(a, s, P)
        self.assertFalse(ok)
        self.assertIn("shared", reason)

    def test_shared_bank_is_accepted(self):
        a = ArticleView(NOV, "guardian-banking", False, frozenset({"barclays", "hsbc"}))
        s = story(0.80, official_only=False, sources=("fca-news",), companies=("barclays",))
        self.assertTrue(accept(a, s, P)[0])

    def test_guard_skipped_when_one_side_names_no_bank(self):
        a = ArticleView(NOV, "banking-dive", False, frozenset())
        s = story(0.80, official_only=False, sources=("banking-dive",), companies=("barclays",))
        self.assertTrue(accept(a, s, P)[0])


class Choosing(unittest.TestCase):
    def test_falls_through_to_second_candidate_when_best_is_rejected(self):
        a = ArticleView(NOV, "guardian-banking", False, frozenset({"barclays"}))
        best = story(0.90, official_only=False, sources=("x",), companies=("jpm",), sid="wrong-bank")
        second = story(0.70, official_only=False, sources=("y",), companies=("barclays",), sid="right-bank")
        chosen, reasons = choose(a, [best, second], P)
        self.assertEqual(chosen.story_id, "right-bank")
        self.assertEqual(len(reasons), 1)

    def test_no_acceptable_candidate_means_new_story(self):
        a = ArticleView(NOV, "fed-press", True)
        chosen, _ = choose(a, [story(0.63, sources=("ecb-press",))], P)
        self.assertIsNone(chosen)

    def test_payload_widens_span_and_unions_sources_and_companies(self):
        a = ArticleView(NOV + timedelta(days=1), "guardian-banking", False, frozenset({"barclays"}))
        p = merged_payload(story(0.9, sources=("fca-news",), companies=("hsbc",)), a, 2)
        self.assertEqual(p["lastPublishedAt"], (NOV + timedelta(days=1)).isoformat())
        self.assertEqual(p["sources"], ["fca-news", "guardian-banking"])
        self.assertFalse(p["officialOnly"])
        self.assertEqual(p["companies"], ["barclays", "hsbc"])


if __name__ == "__main__":
    unittest.main()
