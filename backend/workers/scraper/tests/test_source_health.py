"""Source health threshold of the validator (no network). Run from backend/workers/:
    scraper/venv/bin/python -m unittest discover -s scraper/tests -t .
"""
from __future__ import annotations

import os
import unittest
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[4] / ".env")
os.environ.setdefault("SCRAPER_OUTPUT_PATH", "/dev/null")

from scraper.tools.validate_sources import Result, health_exit_code  # noqa: E402


def results(passing: int, other: dict[str, int]) -> list[Result]:
    out = [Result(name=f"p{i}", category="TRADE_PRESS", region="US", verdict="PASS") for i in range(passing)]
    for verdict, n in other.items():
        out += [Result(name=f"{verdict}{i}", category="TRADE_PRESS", region="US", verdict=verdict) for i in range(n)]
    return out


class SourceHealth(unittest.TestCase):
    def test_no_threshold_never_fails(self):
        self.assertEqual(health_exit_code(results(0, {"FAIL": 5}), None), 0)

    def test_at_the_limit_passes_above_fails(self):
        self.assertEqual(health_exit_code(results(8, {"FAIL": 2}), 0.2), 0)          # 20 % is allowed
        self.assertEqual(health_exit_code(results(7, {"FAIL": 3}), 0.2), 1)

    def test_headline_only_counts_as_degraded(self):
        self.assertEqual(health_exit_code(results(7, {"FAIL": 1, "HEADLINE_ONLY": 2}), 0.2), 1)
