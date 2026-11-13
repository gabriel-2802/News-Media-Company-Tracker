"""
Dictionary-based entity linking of articles to the company universe.

Surface forms = each company's name + aliases (from GET /api/companies).
One compiled regex per matching policy finds them; overlapping hits resolve
to the earliest, then longest, match.

Precision rules (the universe mixes household brands with ordinary words):
  * Acronyms and camel-case brands (SEC, ING, BofA, SoFi) match case-sensitively.
  * Brands that are ordinary words or places (Chase, Block, Affirm, Wise,
    Halifax, ...) match case-sensitively AND only if the article has
    financial context.
  * A surface form that belongs to more than one company is dropped as
    ambiguous (e.g. "First Bancorp" is both FBP and FBNC).
  * A surface form made only of generic legal words ("Bancorp, Inc.",
    "Financial Group") is dropped.
  * Everything else matches case-insensitively on word boundaries.

Why no NER model: with a curated alias dictionary, fuzzy matching of NER
spans mostly adds false links; spans the dictionary misses are almost
always companies outside the universe anyway.
"""
from __future__ import annotations

import re
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Iterable, Optional

AMBIGUOUS_WORDS = {
    "affirm", "ally", "block", "chase", "chime", "circle", "halifax", "huntington", "key", "merrill",
    "regions", "square", "stripe", "upstart", "visa", "wise", "goldman", "fed",
}
GENERIC_TOKENS = {
    "the", "of", "and", "&", "bank", "banks", "banc", "bancorp", "bancorporation", "bancshares", "bankshares",
    "financial", "trust", "group", "holding", "holdings", "inc", "inc.", "corp", "corp.", "corporation", "co",
    "co.", "company", "plc", "ltd", "ltd.", "limited", "n.a.", "na", "capital", "services", "national",
    "association", "sa", "s.a.", "ag", "se", "nv", "n.v.", "spa", "s.p.a.",
}
# Ordinary words that many US bank names are built from. A name made only of
# GENERIC/COMMON words ("Financial Institutions", "First National") reads as
# plain English: such "weak" names match case-sensitively, and only if they
# also contain a corporate word ("Northern Trust", "Citizens Bank" stay;
# "Financial Institutions" — 8 false links in the first live run — is skipped).
COMMON_TOKENS = {
    "institutions", "institution", "first", "united", "american", "community", "security", "savings", "federal",
    "investors", "citizens", "peoples", "people's", "home", "mutual", "commerce", "commercial", "merchants",
    "farmers", "union", "city", "state", "county", "republic", "south", "north", "east", "west", "southern",
    "northern", "eastern", "western", "central", "pacific", "atlantic", "great", "independent", "enterprise",
    "premier", "preferred",
}
CORPORATE_TOKENS = {
    "bank", "bancorp", "bancorporation", "bancshares", "bankshares", "banc", "trust", "corp", "corp.",
    "corporation", "inc", "inc.", "co", "co.", "company", "plc", "holdings", "group",
}
FINANCE_CONTEXT = re.compile(
    r"\b(?:banks?|banking|lenders?|loans?|lending|deposits?|credit|fintech|payments?|cards?|mortgages?|savings|"
    r"interest rates?|accounts?|"
    r"shares?|stocks?|investors?|earnings|revenue|profit|regulators?|SEC|FDIC|Fed|ECB|"
    r"acquisition|merger|IPO|valuation|fees?|customers?)\b",
    re.IGNORECASE,
)
LEAD_CHARS = 300
_PRECEDING_NAME = re.compile(r"(?<![.!?:]\s)(?<!^)\b[A-Z][\w&.'-]*\s$")
_ACRONYM = re.compile(r"^(?:the )?[A-Z0-9&.\-]{2,6}$|^[A-Z][a-z]*[A-Z][A-Za-z]*$")


@dataclass(frozen=True)
class Company:
    id: str
    name: str
    aliases: tuple[str, ...] = ()


@dataclass
class Mention:
    company_id: str
    count: int = 0
    in_title: bool = False
    first_offset: Optional[int] = None          # offset of first body match
    sentences: list[str] = field(default_factory=list)

    def salience(self) -> float:
        """0.6 for a title mention, +0.25 if named in the lead, +0.1 per extra mention (max +0.3)."""
        score = 0.6 if self.in_title else 0.0
        if self.first_offset is not None and self.first_offset < LEAD_CHARS:
            score += 0.25
        score += min(0.3, 0.1 * self.count)
        return round(min(1.0, max(0.1, score)), 3)


def _tokens(form: str) -> list[str]:
    return [t.strip(",.()").lower() for t in form.split() if t.strip(",.()")]


