"""
Entity-linking tests against the real company universe (data/company_universe.json).

Run from backend/workers/:
    enricher/venv/bin/python -m unittest discover -s enricher/tests -t .
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

from entity_linker import Company, EntityLinker

UNIVERSE = Path(__file__).resolve().parents[4] / "data" / "company_universe.json"


class LinkerAgainstRealUniverse(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rows = json.loads(UNIVERSE.read_text())
        # The JSON has no ids; use the display name as a stand-in id.
        cls.linker = EntityLinker(Company(r["name"], r["name"], tuple(r["aliases"])) for r in rows)

    def names(self, title, body=""):
        return {m.company_id: m for m in self.linker.link(title, body)}

    def test_bank_and_regulator_in_title(self):
        found = self.names("JPMorgan Chase fined by OCC over trade surveillance gaps")
        self.assertIn("JPMorgan Chase", found)
        self.assertIn("OCC", found)
        self.assertTrue(found["JPMorgan Chase"].in_title)
        self.assertGreaterEqual(found["JPMorgan Chase"].salience(), 0.6)

    def test_alias_and_name_count_as_one_company(self):
        found = self.names("Bank of America lifts profit", "BofA said loan growth picked up. Bank of America shares rose.")
        self.assertEqual(found["Bank of America"].count, 3)

    def test_common_word_brand_needs_capital_and_finance_context(self):
        self.assertEqual(self.names("The police chase ended downtown"), {})
        self.assertEqual(self.names("Chase the dream: a profile of a marathon runner"), {})
        self.assertIn("JPMorgan Chase", self.names("Chase raises savings rates for deposit customers"))

    def test_block_the_company_vs_a_block_of_shares(self):
        self.assertIn("Block", self.names("Block reports higher Cash App revenue"))
        self.assertNotIn("Block", self.names("Investors bought a block of shares"))

    def test_place_names_need_finance_context(self):
        self.assertEqual(self.names("Halifax hosts the summer festival"), {})
        self.assertIn("Lloyds Banking Group", self.names("Halifax raises mortgage rates"))

    def test_longest_match_wins_over_generic_suffix(self):
        found = self.names("Fifth Third Bancorp, Inc. completes acquisition")
        self.assertIn("Fifth Third Bancorp", found)
        self.assertNotIn("Bancorp, Inc.", found)

    def test_unknown_bancorp_is_not_linked_to_the_bancorp(self):
        self.assertEqual(self.names("Smallville Bancorp, Inc. reports quarterly earnings"), {})

    def test_shared_name_is_ambiguous_and_skipped(self):
        self.assertIn("First Bancorp", self.linker.ambiguous)
        found = self.names("First Bancorp reports record deposits")
        self.assertNotIn("First Bancorp (FBP)", found)
        self.assertNotIn("First Bancorp (FBNC)", found)

    def test_acronyms_are_case_sensitive(self):
        self.assertIn("SEC", self.names("SEC charges adviser with fraud"))
        self.assertNotIn("SEC", self.names("The page loads in under a sec on mobile"))

    def test_central_bank_by_full_name(self):
        self.assertIn("Federal Reserve", self.names("The Federal Reserve held interest rates steady"))

    def test_lead_mention_raises_salience(self):
        lead = self.names("Regional lenders face deposit pressure", "Truist said deposits fell in the quarter.")
        late = self.names("Regional lenders face deposit pressure", "x " * 400 + "Truist said deposits fell.")
        self.assertGreater(lead["Truist Financial"].salience(), late["Truist Financial"].salience())

    def test_common_phrase_names_do_not_match_prose(self):
        # From the first live run: "financial institutions" linked to Financial Institutions Inc 8 times.
        body = "Supervisors said financial institutions must hold more capital. Financial institutions welcomed it."
        self.assertNotIn("Financial Institutions Inc", self.names("Bank of England publishes FPC record", body))
        self.assertEqual(self.names("first national survey of savings"), {})

    def test_weak_names_with_corporate_word_still_match_when_capitalised(self):
        self.assertIn("Northern Trust", self.names("Northern Trust expands custody business"))
        self.assertNotIn("Northern Trust", self.names("the northern trust fund for schools"))
        self.assertIn("Citizens Financial Group", self.names("Citizens Bank raises deposit rates"))

    def test_mention_sentences_are_collected_for_sentiment(self):
        found = self.names("Barclays settles probe", "Barclays agreed to pay a penalty. Unrelated sentence here.")
        self.assertTrue(any("penalty" in s for s in found["Barclays"].sentences))


if __name__ == "__main__":
    unittest.main()
