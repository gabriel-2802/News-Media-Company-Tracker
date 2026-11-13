"""
Classifier smoke test: the trained model loads, its labels agree with the taxonomy, the
thresholds and the topics seeded in Neo4j (V4), and clear headlines get the obvious topic.
Skipped when the model (git-ignored, ~420 MB) is not present. Run from backend/workers/:
    classifier/venv/bin/python -m unittest discover -s classifier/tests -t .
"""
from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

from classifier.training.taxonomy import LABELS

MODEL_DIR = Path(__file__).resolve().parents[1] / "cls_model_output"
V4 = Path(__file__).resolve().parents[3] / "neo4j" / "migrations" / "V4__seed_banking_topics.cypher"
HAS_MODEL = (MODEL_DIR / "model.safetensors").exists()


class ModelFiles(unittest.TestCase):
    def test_taxonomy_matches_neo4j_topics(self):
        seeded = set(re.findall(r"['\"]([a-z_]+)['\"]", V4.read_text())) & set(LABELS)
        self.assertEqual(seeded, set(LABELS), "every taxonomy label is seeded as a Topic")
        self.assertEqual(len(LABELS), 13)

    @unittest.skipUnless(HAS_MODEL, "trained model not present")
    def test_label_maps_and_thresholds_are_consistent(self):
        label2id = json.loads((MODEL_DIR / "label2id.json").read_text())
        config = json.loads((MODEL_DIR / "config.json").read_text())
        thresholds = json.loads((MODEL_DIR / "thresholds.json").read_text())
        self.assertEqual(label2id, {name: i for i, name in enumerate(LABELS)})
        self.assertEqual({int(k): v for k, v in config["id2label"].items()}, dict(enumerate(LABELS)))
        self.assertEqual(config["problem_type"], "multi_label_classification")
        self.assertEqual(set(thresholds), set(LABELS))
        for name, t in thresholds.items():
            self.assertGreater(t, 0.0, name)
            self.assertLess(t, 1.0, name)


@unittest.skipUnless(HAS_MODEL, "trained model not present")
class Predictions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from classifier.classifier import NewsClassifier, classifier_input
        cls.clf = NewsClassifier.get_instance()
        cls.input = staticmethod(classifier_input)

    def topics(self, title, body=""):
        return self.clf.classify(self.input(title, body))

    def test_output_shape(self):
        out = self.topics("Bank shares mixed in quiet trading")
        self.assertTrue(out)
        self.assertTrue(all(name in LABELS and 0.0 <= p <= 1.0 for name, p in out))
        self.assertEqual(out, sorted(out, key=lambda t: t[1], reverse=True))
        self.assertEqual(out, self.topics("Bank shares mixed in quiet trading"))      # deterministic

    def test_clear_headlines(self):
        cases = {
            "Fed raises interest rates by 25 basis points to fight inflation": "monetary_policy_rates",
            "Bank agrees to acquire regional rival in $2 billion all-stock deal": "m_and_a_investments",
            "Lender declares quarterly dividend and announces $5 billion share buyback": "dividends_buybacks",
            "Analyst downgrades bank stock to sell, cuts price target": "analyst_ratings",
            "Dollar slides against the euro and yen after jobs data": "fx_currencies",
        }
        for title, expected in cases.items():
            with self.subTest(title):
                self.assertIn(expected, [name for name, _ in self.topics(title)][:2])


if __name__ == "__main__":
    unittest.main()
