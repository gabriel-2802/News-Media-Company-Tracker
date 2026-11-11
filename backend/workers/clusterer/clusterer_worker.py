"""
RabbitMQ-driven clustering worker.

Neo4j is never touched directly — every read/write of Story/Article data
goes through the Provider service's REST API, same as the scrape and
classifier workers. Embeddings live in Qdrant (`story_centroids`
collection), keyed by Story ID, never in Neo4j.

Consumes messages from the `article.cluster` queue. This queue is bound to
the same `article.saved` routing key the scrape worker publishes to after
every article save (see scraper_worker.py) — it runs as an independent
consumer alongside the classifier worker (which consumes `article.classify`
off the same fan-out) rather than competing with it for the same messages
(see rabbitmq/setup.sh).

For each message:
  - Fetches the article's body text from the Provider.
  - Embeds it (see embedder.py).
  - Under a single global Redis lock (see handle()) — because the decision
    below isn't just "update an existing story's centroid," it's "does a
    matching story exist at all," and two clusterer instances running that
    check concurrently for two articles of the same brand-new story would
    otherwise both decide "no match" and create duplicate stories:
      - Queries `story_centroids` for the nearest CLUSTER_CANDIDATES (5)
        stories whose articles were *published* within STORY_WINDOW_DAYS (3)
        of this article, and attaches it to the first one clusterer/decision.py
        accepts: similarity >= SIMILARITY_THRESHOLD (0.533), or for two
        official releases the same publisher and >= OFFICIAL_SIMILARITY_THRESHOLD
        (0.75); and if both name banks/fintechs, at least one in common
        (linked with the shared entity_linker). The centroid is updated as a
        running average (Qdrant) and the article attached (Provider).
      - Otherwise creates a new Story (Provider — this is the one place
        the Provider write has to happen *before* the Qdrant write, since
        Story IDs are server-generated and there's no way to seed a
        centroid point under an ID that doesn't exist yet) and seeds its
        centroid.
      - No candidates (bootstrap / first article ever) falls into the
        same below-threshold branch — no special-casing needed.
  - The embedding step above — the CPU/GPU-bound cost that's the actual
    reason to run more than one clusterer instance — happens *before* the
    lock is acquired, so it stays fully parallel across instances; only
    the comparatively cheap decide-and-write step is serialized.

Message format (JSON), published by worker.py:
    {
        "url": "...",
        "source_name": "bbc"
    }

Run (from workers/, the parent of this file):
    make cluster-worker
    # or: clusterer/venv/bin/python -m clusterer.clusterer_worker

Environment variables:
    RABBITMQ_URL      amqp://user:pass@host:port/vhost
    PROVIDER_URL      http://host:port
    QDRANT_URL        https://<cluster-id>.<region>.gcp.cloud.qdrant.io
    QDRANT_API_KEY    Qdrant Cloud API key
    REDIS_URL         redis://[:password@]host:port/db — global clustering
                      decision lock, only matters once more than one
                      clusterer runs
"""
from __future__ import annotations

import json
import logging
import os
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

import pika
import pika.channel
import pika.exceptions
import pika.spec
import redis
from qdrant_client import QdrantClient
from qdrant_client.models import (
    DatetimeRange,
    Distance,
    FieldCondition,
    Filter,
    PayloadSchemaType,
    PointStruct,
    VectorParams,
)

from clusterer.decision import ArticleView, Policy, StoryView, choose, merged_payload, new_payload
from clusterer.embedder import ArticleEmbedder
from entity_linker import Company, EntityLinker
from env_config import require_env, require_float, require_int
from log_config import configure_logging
from provider_client import ProviderClient, ProviderError
from retry import call_with_retry

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

RABBITMQ_URL = require_env("RABBITMQ_URL")
PROVIDER_URL = require_env("PROVIDER_URL")

ARTICLE_CLUSTER_QUEUE = require_env("ARTICLE_CLUSTER_QUEUE")
CENTROID_COLLECTION = require_env("CENTROID_COLLECTION")

# Must match ArticleEmbedder's output dim (all-MiniLM-L6-v2 = 384). Update
# alongside embedder.py's MODEL_NAME if that ever changes — a mismatch
# fails loudly at collection-creation time, not silently.
EMBEDDING_DIM = require_int("EMBEDDING_DIM")

