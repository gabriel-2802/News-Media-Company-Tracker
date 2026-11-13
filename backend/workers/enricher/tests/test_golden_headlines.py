"""
Golden entity-linking set: 50 tricky headlines (common-word brands, acronyms, subsidiaries,
failed or out-of-universe banks, look-alike names) with the exact companies expected, against
the real company universe. Run from backend/workers/:
    enricher/venv/bin/python -m unittest discover -s enricher/tests -t .
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

from entity_linker import Company, EntityLinker

ROOT = Path(__file__).resolve().parents[4]
GOLDEN = Path(__file__).parent / "golden" / "headlines.json"


class GoldenHeadlines(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rows = json.loads((ROOT / "data" / "company_universe.json").read_text())
        cls.linker = EntityLinker(Company(r["name"], r["name"], tuple(r["aliases"])) for r in rows)
        cls.cases = json.loads(GOLDEN.read_text())

    def test_set_size(self):
        self.assertEqual(len(self.cases), 50)

    def test_every_headline_links_exactly_the_expected_companies(self):
        for case in self.cases:
            with self.subTest(case["title"]):
                got = sorted({m.company_id for m in self.linker.link(case["title"], "")})
                self.assertEqual(got, case["expect"], case["why"])


if __name__ == "__main__":
    unittest.main()
