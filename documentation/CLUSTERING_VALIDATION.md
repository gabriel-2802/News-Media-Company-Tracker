# Embedding Validation Report

Validates the clustering worker's similarity approach against human-labeled article pairs, and compares 2 embedding models.

## Data

- **Source**: SemEval-2022 Task 8 (Multilingual News Article Similarity), `final_eval_data.csv` from [Zenodo record 6507872](https://zenodo.org/records/6507872), filtered to English-English pairs.
- **Labeling**: `OVERALL` score <= 2.0 => same story, >= 3.0 => different story (scores 2-3 are ambiguous and excluded).
- **Text**: fetched from Internet Archive snapshots (`ia_link1`/`ia_link2`) via `trafilatura`(../build_validation_pairs.py).
- **Yield**: 236 candidate English-English pairs => 197 usable pairs after IA fetch failures (dead snapshots, timeouts, a few 403/429s : normal attrition for 2020-era URLs):
  - `similar_news.json` : 116 same-story pairs
  -`different_news.json` : 81 different-story pairs
- AllSides.com scraping was attempted but the site blocks non-browser requests (403) from this environment; not included in this validation set.

## Method

[`validate.py`](../validate.py) embeds `text_a` and `text_b` of every pair (truncated to 4000 chars, see caveat below), computes cosine similarity, and:
- reports summary stats (mean/median/percentiles) per label
- sweeps all observed similarity values to find the threshold maximizing balanced accuracy (avg of true-positive rate on same-story pairs and true-negative rate on different-story pairs)

Two models were run, each writing to its own file so results are never overwritten:

| Model | Params | Results file |
|---|---|---|
| `Qwen/Qwen3-Embedding-0.6B` (production, [`embedder.py`](../../embedder.py)) | 0.6B | `embedding_validation_results_Qwen_Qwen3-Embedding-0.6B.json` |
| `sentence-transformers/all-MiniLM-L6-v2` (comparison) | 22M | `embedding_validation_results_sentence-transformers_all-MiniLM-L6-v2.json` |

## Results

| | Qwen3-Embedding-0.6B | all-MiniLM-L6-v2 |
|---|---|---|
| Same-story similarity (mean / median) | 0.830 / 0.862 | 0.785 / 0.816 |
| Different-story similarity (mean / median) | 0.341 / 0.269 | 0.292 / 0.224 |
| Suggested threshold | **0.638** | 0.533 |
| Balanced accuracy at threshold | **90.8%** | 88.0% |
| True-positive rate (same-story caught) | 94.0% | 95.7% |
| True-negative rate (different-story rejected) | **87.7%** | 80.2% |

## Findings

- Both models cleanly separate the two distributions : same-story pairs cluster high, different-story pairs cluster low : validating cosine similarity as a workable signal for the clustering worker's threshold decision (Step 6 in `instructions.md`).
- Qwen3-Embedding-0.6B (production) beats MiniLM on overall balanced accuracy (90.8% vs 88.0%), driven almost entirely by better rejection of unrelated pairs (TNR 87.7% vs 80.2%). MiniLM is marginally *more* sensitive to true matches (TPR 95.7% vs 94.0%) but lets more false merges through.
- **Tradeoff**: MiniLM is ~27x smaller and correspondingly cheaper/faster to run at the per-article embedding step (the "slow step" noted in `instructions.md` §6). If the clustering worker's cost/latency budget is tight, MiniLM's ~3-point accuracy hit may be acceptable : but if false story-merges are the costlier failure mode in practice, Qwen3-Embedding-0.6B's better TNR is worth the extra compute.
- The distributions still overlap in the middle (different-story p90 of 0.688-0.669 vs same-story p10 of 0.678-0.615 depending on model) : an inherent ambiguous zone, consistent with SemEval's own decision to exclude mid-range scores as unreliable. Expect some real-world borderline cases regardless of model choice.

## Caveats

- **Text truncation**: article text is capped at 4000 characters before embedding. The model's `max_seq_length` is 32768 tokens with no lower automatic truncation, so a small number of unusually long scraped articles (tens of thousands of characters) were driving quadratic self-attention memory into the tens-of-GB range on CPU/MPS during an early run. The cap avoids that; it may lose signal for stories that only diverge deep into long articles, which the first ~4000 characters usually cover for hard-news leads.
- **Extraction noise**: a manual spot-check found at least one same-story pair where `trafilatura` extracted mostly site chrome/boilerplate ("Lifetime / Expired / Redeemed... TimesPoints") rather than article body : worth a broader spot-check before treating this set as fully clean ground truth.
- **Single source, English-only, 2020-era news**: SemEval's article set skews toward COVID-19-era stories and doesn't include AllSides' modern left/center/right framing angle, which `instructions.md` §8 (near-duplicate vs. distinct-report) specifically cares about. Treat the suggested threshold as a starting point to revisit once real traffic accumulates, per §11.


---

## Re-validation on financial news (2026-11-11)

**Data:** the first 140 real articles scraped from the banking sources (Bank of England, Federal Reserve, ECB, BIS, Guardian banking, DW business, CoinDesk), published 2026-07-13 to 2026-11-11. No human labels; pairs were inspected by hand.

**Findings (MiniLM, title + body ≤ 4000 chars, all pairs ≥ 0.533):**

| | Pairs |
| --- | --- |
| Both official releases, published ≤ 3 days apart | 28 |
| Both official releases, > 3 days apart | 193 |
| At least one press article, ≤ 3 days apart | 36 |
| At least one press article, > 3 days apart | 34 |

- 227 of 291 above-threshold pairs were more than 3 days apart (e.g. BoE July vs November rate decisions, 0.90, 99 days apart). The old recency filter used the story's *processing* time, so a first scrape of a feed's back catalogue merged months of releases.
- The genuine same-event official pair (ECB "Monetary policy decisions" + Lagarde's statement, same day) scored 0.83; cross-publisher official pairs (ECB vs FOMC 0.63, BoE MPC remit vs FOMC 0.62) and formulaic same-publisher minutes (≈ 0.60–0.70) were not the same event.
- Of 31 above-threshold pairs where both articles named banks/fintechs, 28 named entirely different ones.
- After the date window and official-release rules, the remaining false merges were press pairs at 0.534–0.584 (e.g. "Can Germany revive its economy…" / "Why US investors are snapping up Germany's Mittelstand"); true merges were ≥ 0.60 apart from two borderline ones (0.541, 0.560).

**Current rules** (`backend/workers/clusterer/decision.py`): publish-date window of 3 days around a story's articles; base threshold 0.60 (on the SemEval pairs: TPR 0.914, TNR 0.815, vs 0.948 / 0.802 at 0.533); two official releases only from the same publisher and ≥ 0.75; if both sides name banks/fintechs, at least one shared; top 5 candidates considered.

**Result on the same 140 articles:** 129 stories (was 48), biggest 6 articles (was 21), no story spanning more than 1 day (was up to 111), and every remaining multi-article story is a plausible single event or news cycle (ECB decision + statement; same-day Bitcoin/CPI coverage; CFTC legal fight; consecutive FX-reserve notices).

**Still to do:** a hand-labelled set of financial article pairs would turn these inspections into measured precision/recall.