# Chosen from workers/clusterer/data/output/validation_report.md (the
# all-MiniLM-L6-v2 run, since that's what embedder.py uses). Revisit once
# real traffic accumulates.
SIMILARITY_THRESHOLD = require_float("SIMILARITY_THRESHOLD")
# Story window by article publish date (days on each side of a story's
# first/last article) and the stricter bar for two official releases.
# See clusterer/decision.py for the measurements behind these defaults.
STORY_WINDOW_DAYS = float(os.environ.get("STORY_WINDOW_DAYS", "3"))
OFFICIAL_SIMILARITY_THRESHOLD = float(os.environ.get("OFFICIAL_SIMILARITY_THRESHOLD", "0.75"))
CLUSTER_CANDIDATES = int(os.environ.get("CLUSTER_CANDIDATES", "5"))
UNIVERSE_REFRESH_SECONDS = 60 * float(os.environ.get("UNIVERSE_REFRESH_MINUTES", "60"))
OFFICIAL_CATEGORIES = {"REGULATOR", "CENTRAL_BANK"}

# The model has no automatic truncation for inputs this short of its
# max_seq_length, so an unusually long scraped article can drive quadratic
# self-attention memory into the tens-of-GB range. Cap before embedding —
# see validate.py, where this was found live.
_MAX_EMBED_CHARS = require_int("MAX_EMBED_CHARS")

# Global lock around the whole "find a candidate, then attach-or-create"
# decision (see handle()). Only matters once more than one clusterer
# instance runs — see "Scaling Considerations" in the README.
CLUSTER_DECISION_LOCK_KEY = "clusterer:decision-lock"
CLUSTER_LOCK_TIMEOUT_SECONDS = require_int("CLUSTER_LOCK_TIMEOUT_SECONDS")
CLUSTER_LOCK_BLOCKING_TIMEOUT_SECONDS = require_int("CLUSTER_LOCK_BLOCKING_TIMEOUT_SECONDS")

configure_logging()
log = logging.getLogger(__name__)


def _embed_article(embedder: ArticleEmbedder, title: str, body_text: str) -> list[float]:
    text = f"{title}\n\n{body_text}"[:_MAX_EMBED_CHARS]
    return embedder.embed(text)


def _normalize(vector: list[float]) -> list[float]:
    norm = sum(v * v for v in vector) ** 0.5
    if norm == 0:
        return vector
    return [v / norm for v in vector]


def ensure_collection(qdrant: QdrantClient) -> None:
    """Idempotent — safe to call on every worker startup."""
    if not qdrant.collection_exists(CENTROID_COLLECTION):
        log.info("Creating Qdrant collection '%s' (dim=%d, cosine)", CENTROID_COLLECTION, EMBEDDING_DIM)
        qdrant.create_collection(
            collection_name=CENTROID_COLLECTION,
            vectors_config=VectorParams(size=EMBEDDING_DIM, distance=Distance.COSINE),
        )
    # Qdrant needs a payload index before a field can be used in a filter.
    # Re-run on every startup so an older collection gets new indexes too.
    for field_name in ("lastUpdated", "firstPublishedAt", "lastPublishedAt"):
        qdrant.create_payload_index(
            collection_name=CENTROID_COLLECTION,
            field_name=field_name,
            field_schema=PayloadSchemaType.DATETIME,
        )


def _parse_dt(value: Optional[str], default: datetime) -> datetime:
    if not value:
        return default
    dt = datetime.fromisoformat(value)
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# Job handler
# ---------------------------------------------------------------------------


