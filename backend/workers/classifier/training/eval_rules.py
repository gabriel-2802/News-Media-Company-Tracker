"""
Measure the rule layer's effect on the held-out public test sets.

Scores the installed model with and without classifier/rules.py and prints
macro-F1 plus every label whose F1, precision or recall moved. Texts are
treated as titles with no source category (the strictest setting: title
matches score highest, and the financial-context anchor is required).

Note: the public sets are not ground truth for this taxonomy. TFNT labels
political news as "other" and files e.g. "central bank fines Binance" under
central banks, so some rule "false positives" are taxonomy disagreements.

Run from backend/workers/ after training/prepare_data.py:
    classifier/venv/bin/python -m classifier.training.eval_rules
"""
from __future__ import annotations

import json
import logging
from pathlib import Path

import numpy as np
import torch
from sklearn.metrics import f1_score, precision_score, recall_score

from classifier.classifier import MAX_LENGTH, NewsClassifier
from classifier.rules import apply_rules
from classifier.training.taxonomy import LABEL2ID, LABELS

DATA = Path(__file__).resolve().parent / "data"


def main() -> None:
    logging.disable(logging.WARNING)
    clf = NewsClassifier.get_instance()
    dev = "mps" if torch.backends.mps.is_available() else "cpu"
    clf.model.to(dev)

    for split in ("test_tfnt", "test_reuters"):
        rows = [json.loads(line) for line in open(DATA / f"{split}.jsonl")]
        gold = np.array([r["labels"] for r in rows])
        base, ruled = np.zeros_like(gold), np.zeros_like(gold)
        for i in range(0, len(rows), 64):
            texts = [r["text"] for r in rows[i:i + 64]]
            enc = clf.tokenizer(texts, return_tensors="pt", truncation=True, max_length=MAX_LENGTH,
                                padding=True).to(dev)
            with torch.no_grad():
                probs = torch.sigmoid(clf.model(**enc).logits).cpu()
            for j, p in enumerate(probs):
                chosen = (p >= clf.thresholds).nonzero().flatten().tolist() or [int(p.argmax())]
                model_topics = [(clf.id2label[k], float(p[k])) for k in chosen]
                for t, _ in model_topics:
                    base[i + j, LABEL2ID[t]] = 1
                for t, _ in apply_rules(model_topics, title=texts[j])[0]:
                    ruled[i + j, LABEL2ID[t]] = 1

        present = gold.sum(axis=0) > 0
        fb = f1_score(gold, base, average=None, zero_division=0)
        fr = f1_score(gold, ruled, average=None, zero_division=0)
        print(f"{split}: macro-F1 model {fb[present].mean():.4f} -> with rules {fr[present].mean():.4f}")
        for k in np.where(present)[0]:
            if abs(fr[k] - fb[k]) >= 0.003:
                pb, pr = (precision_score(gold[:, k], x[:, k], zero_division=0) for x in (base, ruled))
                rb, rr = (recall_score(gold[:, k], x[:, k], zero_division=0) for x in (base, ruled))
                print(f"   {LABELS[k]:30} F1 {fb[k]:.3f}->{fr[k]:.3f}  P {pb:.2f}->{pr:.2f}  R {rb:.2f}->{rr:.2f}")


if __name__ == "__main__":
    main()