def _is_generic(form: str) -> bool:
    return all(t in GENERIC_TOKENS for t in _tokens(form))


def _is_weak(form: str) -> bool:
    """Made only of generic/common English words (see COMMON_TOKENS)."""
    return all(t in GENERIC_TOKENS or t in COMMON_TOKENS for t in _tokens(form))


def _pattern(forms: Iterable[str], flags: int) -> Optional[re.Pattern]:
    ordered = sorted(set(forms), key=len, reverse=True)       # longest first within one alternation
    if not ordered:
        return None
    alternation = "|".join(re.escape(f) for f in ordered)
    return re.compile(rf"(?<![\w&])(?:{alternation})(?![\w&])", flags)


class EntityLinker:
    def __init__(self, companies: Iterable[Company]) -> None:
        owners: dict[str, set[str]] = defaultdict(set)
        display: dict[str, str] = {}
        for c in companies:
            for form in (c.name, *c.aliases):
                form = form.strip()
                if len(form) < 2 or _is_generic(form):
                    continue
                if _is_weak(form) and not any(t in CORPORATE_TOKENS for t in _tokens(form)):
                    continue
                owners[form.lower()].add(c.id)
                display.setdefault(form.lower(), form)

        self.ambiguous = sorted(display[k] for k, ids in owners.items() if len(ids) > 1)
        unique = {display[k]: next(iter(ids)) for k, ids in owners.items() if len(ids) == 1}

        self._owner: dict[str, str] = {}
        self._weak: set[str] = set()            # companies matched through a common-words name
        exact, contextual, insensitive = [], [], []
        for form, company_id in unique.items():
            bare = re.sub(r"^the ", "", form, flags=re.IGNORECASE)
            if bare.lower() in AMBIGUOUS_WORDS:
                contextual.append(form)
                self._owner[form] = company_id
                if form.startswith("the "):                       # "the Fed" also at a sentence start
                    contextual.append("T" + form[1:])
                    self._owner["T" + form[1:]] = company_id
            elif _ACRONYM.match(form) or _is_weak(form):
                exact.append(form)
                self._owner[form] = company_id
                if _is_weak(form):
                    self._weak.add(company_id)
            else:
                insensitive.append(form)
                self._owner[form.lower()] = company_id

        self._exact = _pattern(exact, 0)
        self._contextual = _pattern(contextual, 0)
        self._insensitive = _pattern(insensitive, re.IGNORECASE)

    def _hits(self, text: str, finance_context: bool) -> list[tuple[int, int, str]]:
        hits = []
        for pattern, case_sensitive in ((self._exact, True), (self._insensitive, False),
                                        (self._contextual if finance_context else None, True)):
            if pattern is None:
                continue
            for m in pattern.finditer(text):
                key = m.group(0) if case_sensitive else m.group(0).lower()
                company_id = self._owner.get(key)
                if company_id and case_sensitive and company_id in self._weak and _PRECEDING_NAME.search(text, 0, m.start()):
                    # "New York Community Bancorp" is not "Community Bancorp": a common-words
                    # name right after a capitalised word is the tail of a longer, unknown name.
                    continue
                if company_id:
                    hits.append((m.start(), m.end(), company_id))
        # Earliest first, then longest; drop hits overlapping an accepted one.
        hits.sort(key=lambda h: (h[0], -(h[1] - h[0])))
        accepted, last_end = [], -1
        for start, end, cid in hits:
            if start >= last_end:
                accepted.append((start, end, cid))
                last_end = end
        return accepted

    def link(self, title: str, body: str) -> list[Mention]:
        context = bool(FINANCE_CONTEXT.search(f"{title} {body[:2000]}"))
        mentions: dict[str, Mention] = {}

        for _, _, cid in self._hits(title, context):
            m = mentions.setdefault(cid, Mention(cid))
            m.in_title = True
            m.count += 1
            if title not in m.sentences:
                m.sentences.append(title)

        for start, _, cid in self._hits(body, context):
            m = mentions.setdefault(cid, Mention(cid))
            m.count += 1
            if m.first_offset is None:
                m.first_offset = start
            sentence = _sentence_at(body, start)
            if len(m.sentences) < 5 and sentence not in m.sentences:
                m.sentences.append(sentence)

        return sorted(mentions.values(), key=lambda m: m.salience(), reverse=True)


def _sentence_at(text: str, offset: int) -> str:
    start = max(text.rfind(". ", 0, offset), text.rfind("\n", 0, offset)) + 1
    end_candidates = [i for i in (text.find(". ", offset), text.find("\n", offset)) if i != -1]
    end = min(end_candidates) + 1 if end_candidates else len(text)
    return text[start:end].strip()[:400]
