"""require_env strips quotes that `make`'s `include .env` passes through literally."""
import os
import unittest
from unittest import mock

from env_config import require_env


class RequireEnvTest(unittest.TestCase):

    def check(self, raw: str, expected: str):
        with mock.patch.dict(os.environ, {"IT_ENV_VALUE": raw}):
            self.assertEqual(require_env("IT_ENV_VALUE"), expected)

    def test_strips_one_pair_of_matching_quotes(self):
        self.check('"NewsMonitorBot/0.1 (research scraper)"', "NewsMonitorBot/0.1 (research scraper)")
        self.check("'Mozilla/5.0 (Macintosh)'", "Mozilla/5.0 (Macintosh)")

    def test_leaves_other_values_alone(self):
        self.check("NewsMonitorBot/0.1", "NewsMonitorBot/0.1")
        self.check('say "hi"', 'say "hi"')               # inner quotes
        self.check('"unbalanced', '"unbalanced')
        self.check("'mixed\"", "'mixed\"")
        self.check('""""', '""')                          # only one pair

    def test_empty_after_unquoting_is_missing(self):
        with mock.patch.dict(os.environ, {"IT_ENV_VALUE": '""'}):
            with self.assertRaises(RuntimeError):
                require_env("IT_ENV_VALUE")


if __name__ == "__main__":
    unittest.main()
