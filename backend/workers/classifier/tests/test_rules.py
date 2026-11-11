"""
Rule-layer tests. Model outputs below are what the trained model actually
returned for these (real or realistic) headlines; the rules must fix the
regulator-content misses without disturbing correct non-regulatory results.

Run from backend/workers/:
    classifier/venv/bin/python -m unittest discover -s classifier/tests -t .
"""
from __future__ import annotations

import unittest

from classifier.rules import MNA, MONETARY, OTHER, REGULATION, apply_rules


def topics(model, title, lead="", category=None):
    merged, _ = apply_rules(model, title, lead, category)
    return dict(merged)


class FixesRegulatorMisses(unittest.TestCase):
    def test_fca_aml_fine_becomes_regulation(self):
        t = topics([(OTHER, 0.62)], "FCA fines Example Bank £12m over anti-money laundering failings",
                   category="REGULATOR")
        self.assertGreaterEqual(t[REGULATION], 0.9)
        self.assertNotIn(OTHER, t)

    def test_fed_application_approval_is_deal_and_regulation_not_monetary(self):
        t = topics([(MONETARY, 0.99)],
                   "Federal Reserve Board announces approval of two applications by Mutual Federal Bancorp, MHC, "
                   "and MFB Bancorp, Inc.", category="CENTRAL_BANK")
        self.assertNotIn(MONETARY, t)
        self.assertIn(REGULATION, t)

    def test_fed_bank_acquisition_approval(self):
        t = topics([(MONETARY, 0.99)],
                   "Federal Reserve Board announces approval of application by Example Bancorp to acquire "
                   "First Example Bank", category="CENTRAL_BANK")
        self.assertIn(MNA, t)
        self.assertIn(REGULATION, t)
        self.assertNotIn(MONETARY, t)

    def test_fed_enforcement_termination(self):
        t = topics([(MONETARY, 0.98)],
                   "Federal Reserve Board announces termination of enforcement action with Allegiant Bank",
                   category="CENTRAL_BANK")
        self.assertEqual(max(t, key=t.get), REGULATION)
        self.assertNotIn(MONETARY, t)

    def test_ransomware_breach(self):
        t = topics([(REGULATION, 0.98)], "Ransomware crew claims 5 banks and a CU; 2 confirm breaches")
        self.assertIn(REGULATION, t)

    def test_swipe_fee_fight(self):
        t = topics([(OTHER, 0.99)], "Democrats' affordability mandate could revive swipe-fee fight",
                   category="TRADE_PRESS")
        self.assertIn(REGULATION, t)
        self.assertNotIn(OTHER, t)

    def test_watchdog(self):
        t = topics([("company_product_news", 0.98)], "FTC emerges as main consumer-protection watchdog in D.C.",
                   category="TRADE_PRESS")
        self.assertIn(REGULATION, t)

    def test_regulator_publication_defaults_to_regulation(self):
        t = topics([(OTHER, 0.99)], "Looking back to move forward: why reviewing our rules matters",
                   category="REGULATOR")
        self.assertEqual(list(t), [REGULATION])


class FixesNonRegulatoryMisses(unittest.TestCase):
    def test_earnings_beat_from_live_run(self):
        t = topics([("company_product_news", 0.61)], "JPMorgan profit beats estimates as investment banking fees jump",
                   category="TRADE_PRESS")
        self.assertEqual(max(t, key=t.get), "earnings_results")

    def test_quarterly_results(self):
        t = topics([(OTHER, 0.7)], "Regional lender's third-quarter net income falls on higher provisions")
        self.assertIn("earnings_results", t)

    def test_analyst_upgrade(self):
        t = topics([("markets_stock_moves", 0.8)], "Morgan Stanley upgrades Truist to overweight on deposit trends")
        self.assertIn("analyst_ratings", t)

    def test_rating_agency_downgrade(self):
        t = topics([(OTHER, 0.6)], "Moody's downgrades five regional banks citing commercial property risk")
        self.assertIn("analyst_ratings", t)

    def test_dividend_declaration(self):
        t = topics([("company_product_news", 0.5)], "Fifth Third declares quarterly cash dividend")
        self.assertEqual(max(t, key=t.get), "dividends_buybacks")

    def test_leadership_change(self):
        t = topics([(OTHER, 0.7)], "Citigroup names new chief financial officer from rival bank")
        self.assertIn("leadership_personnel", t)
        t = topics([(OTHER, 0.7)], "Lender's CEO to step down after a decade in charge")
        self.assertIn("leadership_personnel", t)


