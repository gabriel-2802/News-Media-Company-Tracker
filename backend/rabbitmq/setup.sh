#!/bin/sh
set -e

BASE="http://localhost:15672/api"
AUTH="${RABBITMQ_USER:-admin}:${RABBITMQ_PASSWORD:-secret}"

echo "[setup] Waiting for management API..."
RETRY=0
MAX_RETRIES=30
until [ $RETRY -eq $MAX_RETRIES ]; do
  if curl -s -u "${AUTH}" "${BASE}/overview" > /dev/null 2>&1; then
    echo "[setup] Management API is ready"
    break
  fi
  RETRY=$((RETRY + 1))
  echo "[setup] Attempt $RETRY/$MAX_RETRIES..."
  sleep 2
done

if [ $RETRY -eq $MAX_RETRIES ]; then
  echo "[setup] ERROR: Management API never became ready"
  exit 1
fi

echo "[setup] Creating vhost: news_monitor"
curl -s -u "${AUTH}" -X PUT "${BASE}/vhosts/news_monitor"
echo ""

echo "[setup] Setting permissions for ${RABBITMQ_USER:-admin} on news_monitor"
curl -s -u "${AUTH}" -X PUT "${BASE}/permissions/news_monitor/${RABBITMQ_USER:-admin}" \
-H "Content-Type: application/json" \
-d '{"configure":".*","write":".*","read":".*"}'
echo ""

echo "[setup] Creating exchange: news_monitor.dlx (fanout dead-letter exchange)"
curl -s -u "${AUTH}" -X PUT "${BASE}/exchanges/news_monitor/news_monitor.dlx" \
-H "Content-Type: application/json" \
-d '{"type":"fanout","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'
echo ""

echo "[setup] Creating exchange: news_monitor (topic)"
curl -s -u "${AUTH}" -X PUT "${BASE}/exchanges/news_monitor/news_monitor" \
-H "Content-Type: application/json" \
-d '{"type":"topic","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'
echo ""

echo "[setup] Creating queue: scrape.jobs.dead"
curl -s -u "${AUTH}" -X PUT "${BASE}/queues/news_monitor/scrape.jobs.dead" \
-H "Content-Type: application/json" \
-d '{"durable":true,"auto_delete":false,"arguments":{}}'
echo ""

echo "[setup] Creating queue: scrape.jobs (DLX + 24h TTL)"
curl -s -u "${AUTH}" -X PUT "${BASE}/queues/news_monitor/scrape.jobs" \
-H "Content-Type: application/json" \
-d '{"durable":true,"auto_delete":false,"arguments":{"x-dead-letter-exchange":"news_monitor.dlx","x-message-ttl":86400000}}'
echo ""

echo "[setup] Binding scrape.jobs.dead -> news_monitor.dlx"
curl -s -u "${AUTH}" -X POST "${BASE}/bindings/news_monitor/e/news_monitor.dlx/q/scrape.jobs.dead" \
-H "Content-Type: application/json" \
-d '{"routing_key":"#","arguments":{}}'
echo ""

echo "[setup] Binding scrape.jobs -> news_monitor (routing_key: scrape.job)"
curl -s -u "${AUTH}" -X POST "${BASE}/bindings/news_monitor/e/news_monitor/q/scrape.jobs" \
-H "Content-Type: application/json" \
-d '{"routing_key":"scrape.job","arguments":{}}'
echo ""

echo "[setup] Creating queue: article.classify"
curl -s -u "${AUTH}" -X PUT "${BASE}/queues/news_monitor/article.classify" \
-H "Content-Type: application/json" \
-d '{"durable":true,"auto_delete":false,"arguments":{}}'
echo ""

echo "[setup] Binding article.classify -> news_monitor (routing_key: article.saved)"
curl -s -u "${AUTH}" -X POST "${BASE}/bindings/news_monitor/e/news_monitor/q/article.classify" \
-H "Content-Type: application/json" \
-d '{"routing_key":"article.saved","arguments":{}}'
echo ""

# Clustering was removed. On a broker set up before that, article.cluster is
# still bound to article.saved and, with no consumer, would keep a copy of
# every scraped article; delete it (404 on a fresh broker is fine).
echo "[setup] Deleting legacy queue: article.cluster"
curl -s -o /dev/null -u "${AUTH}" -X DELETE "${BASE}/queues/news_monitor/article.cluster"
echo ""

echo "[setup] Creating queue: article.enrich"
curl -s -u "${AUTH}" -X PUT "${BASE}/queues/news_monitor/article.enrich" \
-H "Content-Type: application/json" \
-d '{"durable":true,"auto_delete":false,"arguments":{}}'
echo ""

echo "[setup] Binding article.enrich -> news_monitor (routing_key: article.saved)"
curl -s -u "${AUTH}" -X POST "${BASE}/bindings/news_monitor/e/news_monitor/q/article.enrich" \
-H "Content-Type: application/json" \
-d '{"routing_key":"article.saved","arguments":{}}'
echo ""

echo "[setup] Creating queue: analytics.events"
curl -s -u "${AUTH}" -X PUT "${BASE}/queues/news_monitor/analytics.events" \
-H "Content-Type: application/json" \
-d '{"durable":true,"auto_delete":false,"arguments":{}}'
echo ""

echo "[setup] Binding analytics.events -> news_monitor (routing_key: event.upserted)"
curl -s -u "${AUTH}" -X POST "${BASE}/bindings/news_monitor/e/news_monitor/q/analytics.events" \
-H "Content-Type: application/json" \
-d '{"routing_key":"event.upserted","arguments":{}}'
echo ""

echo "[setup] Creating queue: manager.events"
curl -s -u "${AUTH}" -X PUT "${BASE}/queues/news_monitor/manager.events" \
-H "Content-Type: application/json" \
-d '{"durable":true,"auto_delete":false,"arguments":{}}'
echo ""

echo "[setup] Binding manager.events -> news_monitor (routing_key: event.upserted)"
curl -s -u "${AUTH}" -X POST "${BASE}/bindings/news_monitor/e/news_monitor/q/manager.events" \
-H "Content-Type: application/json" \
-d '{"routing_key":"event.upserted","arguments":{}}'
echo ""

# Provider-only queue: the Provider publishes directly to it by name via the
# default exchange (routing key == queue name), so it must NOT be bound to
# the news_monitor topic exchange — binding it to article.saved (or any
# other routing key shared with the classify/enrich fan-out) would leak
# every scraped article into this queue alongside the real subscription
# notifications.
echo "[setup] Creating queue: article.notifications"
curl -s -u "${AUTH}" -X PUT "${BASE}/queues/news_monitor/article.notifications" \
-H "Content-Type: application/json" \
-d '{"durable":true,"auto_delete":false,"arguments":{}}'
echo ""

echo "[setup] ✓ RabbitMQ topology is ready."