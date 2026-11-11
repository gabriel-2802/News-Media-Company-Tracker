"""
Build the multi-label training data for the banking topic classifier.

Writes training/data/{train,val,test_tfnt,test_reuters}.jsonl, each row:
    {"text": "...", "labels": [0/1 x len(LABELS)], "source": "tfnt|reuters"}

Splits
  * TFNT: the dataset's own `validation` split is held out as test_tfnt;
    10% of its `train` (stratified) becomes validation.
  * Reuters: ModApte "training/" docs go to train (10% to val), "test/" docs
    become test_reuters.
  * Reuters docs with no mapped category are kept as "other", capped so the
    commodity-heavy remainder does not swamp the set.

Run from backend/workers/classifier/:
    venv/bin/python -m training.prepare_data
"""
from __future__ import annotations

import json
import os
import random
import re
from collections import Counter
from pathlib import Path

from training.taxonomy import LABEL2ID, LABELS, REUTERS_LABELS, TFNT_LABELS

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".cache"
OUT = HERE / "data"
SEED = 13
REUTERS_OTHER_CAP = 800
LEAD_CHARS = 600          # title + opening of the story; the model sees <=128 tokens

os.environ.setdefault("HF_HOME", str(CACHE / "hf"))

_URL = re.compile(r"https?://\S+")
_WS = re.compile(r"\s+")


def clean(text: str) -> str:
    text = _URL.sub("", text).replace("&amp;", "&")
    return _WS.sub(" ", text).strip()


def one_hot(names: set[str]) -> list[int]:
    vec = [0] * len(LABELS)
    for n in names:
        vec[LABEL2ID[n]] = 1
    return vec


def tfnt_rows() -> tuple[list[dict], list[dict]]:
    from datasets import load_dataset

    ds = load_dataset("zeroshot/twitter-financial-news-topic")

    def convert(split: str) -> list[dict]:
        rows = []
        for r in ds[split]:
            text = clean(r["text"])
            if len(text) < 15:
                continue
            rows.append({"text": text, "labels": one_hot({TFNT_LABELS[r["label"]][1]}), "source": "tfnt"})
        return rows

    return convert("train"), convert("validation")


def reuters_rows() -> tuple[list[dict], list[dict]]:
    import nltk

    nltk_dir = str(CACHE / "nltk")
    nltk.download("reuters", download_dir=nltk_dir, quiet=True)
    nltk.data.path.insert(0, nltk_dir)
    from nltk.corpus import reuters

    rng = random.Random(SEED)
    train, test, other_train, other_test = [], [], [], []
    for fid in reuters.fileids():
        raw = reuters.raw(fid)
        lines = raw.strip().split("\n", 1)
        title = lines[0].strip().title()       # titles are ALL CAPS in the corpus
        body = clean(lines[1]) if len(lines) > 1 else ""
        text = clean(f"{title}. {body}")[:LEAD_CHARS]
        mapped = {REUTERS_LABELS[c] for c in reuters.categories(fid) if c in REUTERS_LABELS}
        row = {"text": text, "labels": one_hot(mapped or {"other"}), "source": "reuters"}
        is_train = fid.startswith("training/")
        if mapped:
            (train if is_train else test).append(row)
        else:
            (other_train if is_train else other_test).append(row)
    rng.shuffle(other_train)
    rng.shuffle(other_test)
    train += other_train[:REUTERS_OTHER_CAP]
    test += other_test[: REUTERS_OTHER_CAP // 3]
    return train, test


def split_val(rows: list[dict], frac: float, rng: random.Random) -> tuple[list[dict], list[dict]]:
    """Stratify on the first positive label so rare classes appear in val."""
    by: dict[int, list[dict]] = {}
    for r in rows:
        by.setdefault(r["labels"].index(1), []).append(r)
    train, val = [], []
    for group in by.values():
        rng.shuffle(group)
        k = max(1, int(len(group) * frac))
        val += group[:k]
        train += group[k:]
    return train, val


def write(name: str, rows: list[dict]) -> None:
    with open(OUT / f"{name}.jsonl", "w") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")


def describe(name: str, rows: list[dict]) -> None:
    c = Counter()
    for r in rows:
        for i, v in enumerate(r["labels"]):
            if v:
                c[LABELS[i]] += 1
    print(f"{name:13} {len(rows):6} rows | " + ", ".join(f"{k}={v}" for k, v in sorted(c.items())))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    rng = random.Random(SEED)
    tfnt_train_all, tfnt_test = tfnt_rows()
    reu_train_all, reu_test = reuters_rows()
    tfnt_train, tfnt_val = split_val(tfnt_train_all, 0.10, rng)
    reu_train, reu_val = split_val(reu_train_all, 0.10, rng)

    train = tfnt_train + reu_train
    val = tfnt_val + reu_val
    rng.shuffle(train)
    rng.shuffle(val)

    for name, rows in [("train", train), ("val", val), ("test_tfnt", tfnt_test), ("test_reuters", reu_test)]:
        write(name, rows)
        describe(name, rows)
    (OUT / "labels.json").write_text(json.dumps(LABELS, indent=2) + "\n")


if __name__ == "__main__":
    main()