class NewRulesStayTight(unittest.TestCase):
    def test_app_upgrade_is_not_an_analyst_rating(self):
        t = topics([("company_product_news", 0.9)], "Bank upgrades its mobile app with new budgeting tools")
        self.assertNotIn("analyst_ratings", t)

    def test_dividend_mention_in_unrelated_story_lead_needs_phrase(self):
        t = topics([(MNA, 0.95)], "Lender agrees to buy rival", lead="Shareholders will keep receiving dividends.")
        self.assertNotIn("dividends_buybacks", t)

    def test_president_of_country_is_not_leadership(self):
        t = topics([("macro_economy", 0.9)], "President signs bill on infrastructure spending")
        self.assertNotIn("leadership_personnel", t)


class LiveRunCorrections(unittest.TestCase):
    """Cases from the first live run over real feeds."""

    def test_central_bank_banknote_news_is_not_regulation(self):
        t = topics([(MONETARY, 0.9)], "Bank of England announces the four animals that will feature on our new banknotes",
                   category="CENTRAL_BANK")
        self.assertNotIn(REGULATION, t)
        self.assertNotIn(MONETARY, t)

    def test_central_bank_consultation_is_regulation(self):
        t = topics([(MONETARY, 0.9)], "Federal Reserve Board requests public comment on two proposals related to supervision",
                   category="CENTRAL_BANK")
        self.assertIn(REGULATION, t)
        self.assertNotIn(MONETARY, t)

    def test_public_consultation_on_banknotes_is_not_rulemaking(self):
        t = topics([(OTHER, 0.6)], "Bank of England announces the four animals for its next banknotes",
                   lead="Nearly 500,000 people responded to a public consultation. The Bank used the consultation feedback.",
                   category="CENTRAL_BANK")
        self.assertNotIn(REGULATION, t)

    def test_consultation_on_capital_rules_is_rulemaking(self):
        t = topics([(OTHER, 0.6)], "PRA consults on changes to capital requirements for smaller banks", category="REGULATOR")
        self.assertIn(REGULATION, t)

    def test_central_bank_qt_letter_is_monetary(self):
        t = topics([("company_product_news", 0.93)],
                   "Exchange of letters between the Governor and the Chancellor regarding QT and the APF",
                   category="CENTRAL_BANK")
        self.assertIn(MONETARY, t)

    def test_head_stepping_down_is_leadership(self):
        t = topics([(MNA, 0.83)], "Ripple Prime head Marc Asch to step down, Noel Kimmel named successor")
        self.assertIn("leadership_personnel", t)

    def test_single_earnings_phrase_in_market_wrap_lead_is_not_earnings(self):
        t = topics([("markets_stock_moves", 0.99)], "Bitcoin sits out stock rebound as ETF outflows extend",
                   lead="Equities rallied as earnings season continued, while bitcoin lagged.")
        self.assertNotIn("earnings_results", t)


class LeavesCorrectResultsAlone(unittest.TestCase):
    def test_fomc_statement_stays_monetary(self):
        t = topics([(MONETARY, 0.99)], "Federal Reserve issues FOMC statement", category="CENTRAL_BANK")
        self.assertEqual(list(t), [MONETARY])

    def test_ecb_monetary_speech_stays_monetary(self):
        t = topics([(MONETARY, 0.99)],
                   "Philip R. Lane: The science and practice of monetary policy communication",
                   category="CENTRAL_BANK")
        self.assertEqual(list(t), [MONETARY])

    def test_earnings_guidance_is_not_regulation(self):
        t = topics([("earnings_results", 0.95)], "Regional bank raises full-year guidance after record quarter",
                   category="NEWS")
        self.assertEqual(list(t), ["earnings_results"])

    def test_deal_pending_regulatory_approval_stays_mna_only(self):
        t = topics([(MNA, 0.99)], "Regional lender agrees to acquire rival in $2.1B all-stock deal",
                   lead="The deal is expected to close next year, pending regulatory approval.",
                   category="TRADE_PRESS")
        self.assertEqual(max(t, key=t.get), MNA)
        self.assertNotIn(REGULATION, t)

    def test_buyback_is_not_a_deal(self):
        t = topics([("dividends_buybacks", 0.97)], "Lender plans to buy back $2 billion of its shares")
        self.assertNotIn(MNA, t)

    def test_rules_never_lower_model_scores(self):
        t = topics([(REGULATION, 0.99)], "Bank fined over AML controls", category="NEWS")
        self.assertEqual(t[REGULATION], 0.99)

    def test_digital_euro_speech_from_central_bank_untouched(self):
        t = topics([(OTHER, 0.91)], "Piero Cipollone: Cash and the digital euro: two sides of the same coin",
                   category="CENTRAL_BANK")
        self.assertEqual(list(t), [OTHER])


if __name__ == "__main__":
    unittest.main()