class ClusteringHandler:
    def __init__(self, provider: ProviderClient, qdrant: QdrantClient, redis_client: redis.Redis) -> None:
        self._provider = provider
        self._qdrant = qdrant
        self._redis = redis_client
        # Loads lazily on first use, not at import time.
        self._embedder = ArticleEmbedder.get_instance()
        self._policy = Policy(
            base_threshold=SIMILARITY_THRESHOLD,
            official_threshold=OFFICIAL_SIMILARITY_THRESHOLD,
            window=timedelta(days=STORY_WINDOW_DAYS),
        )
        self._categories: dict[str, Optional[str]] = {}
        self._linker: Optional[EntityLinker] = None
        self._bank_ids: frozenset[str] = frozenset()
        self._loaded_at = 0.0
        self._refresh_universe()

    def _refresh_universe(self) -> None:
        """Company universe for the bank/fintech guard (same linker as the enricher)."""
        companies = self._provider.get_companies()
        self._linker = EntityLinker(Company(c.id, c.name, c.aliases) for c in companies)
        self._bank_ids = frozenset(c.id for c in companies if c.kind in ("BANK", "FINTECH"))
        self._loaded_at = time.monotonic()
        log.info("Company universe loaded for clustering guard: %d banks/fintechs", len(self._bank_ids))

    def _is_official(self, source_name: str) -> bool:
        if source_name not in self._categories:
            source = self._provider.get_source(source_name)
            self._categories[source_name] = source.category if source else None
        return self._categories[source_name] in OFFICIAL_CATEGORIES

    def handle(self, method: pika.spec.Basic.Deliver, body: bytes, channel: pika.channel.Channel) -> None:
        try:
            payload = json.loads(body)
            url: str = payload["url"]
        except (json.JSONDecodeError, KeyError) as exc:
            log.error("Malformed message, discarding: %r — %s", body, exc)
            channel.basic_ack(delivery_tag=method.delivery_tag)
            return

        article = self._provider.get_article(url)
        if article is None:
            log.warning("Article not found via provider, discarding: %s", url)
            channel.basic_ack(delivery_tag=method.delivery_tag)
            return

        if time.monotonic() - self._loaded_at > UNIVERSE_REFRESH_SECONDS:
            self._refresh_universe()

        # Embedding is the CPU/GPU-bound step (the reason to run more than
        # one clusterer instance), so it happens before the lock.
        vector = _embed_article(self._embedder, article.title, article.body_text)
        source = payload.get("source_name") or article.source
        view = ArticleView(
            published_at=article.published_at,
            source=source,
            official=self._is_official(source),
            companies=frozenset(m.company_id for m in self._linker.link(article.title, article.body_text)
                                if m.company_id in self._bank_ids),
        )

        # "Is there a matching story, and act on it" must be one atomic
        # decision across clusterer instances, or two instances could both
        # create a story for the same new event.
        lock = self._redis.lock(
            CLUSTER_DECISION_LOCK_KEY,
            timeout=CLUSTER_LOCK_TIMEOUT_SECONDS,
            blocking_timeout=CLUSTER_LOCK_BLOCKING_TIMEOUT_SECONDS,
        )
        if not lock.acquire():
            raise TimeoutError(
                f"Could not acquire clustering decision lock within "
                f"{CLUSTER_LOCK_BLOCKING_TIMEOUT_SECONDS}s"
            )
        try:
            candidates = self._find_candidates(vector, view.published_at)
            chosen, rejected = choose(view, [c for c, _ in candidates], self._policy)
            if rejected:
                log.info("Rejected candidates for %s: %s", url, "; ".join(rejected))
            if chosen is not None:
                hit = next(h for c, h in candidates if c is chosen)
                self._attach_to_existing(chosen, hit, vector, view, url)
            else:
                self._create_and_attach(article.title, vector, view, url)
        finally:
            lock.release()

        channel.basic_ack(delivery_tag=method.delivery_tag)

    def _find_candidates(self, vector: list[float], published_at: datetime) -> list[tuple[StoryView, Any]]:
        """Nearest stories whose publish-date span is within the window of this article."""
        window = timedelta(days=STORY_WINDOW_DAYS)
        hits = self._qdrant.query_points(
            collection_name=CENTROID_COLLECTION,
            query=vector,
            query_filter=Filter(must=[
                FieldCondition(key="lastPublishedAt", range=DatetimeRange(gte=(published_at - window).isoformat())),
                FieldCondition(key="firstPublishedAt", range=DatetimeRange(lte=(published_at + window).isoformat())),
            ]),
            limit=CLUSTER_CANDIDATES,
            with_vectors=True,
        ).points
        views = []
        for h in hits:
            p = h.payload
            updated = _parse_dt(p.get("lastUpdated"), datetime.now(timezone.utc))
            views.append((StoryView(
                story_id=p["storyId"],
                score=h.score,
                first_published=_parse_dt(p.get("firstPublishedAt"), updated),
                last_published=_parse_dt(p.get("lastPublishedAt"), updated),
                sources=set(p.get("sources", [])),
                official_only=bool(p.get("officialOnly", False)),
                companies=set(p.get("companies", [])),
            ), h))
        return views

    def _attach_to_existing(self, story: StoryView, hit: Any, vector: list[float], view: ArticleView, url: str) -> None:
        # Inside the decision lock, so `hit` cannot have changed since it was read.
        # Vector DB first (idempotent upsert by id). A retry after a lost ack
        # can compound the running average once; Neo4j's articleCount stays
        # authoritative, so this only skews the internal centroid slightly.
        new_count = hit.payload["articleCount"] + 1
        new_vector = _normalize([(ov * (new_count - 1) + nv) for ov, nv in zip(hit.vector, vector)])
        payload = merged_payload(story, view, new_count) | {"lastUpdated": datetime.now(timezone.utc).isoformat()}
        self._qdrant.upsert(
            collection_name=CENTROID_COLLECTION,
            points=[PointStruct(id=story.story_id, vector=new_vector, payload=payload)],
        )
        if self._provider.attach_article_to_story(story.story_id, url) is not None:
            log.info("Attached (score=%.3f): %s -> story %s", story.score, url, story.story_id)
        else:
            log.warning("Provider rejected attach to existing story %s: %s", story.story_id, url)

    def _create_and_attach(self, title: str, vector: list[float], view: ArticleView, url: str) -> None:
        # Provider first: story ids are server-generated, so there is no id to
        # seed a centroid under until the Provider assigns one.
        new_story = self._provider.create_story(title)
        if self._provider.attach_article_to_story(new_story.id, url) is None:
            log.warning("Provider rejected attach to freshly created story %s: %s", new_story.id, url)
            return
        payload = new_payload(new_story.id, view) | {"lastUpdated": datetime.now(timezone.utc).isoformat()}
        self._qdrant.upsert(
            collection_name=CENTROID_COLLECTION,
            points=[PointStruct(id=new_story.id, vector=vector, payload=payload)],
        )
        log.info("New story: %s -> story %s", url, new_story.id)


