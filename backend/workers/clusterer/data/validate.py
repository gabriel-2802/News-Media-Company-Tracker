"""
validate.py

Loads similar_news.json / different_news.json (built by
build_validation_pairs.py), embeds every article, and reports how well
cosine similarity separates same-story from different-story pairs.

By default uses the same ArticleEmbedder singleton as production
(embedder.py, model set by EMBEDDING_MODEL_NAME). Pass --model to swap in
a different sentence-transformers model for comparison without touching
production code — results are written to a model-specific filename so
repeated runs never clobber each other's output.

Since embeddings are normalized, cosine similarity is just the dot
product.

Usage:
    python validate.py --pairs-dir ./output
    python validate.py --pairs-dir ./output --model sentence-transformers/all-MiniLM-L6-v2
"""
from __future__ import annotations

import argparse
import json
import logging
import re
import sys
from pathlib import Path

import numpy as np
import torch
from sentence_transformers import SentenceTransformer

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from embedder import ArticleEmbedder, MODEL_NAME  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-8s %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger(__name__)

# The model's max_seq_length is 32768 tokens with no automatic truncation
# below that, so an unusually long scraped article (tens of thousands of
# chars) drives quadratic self-attention memory into the tens-of-GB range
# on CPU. Cap input length before embedding — a few thousand characters is
# already more than enough signal for story-similarity purposes.
_MAX_EMBED_CHARS = 4000


def load_pairs(path: Path) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8"))


class _AdHocEmbedder:
    """Same normalize-and-encode contract as ArticleEmbedder, for a
    caller-supplied model instead of the production singleton."""

    def __init__(self, model_name: str) -> None:
        device = "mps" if torch.backends.mps.is_available() else "cpu"
        self.model = SentenceTransformer(model_name, device=device)

    def embed(self, text: str) -> list[float]:
        return self.model.encode(text, normalize_embeddings=True).tolist()


def slugify(name: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_.-]+", "_", name)


def score_pairs(pairs: list[dict], embedder: ArticleEmbedder, label: str) -> list[dict]:
    scored = []
    for pair in pairs:
        vec_a = np.array(embedder.embed(pair["text_a"][:_MAX_EMBED_CHARS]))
        vec_b = np.array(embedder.embed(pair["text_b"][:_MAX_EMBED_CHARS]))
        similarity = float(np.dot(vec_a, vec_b))
        scored.append({
            "pair_id": pair["pair_id"],
            "source": pair["source"],
            "label": label,
            "similarity": similarity,
        })
    return scored


def summarize(scores: list[float]) -> dict:
    arr = np.array(scores)
    return {
        "count": len(arr),
        "mean": float(arr.mean()),
        "median": float(np.median(arr)),
        "std": float(arr.std()),
        "min": float(arr.min()),
        "max": float(arr.max()),
        "p10": float(np.percentile(arr, 10)),
        "p25": float(np.percentile(arr, 25)),
        "p75": float(np.percentile(arr, 75)),
        "p90": float(np.percentile(arr, 90)),
    }


def best_threshold(same_scores: list[float], diff_scores: list[float]) -> dict:
    """Sweep thresholds and pick the one maximizing balanced accuracy
    (avg of true-positive rate on same-pairs and true-negative rate on
    different-pairs) — a threshold-selection method, not a clustering one."""
    same_arr = np.array(same_scores)
    diff_arr = np.array(diff_scores)

    candidates = np.unique(np.concatenate([same_arr, diff_arr]))
    best = {"threshold": 0.5, "balanced_accuracy": -1.0, "tpr": 0.0, "tnr": 0.0}

    for t in candidates:
        tpr = float((same_arr >= t).mean())
        tnr = float((diff_arr < t).mean())
        balanced_accuracy = (tpr + tnr) / 2
        if balanced_accuracy > best["balanced_accuracy"]:
            best = {"threshold": float(t), "balanced_accuracy": balanced_accuracy, "tpr": tpr, "tnr": tnr}

    return best


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pairs-dir", type=Path, default=Path("./output"), help="Dir containing similar_news.json / different_news.json")
    parser.add_argument("--model", type=str, default=None, help="sentence-transformers model to use instead of the production ArticleEmbedder (default: production model, %s)" % MODEL_NAME)
    parser.add_argument("--out", type=Path, default=None, help="Output path for results JSON (default: <pairs-dir>/embedding_validation_results_<model>.json — model-specific name so runs against different models never overwrite each other)")
    args = parser.parse_args()

    if args.model:
        model_name = args.model
        embedder = _AdHocEmbedder(model_name)
    else:
        model_name = MODEL_NAME
        embedder = ArticleEmbedder.get_instance()

    out_path = args.out or (args.pairs_dir / f"embedding_validation_results_{slugify(model_name)}.json")
    if out_path.exists() and not args.out:
        log.warning("Default output path %s already exists — pass --out explicitly if you don't want to overwrite it.", out_path)

    same_pairs = load_pairs(args.pairs_dir / "similar_news.json")
    diff_pairs = load_pairs(args.pairs_dir / "different_news.json")
    log.info("Loaded %d same-story pairs, %d different-story pairs", len(same_pairs), len(diff_pairs))
    log.info("Using model: %s", model_name)

    log.info("Embedding same-story pairs...")
    same_scored = score_pairs(same_pairs, embedder, label="same_story")
    log.info("Embedding different-story pairs...")
    diff_scored = score_pairs(diff_pairs, embedder, label="different_story")

    same_similarities = [p["similarity"] for p in same_scored]
    diff_similarities = [p["similarity"] for p in diff_scored]

    results = {
        "model": model_name,
        "same_story": {
            "summary": summarize(same_similarities),
            "pairs": same_scored,
        },
        "different_story": {
            "summary": summarize(diff_similarities),
            "pairs": diff_scored,
        },
        "suggested_threshold": best_threshold(same_similarities, diff_similarities),
    }

    out_path.write_text(json.dumps(results, indent=2), encoding="utf-8")
    log.info("Wrote results -> %s", out_path)

    log.info("Same-story similarity:      mean=%.3f median=%.3f p10=%.3f p90=%.3f",
              results["same_story"]["summary"]["mean"], results["same_story"]["summary"]["median"],
              results["same_story"]["summary"]["p10"], results["same_story"]["summary"]["p90"])
    log.info("Different-story similarity: mean=%.3f median=%.3f p10=%.3f p90=%.3f",
              results["different_story"]["summary"]["mean"], results["different_story"]["summary"]["median"],
              results["different_story"]["summary"]["p10"], results["different_story"]["summary"]["p90"])
    log.info("Suggested threshold: %.3f (balanced accuracy=%.3f, tpr=%.3f, tnr=%.3f)",
              results["suggested_threshold"]["threshold"], results["suggested_threshold"]["balanced_accuracy"],
              results["suggested_threshold"]["tpr"], results["suggested_threshold"]["tnr"])


if __name__ == "__main__":
    main()
