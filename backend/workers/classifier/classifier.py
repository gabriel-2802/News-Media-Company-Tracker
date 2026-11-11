from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Optional

import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

# Override with CLASSIFIER_MODEL_DIR to point at another trained model.
DIRECTORY = Path(os.environ.get("CLASSIFIER_MODEL_DIR", Path(__file__).resolve().parent / "cls_model_output"))

# Must match training/train.py: the model was trained on headline-length
# text (title + opening of the story), so classify the same view.
MAX_LENGTH = 64
LEAD_CHARS = 600
_WS = re.compile(r"\s+")


def classifier_input(title: str, body: str) -> str:
    """Title + the opening of the body, whitespace-normalised."""
    return _WS.sub(" ", f"{title}. {body[:LEAD_CHARS]}").strip()


class NewsClassifier:
    """Singleton multi-label banking topic classifier.

    Fine-tuned ProsusAI/finbert on public financial-news data (see
    training/). Returns every topic whose sigmoid probability clears its
    per-label threshold (thresholds.json); if none does, the single most
    likely topic, so every article gets a primary topic.
    """

    _instance: Optional[NewsClassifier] = None

    @staticmethod
    def get_instance() -> NewsClassifier:
        if NewsClassifier._instance is None:
            NewsClassifier()
        return NewsClassifier._instance

    def __init__(self) -> None:
        if NewsClassifier._instance is not None:
            raise Exception("This class is a singleton! Use get_instance() instead.")

        self.tokenizer = AutoTokenizer.from_pretrained(DIRECTORY)
        self.model = AutoModelForSequenceClassification.from_pretrained(DIRECTORY)
        self.model.eval()

        label2id = json.loads((DIRECTORY / "label2id.json").read_text())
        self.id2label = {int(v): k for k, v in label2id.items()}
        thresholds = json.loads((DIRECTORY / "thresholds.json").read_text())
        self.thresholds = torch.tensor([thresholds[self.id2label[i]] for i in range(len(self.id2label))])

        NewsClassifier._instance = self

    def classify(self, text: str) -> list[tuple[str, float]]:
        """Return [(topic, probability)], highest first; never empty."""
        inputs = self.tokenizer(text, return_tensors="pt", truncation=True, max_length=MAX_LENGTH)
        with torch.no_grad():
            probs = torch.sigmoid(self.model(**inputs).logits)[0]

        chosen = (probs >= self.thresholds).nonzero().flatten().tolist()
        if not chosen:
            chosen = [int(probs.argmax())]
        result = [(self.id2label[i], round(float(probs[i]), 4)) for i in chosen]
        return sorted(result, key=lambda t: t[1], reverse=True)