# ---------------------------------------------------------------------------
# Worker
# ---------------------------------------------------------------------------


class Worker:
    def __init__(
        self,
        rabbitmq_url: str = RABBITMQ_URL,
        provider_url: str = PROVIDER_URL,
        qdrant_url: Optional[str] = None,
        qdrant_api_key: Optional[str] = None,
        redis_url: Optional[str] = None,
    ) -> None:
        self._rabbitmq_url = rabbitmq_url
        self._provider_url = provider_url
        self._qdrant_url = qdrant_url or require_env("QDRANT_URL")
        self._qdrant_api_key = qdrant_api_key or require_env("QDRANT_API_KEY")
        self._redis_url = redis_url or require_env("REDIS_URL")

    def run(self) -> None:
        provider = ProviderClient(self._provider_url)
        qdrant = QdrantClient(
            url=self._qdrant_url,
            api_key=self._qdrant_api_key,
            check_compatibility=False,
        )
        ensure_collection(qdrant)
        redis_client = redis.Redis.from_url(self._redis_url)

        log.info("Loading embedding model…")
        handler = ClusteringHandler(provider, qdrant, redis_client)
        log.info("Model loaded.")

        log.info("Connecting to RabbitMQ → %s", self._rabbitmq_url)
        connection = pika.BlockingConnection(pika.URLParameters(self._rabbitmq_url))
        channel = connection.channel()
        # prefetch_count=1: embedding + candidate scoring is CPU/GPU-bound;
        # don't let RabbitMQ hand this worker a backlog it can't process
        # concurrently.
        channel.basic_qos(prefetch_count=1)

        def _safe_nack(ch: pika.channel.Channel, delivery_tag: int) -> None:
            """Best-effort nack. If the connection itself is already gone,
            there's nothing more to do here — RabbitMQ will redeliver once
            it notices the consumer vanished. Letting that secondary
            failure escape uncaught is what used to crash the whole worker
            with an unrelated ChannelWrongStateError on top of the original
            connection loss."""
            try:
                ch.basic_nack(delivery_tag=delivery_tag, requeue=True)
            except pika.exceptions.AMQPError as exc:
                log.error("Could not nack message — connection already lost: %s", exc)

        def _callback(
            ch: pika.channel.Channel,
            method: pika.spec.Basic.Deliver,
            _props: pika.spec.BasicProperties,
            body: bytes,
        ) -> None:
            try:
                call_with_retry(lambda: handler.handle(method, body, ch), connection.sleep)
            except ProviderError as exc:
                log.error(
                    "Provider still unreachable — nacking for requeue and shutting down: %s", exc
                )
                _safe_nack(ch, method.delivery_tag)
                ch.stop_consuming()
            except Exception as exc:
                log.exception("Unhandled crash processing message — nacking for requeue: %s", exc)
                _safe_nack(ch, method.delivery_tag)

        channel.basic_consume(queue=ARTICLE_CLUSTER_QUEUE, on_message_callback=_callback)
        log.info("Listening on '%s'. Ctrl-C to stop.", ARTICLE_CLUSTER_QUEUE)

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
