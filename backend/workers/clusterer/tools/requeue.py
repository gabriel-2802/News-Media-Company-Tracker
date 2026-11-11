"""
Re-queue every stored article on article.cluster, oldest first.

Used by `make recluster` after stories and centroids were reset, e.g. when
the clustering rules change. Articles, topics, mentions and sentiment are
untouched; events are rebuilt by the Provider as articles join stories.

Run from backend/workers/:
    clusterer/venv/bin/python -m clusterer.tools.requeue
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import pika
import requests
from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[4] / ".env")


def main() -> int:
    provider = os.environ["PROVIDER_URL"]
    articles, page = [], 0
    while True:
        resp = requests.get(f"{provider}/api/articles", params={"page": page, "count": 200}, timeout=30)
        resp.raise_for_status()
        body = resp.json()
        articles += [(a["publishedAt"] or "", a["url"], a["source"]) for a in body["content"]]
        page += 1
        if page >= body["totalPages"]:
            break
    articles.sort()                                  # oldest first: stories grow in publication order

    conn = pika.BlockingConnection(pika.URLParameters(os.environ["RABBITMQ_URL"]))
    channel = conn.channel()
    queue = os.environ.get("ARTICLE_CLUSTER_QUEUE", "article.cluster")
    for _, url, source in articles:
        channel.basic_publish(exchange="", routing_key=queue,
                              body=json.dumps({"url": url, "source_name": source}),
                              properties=pika.BasicProperties(content_type="application/json", delivery_mode=2))
    conn.close()
    print(f"Queued {len(articles)} articles on {queue}, oldest first")
    return 0


if __name__ == "__main__":
    sys.exit(main())
