"""
Attach-or-create decision for one article against candidate stories.

Measured on the first 140 real articles (see README, "Clustering"):
  * 227 of 291 above-threshold pairs were > 3 days apart (e.g. the BoE's
    July and November rate decisions, similarity 0.90, 99 days apart), and
    recency used to be judged by when a story was last *processed*, not by
    when its articles were *published*.
  * Official releases are formulaic ("Minutes of the ... Committee") and
    look alike across publishers; the genuine same-event official pair
    (ECB decision + press statement, same day) scored 0.83, while ECB vs
    FOMC statements scored 0.63.
  * 28 of 31 above-threshold pairs where both sides named banks/fintechs
    named entirely different ones.

Rules, in order (the first failing one rejects the candidate):
  1. date window: the article's publish date must be within WINDOW of the
     story's [first, last] article publish dates;
  2. two official releases (regulator / central bank) join only if from the
     same publisher and at or above the stricter official threshold; any
     other pair needs the base threshold;
  3. company guard: if both article and story name banks/fintechs, they
     must share at least one.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Optional


@dataclass(frozen=True)
class ArticleView:
    published_at: datetime
    source: str
    official: bool                       # REGULATOR or CENTRAL_BANK source
    companies: frozenset[str] = frozenset()   # bank/fintech company ids only


@dataclass
class StoryView:
    story_id: str
    score: float                         # cosine similarity to the story centroid
    first_published: datetime
    last_published: datetime
    sources: set[str] = field(default_factory=set)
    official_only: bool = False          # every article so far came from an official source
    companies: set[str] = field(default_factory=set)


@dataclass(frozen=True)
class Policy:
    # 0.60: on SemEval almost the same as the balanced-accuracy optimum 0.533
    # (TPR 0.948 -> 0.914, TNR 0.802 -> 0.815), but on real financial news it
    # removed all 6 thematic false merges seen at 0.534-0.584.
    base_threshold: float = 0.60
    official_threshold: float = 0.75
    window: timedelta = timedelta(days=3)


def accept(article: ArticleView, story: StoryView, policy: Policy) -> tuple[bool, str]:
    """Return (accepted, reason). The reason is logged for rejected candidates."""
    if article.published_at < story.first_published - policy.window:
        return False, "published too long before the story"
    if article.published_at > story.last_published + policy.window:
        return False, "published too long after the story"

    if article.official and story.official_only:
        if article.source not in story.sources:
            return False, "official release from a different publisher"
        if story.score < policy.official_threshold:
            return False, f"official pair below {policy.official_threshold}"
    elif story.score < policy.base_threshold:
        return False, f"below {policy.base_threshold}"

    if article.companies and story.companies and not (article.companies & story.companies):
        return False, "no shared bank/fintech"
    return True, "accepted"


def choose(article: ArticleView, candidates: list[StoryView], policy: Policy) -> tuple[Optional[StoryView], list[str]]:
    """First acceptable candidate in similarity order, plus the rejection reasons seen."""
    reasons = []
    for story in sorted(candidates, key=lambda s: s.score, reverse=True):
        ok, reason = accept(article, story, policy)
        if ok:
            return story, reasons
        reasons.append(f"{story.story_id[:8]}@{story.score:.2f}: {reason}")
    return None, reasons


def merged_payload(story: StoryView, article: ArticleView, article_count: int) -> dict:
    """Story payload after attaching the article."""
    return {
        "storyId": story.story_id,
        "articleCount": article_count,
        "firstPublishedAt": min(story.first_published, article.published_at).isoformat(),
        "lastPublishedAt": max(story.last_published, article.published_at).isoformat(),
        "sources": sorted(story.sources | {article.source}),
        "officialOnly": story.official_only and article.official,
        "companies": sorted(story.companies | set(article.companies)),
    }


def new_payload(story_id: str, article: ArticleView) -> dict:
    return {
        "storyId": story_id,
        "articleCount": 1,
        "firstPublishedAt": article.published_at.isoformat(),
        "lastPublishedAt": article.published_at.isoformat(),
        "sources": [article.source],
        "officialOnly": article.official,
        "companies": sorted(article.companies),
    }
