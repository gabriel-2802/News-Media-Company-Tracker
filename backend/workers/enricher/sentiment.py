"""
FinBERT sentiment (ProsusAI/finbert, trained on Financial PhraseBank).

FinBERT is a sentence-level model: on a multi-sentence text one strongly
worded sentence dominates (a positive headline followed by "rival shares
fell" scored -0.96). So texts are scored sentence by sentence and combined:

  * signed score = P(positive) - P(negative), in [-1, 1]
  * label        = FinBERT's most likely class (not a threshold on the
                   score, which mislabels neutral statements such as
                   "Fed holds rates steady")
  * article      = 0.5 * title + 0.5 * mean(first lead sentences)
  * company      = weighted mean over sentences mentioning it, title weight 2
"""
from __future__ import annotations

import os
import re
from dataclasses import dataclass
from typing import Optional

import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

MODEL_NAME = os.environ.get("SENTIMENT_MODEL", "ProsusAI/finbert")
MAX_LENGTH = 128
LEAD_SENTENCES = 3
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'“])")


@dataclass(frozen=True)
class Score:
    value: float          # P(positive) - P(negative)
    label: str            # positive | negative | neutral (argmax)


def sentences(text: str, limit: int) -> list[str]:
    parts = [p.strip() for p in _SENTENCE_END.split(text.strip()) if len(p.strip()) > 3]
    return parts[:limit]


class SentimentModel:
    _instance: Optional["SentimentModel"] = None

    @classmethod
    def get_instance(cls) -> "SentimentModel":
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def __init__(self) -> None:
        self.tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
        self.model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME)
        self.model.eval()
        self.id2label = {int(k): v.lower() for k, v in self.model.config.id2label.items()}
        labels = {v: k for k, v in self.id2label.items()}
        self._pos, self._neg = labels["positive"], labels["negative"]

    def score(self, texts: list[str]) -> list[Score]:
        if not texts:
            return []
        enc = self.tokenizer(texts, return_tensors="pt", truncation=True, max_length=MAX_LENGTH, padding=True)
        with torch.no_grad():
            probs = torch.softmax(self.model(**enc).logits, dim=-1)
        return [Score(round(float(p[self._pos] - p[self._neg]), 4), self.id2label[int(p.argmax())]) for p in probs]


def article_sentiment(title: Score, lead: list[Score]) -> Score:
    """Blend title and lead sentences; the label follows the blended score's sign
    unless both title and lead are mostly neutral."""
    lead_value = sum(s.value for s in lead) / len(lead) if lead else title.value
    value = round(0.5 * title.value + 0.5 * lead_value, 4)
    labels = [title.label] + [s.label for s in lead]
    if labels.count("neutral") > len(labels) / 2 and abs(value) < 0.5:
        return Score(value, "neutral")
    return Score(value, "positive" if value > 0 else "negative" if value < 0 else "neutral")


def weighted_mean(scores: list[Score], weights: list[float]) -> Optional[float]:
    if not scores:
        return None
    total = sum(weights)
    return round(sum(s.value * w for s, w in zip(scores, weights)) / total, 4)
