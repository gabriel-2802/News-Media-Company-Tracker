"""
Fine-tune a multi-label banking topic classifier on the public datasets
prepared by training/prepare_data.py.

  * Base model: ProsusAI/finbert (BERT further pre-trained on financial text)
    with a fresh 13-way sigmoid head.
  * Loss: BCE with per-label pos_weight = clip(sqrt(neg/pos), 1, 8) so rare
    labels (regulation, analyst ratings) are not drowned out.
  * Keeps the epoch with the best validation macro-F1, then tunes one
    threshold per label on validation.
  * Decision rule (also used by the worker): every label whose probability
    clears its threshold; if none does, the single most likely label.

Writes a self-contained model directory (weights, config with id2label,
tokenizer, label2id.json, thresholds.json, metrics.json, README.md).

Run from backend/workers/classifier/:
    venv/bin/python -m training.train --out cls_model_output_banking
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
import time
from pathlib import Path

os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
HERE = Path(__file__).resolve().parent
os.environ.setdefault("HF_HOME", str(HERE / ".cache" / "hf"))

import numpy as np  # noqa: E402
import torch  # noqa: E402
from sklearn.metrics import accuracy_score, f1_score  # noqa: E402
from torch.utils.data import DataLoader  # noqa: E402
from transformers import (  # noqa: E402
    AutoModelForSequenceClassification,
    AutoTokenizer,
    DataCollatorWithPadding,
    get_linear_schedule_with_warmup,
)

from training.taxonomy import LABEL2ID, LABELS  # noqa: E402

DATA = HERE / "data"
BASE_MODEL = "ProsusAI/finbert"
# Step time scales with padded length (measured on an M4/MPS: 0.8 s at 32
# tokens vs 2.2 s at 96 for batch 16). Tweets average ~30 tokens; 64 still
# covers a Reuters headline plus its first sentence.
MAX_LEN = 64
FREEZE_LAYERS = 6   # fine-tune only the top 6 of 12 encoder layers (+ head); ~40% faster steps


class LengthBucketSampler(torch.utils.data.Sampler):
    """Batches of similar-length texts (less padding), in random batch order."""

    def __init__(self, lengths: list[int], batch_size: int, seed: int) -> None:
        self.lengths, self.batch_size, self.rng = lengths, batch_size, random.Random(seed)

    def __iter__(self):
        idx = list(range(len(self.lengths)))
        self.rng.shuffle(idx)                       # random tie-breaking within equal lengths
        idx.sort(key=lambda i: self.lengths[i])
        batches = [idx[i:i + self.batch_size] for i in range(0, len(idx), self.batch_size)]
        self.rng.shuffle(batches)
        return iter(batches)

    def __len__(self) -> int:
        return math.ceil(len(self.lengths) / self.batch_size)


def load(name: str) -> list[dict]:
    with open(DATA / f"{name}.jsonl") as f:
        return [json.loads(line) for line in f]


def device() -> torch.device:
    if torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


class Encoded(torch.utils.data.Dataset):
    def __init__(self, rows: list[dict], tok, max_len: int = MAX_LEN) -> None:
        enc = tok([r["text"] for r in rows], truncation=True, max_length=max_len)
        self.items = [
            {"input_ids": enc["input_ids"][i], "attention_mask": enc["attention_mask"][i],
             "labels": [float(x) for x in rows[i]["labels"]]}
            for i in range(len(rows))
        ]

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, i: int) -> dict:
        return self.items[i]


@torch.no_grad()
def predict_probs(model, loader, dev) -> tuple[np.ndarray, np.ndarray]:
    model.eval()
    probs, gold = [], []
    for batch in loader:
        labels = batch.pop("labels")
        batch = {k: v.to(dev) for k, v in batch.items()}
        probs.append(torch.sigmoid(model(**batch).logits).float().cpu().numpy())
        gold.append(labels.numpy())
    return np.concatenate(probs), np.concatenate(gold).astype(int)


def decide(probs: np.ndarray, thresholds: np.ndarray) -> np.ndarray:
    pred = (probs >= thresholds).astype(int)
    empty = pred.sum(axis=1) == 0
    pred[empty, probs[empty].argmax(axis=1)] = 1
    return pred


def tune_thresholds(probs: np.ndarray, gold: np.ndarray) -> np.ndarray:
    grid = np.arange(0.05, 0.96, 0.025)
    best = np.full(probs.shape[1], 0.5)
    for j in range(probs.shape[1]):
        if gold[:, j].sum() == 0:
            continue
        scores = [f1_score(gold[:, j], probs[:, j] >= t, zero_division=0) for t in grid]
        best[j] = float(grid[int(np.argmax(scores))])
    return best


def report(name: str, probs: np.ndarray, gold: np.ndarray, thresholds: np.ndarray) -> dict:
    pred = decide(probs, thresholds)
    present = gold.sum(axis=0) > 0
    per_label = f1_score(gold, pred, average=None, zero_division=0)
    out = {
        "rows": int(len(gold)),
        "macro_f1_present_labels": round(float(per_label[present].mean()), 4),
        "micro_f1": round(float(f1_score(gold, pred, average="micro", zero_division=0)), 4),
        # Single-label view: is the top-scoring label one of the gold labels?
        "top1_accuracy": round(float(np.mean(gold[np.arange(len(gold)), probs.argmax(axis=1)] == 1)), 4),
        "per_label_f1": {LABELS[j]: round(float(per_label[j]), 4) for j in range(len(LABELS)) if present[j]},
    }
    print(f"[{name}] macro-F1={out['macro_f1_present_labels']:.3f} micro-F1={out['micro_f1']:.3f} "
          f"top1={out['top1_accuracy']:.3f}", flush=True)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="cls_model_output_banking")
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--max-len", type=int, default=MAX_LEN)
    ap.add_argument("--log-every", type=int, default=25)
    ap.add_argument("--freeze-layers", type=int, default=FREEZE_LAYERS)
    ap.add_argument("--lr", type=float, default=3e-5)
    ap.add_argument("--seed", type=int, default=13)
    args = ap.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    dev = device()
    print(f"device={dev} base={BASE_MODEL}", flush=True)

    tok = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = AutoModelForSequenceClassification.from_pretrained(
        BASE_MODEL,
        num_labels=len(LABELS),
        problem_type="multi_label_classification",
        id2label={i: n for i, n in enumerate(LABELS)},
        label2id=LABEL2ID,
        ignore_mismatched_sizes=True,     # replace FinBERT's 3-way sentiment head
        # MPS fused attention (SDPA) has no dropout support; 0 keeps the fast path.
        attention_probs_dropout_prob=0.0,
    ).to(dev)
    if args.freeze_layers:
        for p in model.bert.embeddings.parameters():
            p.requires_grad = False
        for layer in model.bert.encoder.layer[: args.freeze_layers]:
            for p in layer.parameters():
                p.requires_grad = False
    trainable = [p for p in model.parameters() if p.requires_grad]
    print(f"trainable params: {sum(p.numel() for p in trainable) / 1e6:.1f}M "
          f"(bottom {args.freeze_layers} layers frozen)", flush=True)

    train_rows, val_rows = load("train"), load("val")
    collate = DataCollatorWithPadding(tok)
    train_ds = Encoded(train_rows, tok, args.max_len)
    train_dl = DataLoader(train_ds, collate_fn=collate,
                          batch_sampler=LengthBucketSampler([len(x["input_ids"]) for x in train_ds.items],
                                                            args.batch, args.seed))
    val_dl = DataLoader(Encoded(val_rows, tok, args.max_len), batch_size=32, collate_fn=collate)

    y = np.array([r["labels"] for r in train_rows])
    pos = y.sum(axis=0)
    pos_weight = torch.tensor(np.clip(np.sqrt((len(y) - pos) / np.maximum(pos, 1)), 1.0, 8.0),
                              dtype=torch.float32, device=dev)
    loss_fn = torch.nn.BCEWithLogitsLoss(pos_weight=pos_weight)

    opt = torch.optim.AdamW(trainable, lr=args.lr, weight_decay=0.01)
    total = len(train_dl) * args.epochs
    sched = get_linear_schedule_with_warmup(opt, int(0.06 * total), total)

    best_f1, best_state, history = -1.0, None, []
    for epoch in range(1, args.epochs + 1):
        model.train()
        t0, running = time.time(), 0.0
        for step, batch in enumerate(train_dl, 1):
            labels = batch.pop("labels").to(dev)
            batch = {k: v.to(dev) for k, v in batch.items()}
            loss = loss_fn(model(**batch).logits, labels)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            opt.zero_grad()
            running += loss.item()
            if step % args.log_every == 0:
                print(f"epoch {epoch} step {step}/{len(train_dl)} loss {running / step:.4f} "
                      f"({time.time() - t0:.0f}s)", flush=True)

        probs, gold = predict_probs(model, val_dl, dev)
        f1 = f1_score(gold, decide(probs, np.full(len(LABELS), 0.5)), average="macro", zero_division=0)
        history.append({"epoch": epoch, "train_loss": round(running / len(train_dl), 4), "val_macro_f1@0.5": round(f1, 4)})
        print(f"epoch {epoch} done in {time.time() - t0:.0f}s, val macro-F1@0.5={f1:.4f}", flush=True)
        if f1 > best_f1:
            best_f1 = f1
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            # Checkpoint the best epoch so an interrupted run still leaves a usable model.
            ckpt = HERE / "runs" / "best_checkpoint"
            model.save_pretrained(ckpt, safe_serialization=True)
            tok.save_pretrained(ckpt)
            print(f"checkpointed epoch {epoch} to {ckpt}", flush=True)

    model.load_state_dict(best_state)
    probs, gold = predict_probs(model, val_dl, dev)
    thresholds = tune_thresholds(probs, gold)

    metrics = {
        "base_model": BASE_MODEL,
        "max_length": args.max_len,
        "batch_size": args.batch,
        "frozen_layers": args.freeze_layers,
        "epochs_trained": args.epochs,
        "history": history,
        "val": report("val", probs, gold, thresholds),
    }
    for split in ("test_tfnt", "test_reuters"):
        dl = DataLoader(Encoded(load(split), tok, args.max_len), batch_size=32, collate_fn=collate)
        metrics[split] = report(split, *predict_probs(model, dl, dev), thresholds)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model.cpu().save_pretrained(out, safe_serialization=True)
    tok.save_pretrained(out)
    (out / "label2id.json").write_text(json.dumps(LABEL2ID, indent=2) + "\n")
    (out / "thresholds.json").write_text(json.dumps({LABELS[j]: round(float(thresholds[j]), 3)
                                                     for j in range(len(LABELS))}, indent=2) + "\n")
    (out / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n")
    (out / "README.md").write_text(
        "# Banking topic classifier\n\n"
        f"Multi-label fine-tune of `{BASE_MODEL}` on public data only "
        "(zeroshot/twitter-financial-news-topic + Reuters-21578 ModApte), mapped onto "
        f"{len(LABELS)} banking topics (see `training/taxonomy.py`).\n\n"
        f"Input: title + opening sentences, max {args.max_len} tokens. Decision: every label whose "
        "sigmoid probability is at least its threshold in `thresholds.json`; if none, the top label.\n\n"
        f"Public test macro-F1: TFNT {metrics['test_tfnt']['macro_f1_present_labels']}, "
        f"Reuters {metrics['test_reuters']['macro_f1_present_labels']}. Full numbers in `metrics.json`.\n"
    )
    print(f"saved to {out.resolve()}", flush=True)


if __name__ == "__main__":
    main()
