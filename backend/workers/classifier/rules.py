"""
Rule layer on top of the banking topic model.

Why: the model is trained on public data only (tweets + Reuters), which has
no bank-supervision labels. On real regulator content it falls back to
surface cues, e.g. every Federal Reserve release becomes
`monetary_policy_rates` and an FCA anti-money-laundering fine becomes
`other`. These rules correct that with explicit, auditable patterns
instead of more training data.

How:
  * Each rule is a named regex with a fixed confidence. A match ADDS its
    topic (final score = max(model, rule)); rules never lower a score.
  * Title matches are trusted more than lead-only matches.
  * Source-aware corrections for REGULATOR / CENTRAL_BANK sources:
    `monetary_policy_rates` survives only if the text actually talks about
    monetary policy; otherwise it is replaced by regulation.
  * `other` is dropped when any substantive topic was found.

apply_rules() returns the merged topics plus the names of the rules that
fired, so the worker can log why a topic was added.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional

REGULATION = "regulation_legal_enforcement"
MONETARY = "monetary_policy_rates"
MNA = "m_and_a_investments"
EARNINGS = "earnings_results"
ANALYST = "analyst_ratings"
DIVIDENDS = "dividends_buybacks"
LEADERSHIP = "leadership_personnel"
OTHER = "other"

OFFICIAL_CATEGORIES = {"REGULATOR", "CENTRAL_BANK"}


@dataclass(frozen=True)
class Rule:
    name: str
    topic: str
    pattern: re.Pattern
    title_score: float
    lead_score: float
    # Lead-only matches needed before the rule fires (broad vocabularies need >1).
    lead_min_matches: int = 1


def _rx(*alternatives: str) -> re.Pattern:
    return re.compile(r"\b(?:" + "|".join(alternatives) + r")\b", re.IGNORECASE)


RULES: list[Rule] = [
    # Tuned against false positives on the public TFNT test set: bare "fine"
    # (adjective), geopolitical "sanctions", "compliance" (listing notices),
    # "to buy" (analyst upgrades) and UK "Regulatory Announcement" boilerplate.
    Rule("enforcement_action", REGULATION, _rx(
        r"fine[sd]", r"penalt(?:y|ies)", r"consent order", r"cease[- ]and[- ]desist",
        r"enforcement action", r"civil money penalty", r"settle(?:s|d|ment)? (?:charges|claims|allegations)",
        r"charged? with", r"indict(?:ed|ment)", r"sentenced", r"pleads? guilty", r"found guilty", r"lawsuit",
        r"sue[sd]?", r"class action", r"probe", r"investigat(?:ion|es|ed|ing)", r"subpoena", r"banned",
        r"prohibition order", r"disgorge(?:ment)?", r"restitution", r"censure[sd]?",
    ), 0.90, 0.70),
    Rule("financial_crime", REGULATION, _rx(
        r"anti[- ]money[- ]laundering", r"AML", r"money laundering", r"KYC", r"sanctions (?:evasion|violations?|breach(?:es)?)",
        r"OFAC", r"fraud(?:ulent)?", r"bribery", r"insider trading", r"market abuse", r"ransomware", r"data breach",
    ), 0.85, 0.65),
    Rule("supervision_rulemaking", REGULATION, _rx(
        r"regulators?", r"regulat(?:ion|ions)(?! announcement)", r"regulatory(?! announcement)",
        r"supervis(?:ion|ory|or)", r"rulemaking", r"final rule", r"proposed rule",
        r"consultation paper", r"consult(?:s|ation|ing)? on (?:\w+ ){0,3}(?:rules?|requirements?|regulation|regime|framework|reporting|capital|liquidity|disclosures?|standards?)", r"(?:supervisory|regulatory) guidance", r"stress tests?",
        r"capital (?:requirements?|rules?|buffers?)", r"Basel (?:III|IV|3|4)?",
        r"liquidity (?:rules?|requirements?|coverage)", r"resolution plans?", r"living wills?", r"watchdog",
        r"consumer protection", r"swipe[- ]fees?", r"interchange", r"open banking rule", r"Section 1033",
        r"CRA rule", r"deposit insurance", r"public comment", r"comment period", r"requests? (?:for )?comment",
        r"interagency", r"proposals? to (?:amend|revise|update|simplify)",
    ), 0.75, 0.55, lead_min_matches=2),
    # Non-regulatory gaps seen on real articles (e.g. "JPMorgan profit beats
    # estimates" classified as company news). Phrases, not single words.
    Rule("earnings_report", EARNINGS, _rx(
        r"(?:first|second|third|fourth)[- ]quarter (?:profit|earnings|results|revenue|net income|loss)",
        r"(?:quarterly|Q[1-4]|full[- ]year|annual|half[- ]year(?:ly)?) (?:profit|earnings|results|revenue|net income|loss)",
        r"(?:profit|earnings|EPS|revenue|net income) (?:beats?|miss(?:es)?|tops?|rises?|falls?|jumps?|surges?|slumps?|drops?|climbs?|declines?)",
        r"(?:beats?|miss(?:es)?|tops?) (?:analysts?'? )?(?:estimates|expectations|forecasts)",
        r"net interest income", r"reports? (?:quarterly |record )?(?:results|earnings)", r"earnings (?:call|season|report)",
    ), 0.85, 0.65, lead_min_matches=2),     # a single passing mention in a market wrap is not an earnings story
    Rule("analyst_rating", ANALYST, _rx(
        r"(?:upgrade[sd]?|downgrade[sd]?) .{0,40}?\b(?:to|from) (?:buy|sell|hold|neutral|overweight|underweight|outperform|underperform|equal[- ]weight|market perform)",
        r"price target", r"initiates? coverage", r"(?:overweight|underweight|outperform|underperform) rating",
        r"(?:Moody's|S&P|Fitch) (?:upgrade[sd]?|downgrade[sd]?|affirm(?:s|ed)?|cuts?|raises?)",
    ), 0.85, 0.60),
    Rule("dividend_buyback", DIVIDENDS, _rx(
        r"declares? (?:a |its )?(?:quarterly |special |interim |final )?(?:cash )?dividend",
        r"dividend (?:increase|hike|cut|raise)", r"(?:raises?|lifts?|cuts?|boosts?|increases?) (?:its )?(?:quarterly )?dividend",
        r"share (?:buyback|repurchase)s?", r"buyback program(?:me)?", r"stock repurchase",
    ), 0.85, 0.60),
    Rule("leadership_change", LEADERSHIP, _rx(
        r"(?:names?|named|appoints?|appointed|hires?|hired|taps?|tapped|promotes?|promoted) .{0,50}?\b(?:CEO|chief executive|CFO|chief financial officer|COO|chair(?:man|woman)?|president|head of)",
        r"(?:CEO|chief executive|CFO|chair(?:man|woman)?|president|head)\b.{0,40}?\b(?:to )?(?:steps? down|stepping down|resigns?|resigned|retires?|retiring|departs?|exits?|ousted)",
        r"named (?:as )?(?:its |his |her )?successor", r"names? (?:a )?successor",
        r"steps? down as", r"succeed(?:s|ed)? .{0,40}?\bas (?:CEO|chief executive|CFO|chair)",
    ), 0.85, 0.60),
    Rule("deal_approval", MNA, _rx(
        r"approv(?:al|es|ed) (?:of )?(?:the |an |two |\w+ )?(?:applications?|merger|acquisition|proposal) ",
        r"application[s]? (?:by|from) .{0,80}? to (?:acquire|merge|become)",
        r"(?:agrees?|agreed|deal|offer|bid|plans?|move|set) to (?:acquire|buy(?! ?back)|merge with)",
        r"to acquire",
    ), 0.80, 0.60),
]

MONETARY_TERMS = _rx(
    r"monetary policy", r"FOMC", r"interest rates?", r"policy rates?", r"rate (?:cut|hike|decision)s?",
    r"federal funds", r"inflation", r"basis points", r"quantitative (?:easing|tightening)", r"balance sheet runoff",
    r"deposit facility", r"forward guidance", r"price stability", r"QT", r"APF", r"asset purchases?",
    r"Monetary Policy Committee", r"MPC", r"Governing Council",
)


# Sources whose content is financial by construction. Elsewhere (general news,
# unknown source) regulation rules also need a financial-context term, so that
# political "probe" / "fraud" / "fines" stories do not become bank enforcement.
FINANCIAL_SOURCES = {"TRADE_PRESS", "REGULATOR", "CENTRAL_BANK", "FILING"}
FINANCE_CONTEXT = _rx(
    r"banks?", r"banking", r"bankers?", r"lenders?", r"credit unions?", r"fintechs?", r"crypto\w*", r"exchanges?",
    r"brokers?", r"broker-dealers?", r"insurers?", r"payments?", r"cards?", r"loans?", r"lending", r"mortgages?",
    r"deposits?", r"funds?", r"asset managers?", r"investors?", r"securities", r"shares", r"stocks?", r"bonds?",
    r"Fed", r"Federal Reserve", r"ECB", r"SEC", r"FCA", r"PRA", r"FDIC", r"OCC", r"CFPB", r"FINRA", r"Binance",
    r"Wall Street", r"financial (?:firms?|institutions?|services|regulators?)", r"\$[A-Z]{1,5}",
)


def _rule_hits(title: str, lead: str, source_category: Optional[str] = None) -> list[tuple[Rule, float]]:
    context_ok = source_category in FINANCIAL_SOURCES or bool(FINANCE_CONTEXT.search(f"{title} {lead}"))
    hits = []
    for rule in RULES:
        if rule.topic == REGULATION and not context_ok:
            continue
        if rule.pattern.search(title):
            hits.append((rule, rule.title_score))
        elif lead and len(rule.pattern.findall(lead)) >= rule.lead_min_matches:
            hits.append((rule, rule.lead_score))
    return hits


def apply_rules(
    model_topics: list[tuple[str, float]],
    title: str,
    lead: str = "",
    source_category: Optional[str] = None,
) -> tuple[list[tuple[str, float]], list[str]]:
    """Merge model output with rule hits. Returns (topics highest-first, fired rule names)."""
    scores: dict[str, float] = dict(model_topics)
    fired: list[str] = []

    for rule, score in _rule_hits(title, lead, source_category):
        if score > scores.get(rule.topic, 0.0):
            scores[rule.topic] = score
        fired.append(rule.name)

    # Deal approvals by a regulator are also regulatory events.
    if "deal_approval" in fired and source_category in OFFICIAL_CATEGORIES:
        scores[REGULATION] = max(scores.get(REGULATION, 0.0), 0.75)
        fired.append("official_deal_approval")

    # Regulators and central banks publish far more than rate decisions; the
    # model's "Fed/ECB => monetary policy" shortcut is only kept when the text
    # is actually about monetary policy.
    # For a regulator that means regulation; a central bank also publishes
    # speeches, banknote news and statistics, so there the wrong monetary tag
    # is only removed (live run: "four animals on our new banknotes" had
    # become regulation).
    if source_category in OFFICIAL_CATEGORIES and MONETARY in scores:
        if not MONETARY_TERMS.search(f"{title} {lead}"):
            scores.pop(MONETARY)
            if source_category == "REGULATOR":
                scores[REGULATION] = max(scores.get(REGULATION, 0.0), 0.70)
            elif not scores:
                scores[OTHER] = 0.5
            fired.append("official_source_not_monetary")

    # A regulator's own publication is regulatory unless something else won.
    if source_category == "REGULATOR" and not (set(scores) - {OTHER}):
        scores[REGULATION] = 0.60
        fired.append("regulator_source_default")

    # Symmetric case: a central bank's own release whose title is about
    # monetary policy (rate decisions, QT/APF, MPC remit) is monetary even if
    # the model missed it (live run: a QT/APF letter came out company news).
    if source_category == "CENTRAL_BANK" and MONETARY_TERMS.search(title):
        if scores.get(MONETARY, 0.0) < 0.8:
            scores[MONETARY] = 0.8
            fired.append("central_bank_monetary_title")

    if len(scores) > 1 and OTHER in scores:
        scores.pop(OTHER)

    merged = sorted(((t, round(s, 4)) for t, s in scores.items()), key=lambda t: t[1], reverse=True)
    return merged, fired
