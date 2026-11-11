"""
RabbitMQ-driven enrichment worker.

Consumes `article.enrich` (a third fan-out of the scraper's `article.saved`
event, next to `article.classify` and `article.cluster`). For each article:

  1. Fetch title + body from the Provider.
  2. Link company mentions against the company universe (enricher/linker.py).
  3. Score FinBERT sentiment for the article (title + lead) and for each
     company (mean over the sentences that mention it).
  4. PATCH /api/articles/enrichment. The Provider stores sentiment and
     MENTIONS and refreshes the Story-[:AFFECTS]->Company events.

The universe comes from GET /api/companies at startup and is refreshed every
UNIVERSE_REFRESH_MINUTES, or immediately if the Provider rejects a company id.

Run (from workers/):
    make enrich-worker
    # or: enricher/venv/bin/python -m enricher.enricher_worker
"""
from __future__ import annotations

import json
import logging
import os
import time
from typing import Optional

import pika
import pika.channel
import pika.exceptions
import pika.spec

from entity_linker import Company, EntityLinker
from enricher.sentiment import LEAD_SENTENCES, SentimentModel, article_sentiment, sentences, weighted_mean
from env_config import require_env
from log_config import configure_logging
from provider_client import ProviderClient, ProviderError
from retry import call_with_retry

RABBITMQ_URL = require_env("RABBITMQ_URL")
PROVIDER_URL = require_env("PROVIDER_URL")
ARTICLE_ENRICH_QUEUE = os.environ.get("ARTICLE_ENRICH_QUEUE", "article.enrich")
UNIVERSE_REFRESH_SECONDS = 60 * float(os.environ.get("UNIVERSE_REFRESH_MINUTES", "60"))
LEAD_CHARS = 600

configure_logging()
log = logging.getLogger(__name__)


class EnrichmentHandler:
    def __init__(self, provider: ProviderClient) -> None:
        self._provider = provider
        self._sentiment = SentimentModel.get_instance()
        self._linker: Optional[EntityLinker] = None
        self._names: dict[str, str] = {}
        self._loaded_at = 0.0
        self._refresh_universe()

    def _refresh_universe(self) -> None:
        companies = self._provider.get_companies()
        self._linker = EntityLinker(Company(c.id, c.name, c.aliases) for c in companies)
        self._names = {c.id: c.name for c in companies}
        self._loaded_at = time.monotonic()
        log.info("Company universe loaded: %d companies (%d ambiguous forms skipped)",
                 len(companies), len(self._linker.ambiguous))

    def handle(self, method: pika.spec.Basic.Deliver, body: bytes, channel: pika.channel.Channel) -> None:
        try:
            url: str = json.loads(body)["url"]
        except (json.JSONDecodeError, KeyError) as exc:
            log.error("Malformed message, discarding: %r — %s", body, exc)
            channel.basic_ack(delivery_tag=method.delivery_tag)
            return

        if time.monotonic() - self._loaded_at > UNIVERSE_REFRESH_SECONDS:
            self._refresh_universe()

        article = self._provider.get_article(url)
        if article is None:
            log.warning("Article not found via provider, discarding: %s", url)
            channel.basic_ack(delivery_tag=method.delivery_tag)
            return

        label, score, mentions = self.enrich(article.title, article.body_text)
        if not self._provider.set_article_enrichment(url, label, score, mentions):
            # A company may have been removed since the universe was loaded: reload and retry once.
            log.warning("Provider rejected enrichment for %s; reloading company universe", url)
            self._refresh_universe()
            label, score, mentions = self.enrich(article.title, article.body_text)
            if not self._provider.set_article_enrichment(url, label, score, mentions):
                log.error("Provider rejected enrichment again, skipping: %s", url)
        else:
            log.info("Enriched: %s -> sentiment %s (%.2f), companies: %s", url, label, score,
                     ", ".join(self._names.get(m["companyId"], m["companyId"]) for m in mentions) or "none")

        channel.basic_ack(delivery_tag=method.delivery_tag)

    def enrich(self, title: str, body: str) -> tuple[str, float, list[dict]]:
        """Return (sentiment label, sentiment score, mention payloads)."""
        linked = self._linker.link(title, body)
        lead = sentences(body[:LEAD_CHARS], LEAD_SENTENCES)

        # One batched FinBERT call: title, lead sentences, then each company's sentences.
        texts = [title, *lead]
        spans = []
        for m in linked:
            spans.append((len(texts), len(texts) + len(m.sentences)))
            texts.extend(m.sentences)
        scores = self._sentiment.score(texts)

        article = article_sentiment(scores[0], scores[1:1 + len(lead)])
        mentions = []
        for m, (lo, hi) in zip(linked, spans):
            # The title counts double when the company is named in it.
            weights = [2.0 if s == title else 1.0 for s in m.sentences]
            mentions.append({
                "companyId": m.company_id,
                "count": m.count,
                "salience": m.salience(),
                "inTitle": m.in_title,
                "sentiment": weighted_mean(scores[lo:hi], weights),
            })
        return article.label, article.value, mentions


class Worker:
    def __init__(self, rabbitmq_url: str = RABBITMQ_URL, provider_url: str = PROVIDER_URL) -> None:
        self._rabbitmq_url = rabbitmq_url
        self._provider_url = provider_url

    def run(self) -> None:
        provider = ProviderClient(self._provider_url)
        log.info("Loading sentiment model and company universe…")
        handler = EnrichmentHandler(provider)

        connection = pika.BlockingConnection(pika.URLParameters(self._rabbitmq_url))
        channel = connection.channel()
        channel.basic_qos(prefetch_count=1)

        def _safe_nack(ch: pika.channel.Channel, delivery_tag: int) -> None:
            try:
                ch.basic_nack(delivery_tag=delivery_tag, requeue=True)
            except pika.exceptions.AMQPError as exc:
                log.error("Could not nack message — connection already lost: %s", exc)

        def _callback(ch, method, _props, body) -> None:
            try:
                call_with_retry(lambda: handler.handle(method, body, ch), connection.sleep)
            except ProviderError as exc:
                log.error("Provider still unreachable — nacking for requeue and shutting down: %s", exc)
                _safe_nack(ch, method.delivery_tag)
                ch.stop_consuming()
            except Exception as exc:
                log.exception("Unhandled crash processing message — nacking for requeue: %s", exc)
                _safe_nack(ch, method.delivery_tag)

        channel.basic_consume(queue=ARTICLE_ENRICH_QUEUE, on_message_callback=_callback)
        log.info("Listening on '%s'. Ctrl-C to stop.", ARTICLE_ENRICH_QUEUE)
        try:
            channel.start_consuming()
        except KeyboardInterrupt:
            log.info("Shutting down…")
            channel.stop_consuming()
        except pika.exceptions.AMQPError as exc:
            log.error("Lost connection to RabbitMQ — exiting: %s", exc)
        finally:
            try:
                connection.close()
            except pika.exceptions.AMQPError as exc:
                log.debug("Connection already closed: %s", exc)


if __name__ == "__main__":
    Worker().run()
