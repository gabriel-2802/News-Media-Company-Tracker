"""
build_validation_pairs.py

Builds similar_news.json and different_news.json — English-only article
pairs for validating embedding-based clustering quality (see validate.py).

Two sources, merged and deduped into the same two output files:

  1. SemEval-2022 Task 8 (Multilingual News Article Similarity)
     ~27k human-annotated pairs, graded 1 (most similar) to 4 (least
     similar) on whether two articles cover the same substantive story.
     The dataset itself only distributes article URLs + scores, not text
     (copyright) — actual text must be fetched from the Internet Archive.

  2. AllSides.com "story" groupings (best-effort scraper)
     AllSides groups articles from left/center/right outlets covering the
     same story. Within a group -> same_story pairs. Across unrelated
     groups -> different_story pairs.

IMPORTANT — run this on your own machine, not in a sandboxed environment:
  - Step 1 needs network access to archive.org (Internet Archive).
  - Step 2 needs network access to allsides.com.
  Neither is reachable from restricted/allowlisted network environments.
  I have not been able to execute this script end-to-end myself for that
  reason — review it before running at scale, and validate against a
  small sample first.

Also worth knowing before running: AllSides scraping should respect their
robots.txt and terms of use. Their page structure can change without
notice, so the selectors in `scrape_allsides()` are a starting point, not
guaranteed to keep working — inspect the live HTML if it returns nothing.

------------------------------------------------------------------------
Usage
------------------------------------------------------------------------

1) Get the SemEval-2022 Task 8 labeled CSV (urls + Overall score).
   It's distributed via Zenodo: https://zenodo.org/records/6507872
   Download the train/eval csv(s) and pass the path with --semeval-csv.
   Expected columns (per the official format): pair_id, url1_lang,
   url2_lang, link1, link2, ia_link1, ia_link2, OVERALL
   (extra columns like GEO/ENT/TIME/NAR/STYLE/TONE are ignored here but
   are in the file — that's fine).

2) Install dependencies:
     pip install requests trafilatura beautifulsoup4 tqdm

3) Run:
     python build_validation_pairs.py \
         --semeval-csv path/to/semeval_train.csv \
         --scrape-allsides \
         --out-dir ./output

Output: ./output/similar_news.json, ./output/different_news.json
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import itertools
import json
import logging
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable, Optional

import requests
import trafilatura
from bs4 import BeautifulSoup
from tqdm import tqdm

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-8s %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Shared pair representation
# ---------------------------------------------------------------------------


@dataclass
class ArticlePair:
    text_a: str
    text_b: str
    source: str          # "semeval" | "allsides"
    pair_id: str          # source-specific identifier, for traceability/debugging

    def dedupe_key(self) -> str:
        """Order-independent hash so (a,b) and (b,a) collapse to one entry,
        and near-identical merges across sources are caught."""
        norm = sorted([self.text_a.strip()[:500], self.text_b.strip()[:500]])
        return hashlib.sha256("||".join(norm).encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# Source 1: SemEval-2022 Task 8
# ---------------------------------------------------------------------------

# Overall score scale per the task: 1 = most similar (same story), 4 = least
# similar. These thresholds carve out the confident ends and drop the
# ambiguous middle — tune if you want more volume at the cost of noisier
# labels.
SEMEVAL_SAME_STORY_MAX = 2.0     # Overall <= this -> same_story_pairs
SEMEVAL_DIFF_STORY_MIN = 3.0     # Overall >= this -> different_story_pairs

_IA_FETCH_DELAY_SECONDS = 1.0    # be polite to the Internet Archive
_MIN_TEXT_CHARS = 200            # skip near-empty extractions (paywalls, stubs)


def _fetch_ia_text(ia_link: str, session: requests.Session) -> Optional[str]:
    """Fetch and extract article text from an Internet Archive snapshot URL."""
    try:
        resp = session.get(ia_link, timeout=15)
        resp.raise_for_status()
    except requests.RequestException as exc:
        log.warning("IA fetch failed (%s): %s", ia_link, exc)
        return None

    text = trafilatura.extract(resp.text)
    if not text or len(text) < _MIN_TEXT_CHARS:
        return None
    return text


def load_semeval_pairs(csv_path: Path, max_pairs: Optional[int] = None) -> tuple[list[ArticlePair], list[ArticlePair]]:
    """Read the SemEval labels CSV, filter to English-English pairs, fetch
    text via the Internet Archive, and bucket into same/different lists."""
    same: list[ArticlePair] = []
    different: list[ArticlePair] = []

    with open(csv_path, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    english_rows = [r for r in rows if r.get("url1_lang") == "en" and r.get("url2_lang") == "en"]
    log.info("SemEval: %d total rows, %d English-English pairs", len(rows), len(english_rows))

    if max_pairs:
        english_rows = english_rows[:max_pairs]

    session = requests.Session()
    session.headers.update({"User-Agent": "news-clustering-validation/1.0"})

    for row in tqdm(english_rows, desc="Fetching SemEval article text"):
        try:
            overall = float(row["OVERALL"])
        except (KeyError, ValueError):
            continue

        if not (overall <= SEMEVAL_SAME_STORY_MAX or overall >= SEMEVAL_DIFF_STORY_MIN):
            continue  # ambiguous middle, skip

        ia_link1 = row.get("ia_link1")
        ia_link2 = row.get("ia_link2")
        if not ia_link1 or not ia_link2:
            continue

        text_a = _fetch_ia_text(ia_link1, session)
        time.sleep(_IA_FETCH_DELAY_SECONDS)
        text_b = _fetch_ia_text(ia_link2, session)
        time.sleep(_IA_FETCH_DELAY_SECONDS)

        if not text_a or not text_b:
            continue

        pair = ArticlePair(text_a=text_a, text_b=text_b, source="semeval", pair_id=row["pair_id"])
        if overall <= SEMEVAL_SAME_STORY_MAX:
            same.append(pair)
        else:
            different.append(pair)

    log.info("SemEval: collected %d same-story, %d different-story pairs", len(same), len(different))
    return same, different


# ---------------------------------------------------------------------------
# Source 2: AllSides.com story groupings (best-effort)
# ---------------------------------------------------------------------------

ALLSIDES_STORY_INDEX_URL = "https://www.allsides.com/story/admin"  # story archive listing; verify current path before relying on it
_ALLSIDES_FETCH_DELAY_SECONDS = 1.5


def _get_soup(url: str, session: requests.Session) -> Optional[BeautifulSoup]:
    try:
        resp = session.get(url, timeout=15)
        resp.raise_for_status()
    except requests.RequestException as exc:
        log.warning("AllSides fetch failed (%s): %s", url, exc)
        return None
    return BeautifulSoup(resp.text, "html.parser")


def scrape_allsides(max_stories: int = 30) -> tuple[list[ArticlePair], list[ArticlePair]]:
    """Best-effort scraper for AllSides story groupings.

    NOTE: I could not test this against the live site from this environment
    (no network path to allsides.com here). The selectors below are a
    starting point based on the site's general structure — inspect the
    actual page HTML and adjust before relying on this. Respect
    allsides.com/robots.txt and their terms of use.
    """
    same: list[ArticlePair] = []
    different: list[ArticlePair] = []
    session = requests.Session()
    session.headers.update({"User-Agent": "news-clustering-validation/1.0"})

    index_soup = _get_soup(ALLSIDES_STORY_INDEX_URL, session)
    if index_soup is None:
        log.warning("Could not load AllSides story index — skipping AllSides source.")
        return same, different

    # Story links — adjust selector to match actual page structure.
    story_links = [a["href"] for a in index_soup.select("a[href*='/story/']")][:max_stories]
    story_groups: list[list[str]] = []  # each inner list = article texts for one story

    for link in tqdm(story_links, desc="Fetching AllSides stories"):
        story_soup = _get_soup(link, session)
        time.sleep(_ALLSIDES_FETCH_DELAY_SECONDS)
        if story_soup is None:
            continue

        # Each story page lists ~3 linked outlet articles (left/center/right).
        # Adjust selector to match actual page structure.
        article_links = [a["href"] for a in story_soup.select("a.news-title[href]")]
        texts = []
        for article_url in article_links:
            downloaded = trafilatura.fetch_url(article_url)
            if downloaded:
                text = trafilatura.extract(downloaded)
                if text and len(text) >= _MIN_TEXT_CHARS:
                    texts.append(text)
            time.sleep(_ALLSIDES_FETCH_DELAY_SECONDS)

        if len(texts) >= 2:
            story_groups.append(texts)

    # Same-story pairs: every combination within a group.
    for i, group in enumerate(story_groups):
        for text_a, text_b in itertools.combinations(group, 2):
            same.append(ArticlePair(text_a=text_a, text_b=text_b, source="allsides", pair_id=f"allsides_same_{i}"))

    # Different-story pairs: one article from each of two unrelated groups.
    for (i, group_a), (j, group_b) in itertools.combinations(enumerate(story_groups), 2):
        different.append(ArticlePair(
            text_a=group_a[0], text_b=group_b[0], source="allsides", pair_id=f"allsides_diff_{i}_{j}",
        ))

    log.info("AllSides: collected %d same-story, %d different-story pairs", len(same), len(different))
    return same, different


# ---------------------------------------------------------------------------
# Merge, dedupe, write
# ---------------------------------------------------------------------------


def merge_and_dedupe(*groups: Iterable[ArticlePair]) -> list[ArticlePair]:
    seen: set[str] = set()
    result: list[ArticlePair] = []
    for group in groups:
        for pair in group:
            key = pair.dedupe_key()
            if key in seen:
                continue
            seen.add(key)
            result.append(pair)
    return result


def write_json(pairs: list[ArticlePair], path: Path) -> None:
    path.write_text(json.dumps([asdict(p) for p in pairs], ensure_ascii=False, indent=2), encoding="utf-8")
    log.info("Wrote %d pairs -> %s", len(pairs), path)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--semeval-csv", type=Path, default=None, help="Path to downloaded SemEval-2022 Task 8 labels CSV")
    parser.add_argument("--semeval-max-pairs", type=int, default=None, help="Cap SemEval rows processed (useful for a quick test run)")
    parser.add_argument("--scrape-allsides", action="store_true", help="Also scrape AllSides.com story groupings")
    parser.add_argument("--allsides-max-stories", type=int, default=30)
    parser.add_argument("--out-dir", type=Path, default=Path("./output"))
    args = parser.parse_args()

    args.out_dir.mkdir(parents=True, exist_ok=True)

    same_groups: list[list[ArticlePair]] = []
    diff_groups: list[list[ArticlePair]] = []

    if args.semeval_csv:
        same, diff = load_semeval_pairs(args.semeval_csv, max_pairs=args.semeval_max_pairs)
        same_groups.append(same)
        diff_groups.append(diff)
    else:
        log.info("No --semeval-csv given, skipping SemEval source.")

    if args.scrape_allsides:
        same, diff = scrape_allsides(max_stories=args.allsides_max_stories)
        same_groups.append(same)
        diff_groups.append(diff)

    same_final = merge_and_dedupe(*same_groups)
    diff_final = merge_and_dedupe(*diff_groups)

    write_json(same_final, args.out_dir / "similar_news.json")
    write_json(diff_final, args.out_dir / "different_news.json")

    log.info("Done. same=%d different=%d", len(same_final), len(diff_final))


if __name__ == "__main__":
    main()