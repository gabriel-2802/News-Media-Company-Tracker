"""Sentiment rule layer: ended enforcement actions are positive, new ones keep the model's score.
No model is loaded; apply_rules only post-processes scores."""
import unittest

from enricher.sentiment import ACTION_ENDED_SCORE, Score, apply_rules

NEGATIVE = Score(-0.83, "negative")


class ActionEndedRuleTest(unittest.TestCase):

    def corrected(self, text: str, score: Score = NEGATIVE) -> Score:
        return apply_rules([text], [score])[0]

    def test_ended_actions_become_positive(self):
        for text in (
            "Federal Reserve Board announces termination of enforcement action with Ameriprise Financial",
            "Federal Reserve Board announces termination of enforcement actions with two banks",
            "The Federal Reserve Board on Tuesday announced the termination of the enforcement action against the bank.",
            "OCC terminates consent order against Wells Fargo",
            "Regulators lift cease-and-desist order on TD Bank",
            "The written agreement with the holding company was terminated in March.",
            "The consent order issued in 2018 has been lifted.",
        ):
            with self.subTest(text=text):
                self.assertEqual(self.corrected(text), Score(ACTION_ENDED_SCORE, "positive"))

    def test_new_actions_and_unrelated_terminations_keep_the_model_score(self):
        for text in (
            "Federal Reserve Board announces enforcement action against American Express",
            "Federal Reserve Board issues enforcement actions with former employees of Regions Financial",
            "Bank may face a consent order over AML failures",
            "Bank terminates 300 employees after enforcement review",
            "CFPB ends investigation into overdraft fees",
            "Fed finalizes changes to enhance the transparency of stress tests",
        ):
            with self.subTest(text=text):
                self.assertEqual(self.corrected(text), NEGATIVE)

    def test_already_more_positive_score_is_kept(self):
        self.assertEqual(self.corrected("OCC terminates consent order", Score(0.9, "positive")),
                         Score(0.9, "positive"))

    def test_scores_stay_aligned_with_texts(self):
        texts = ["OCC terminates consent order", "Bank fined for AML failures"]
        out = apply_rules(texts, [NEGATIVE, NEGATIVE])
        self.assertEqual(out, [Score(ACTION_ENDED_SCORE, "positive"), NEGATIVE])


if __name__ == "__main__":
    unittest.main()
