#!/usr/bin/env bash
#
# Start, stop and inspect the whole system locally: infra in Docker
# (neo4j, rabbitmq, postgres), the three Spring services and the three Python
# workers as background processes. Used by `make run`, `make stop` and
# `make status` (run from the repository root; the Makefile exports .env).
#
#   start   infra -> manager + provider -> analytics -> workers, each step
#           waiting until the previous one is healthy
#   stop    stops the services and workers, then the infra containers
#   status  one line per component
#
# Logs: .run/logs/<name>.log. Process-group ids live outside the repo (in
# $TMPDIR): the project may sit in an iCloud-synced folder, where sync can hide
# or resurrect small files, and a missing pid file made stop skip a process.
# stop also sweeps our ports and worker processes in case a pid file is lost.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/.run/logs"
RUN="/tmp/news-monitor-run-$(printf '%s' "$ROOT" | shasum | cut -c1-10)"   # fixed path: same for every shell
mkdir -p "$LOGS" "$RUN"

SERVICES=(manager provider analytics)
WORKERS=(classifier enricher scraper)
# Lookups as functions: macOS ships bash 3.2 (no associative arrays).
base_url() { case "$1" in manager) echo http://localhost:9000/manager ;; provider) echo http://localhost:8080/news-provider ;;
                          analytics) echo http://localhost:8090/analytics ;; esac; }
queue_of() { case "$1" in classifier) echo article.classify ;; enricher) echo article.enrich ;; scraper) echo scrape.jobs ;; esac; }
module_of() { case "$1" in classifier) echo classifier.classifier_worker ;; enricher) echo enricher.enricher_worker ;;
                           scraper) echo scraper.scraper_worker ;; esac; }

# Values from .env reach us through make with their quotes kept.
unquote() { local v="$1"; [[ ${#v} -ge 2 && ${v:0:1} == "${v: -1}" && ( ${v:0:1} == '"' || ${v:0:1} == "'" ) ]] && v="${v:1:${#v}-2}"; printf '%s' "$v"; }
RMQ_USER="$(unquote "${RABBITMQ_USER:-admin}")"
RMQ_PASS="$(unquote "${RABBITMQ_PASSWORD:-secret}")"
RMQ_API="http://localhost:$(unquote "${RABBITMQ_MANAGEMENT_PORT:-15672}")/api"

say()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$*"; }
fail() { printf '    \033[31m✗\033[0m %s\n' "$*" >&2; }

pid_of()  { [[ -f "$RUN/$1.pid" ]] && cat "$RUN/$1.pid"; }
alive()   { local p; p="$(pid_of "$1")" && [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null; }
healthy() { [[ "$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$(base_url "$1")/actuator/health")" == 200 ]]; }
consumers() {   # consumers on a RabbitMQ queue (0 if the API is unreachable)
  curl -s -m 3 -u "$RMQ_USER:$RMQ_PASS" "$RMQ_API/queues/news_monitor/$1" 2>/dev/null \
    | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("consumers",0))
except Exception: print(0)'
}

has_consumer() { [[ "$(consumers "$1")" -ge 1 ]]; }

java21() {
  local h; h="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  printf '%s' "${h:-${JAVA_HOME:-}}"
}

# Start "$@" in its own process group so stop can take down mvn/java or
# python/chromium children too.
launch() {
  local name="$1" dir="$2"; shift 2
  set -m
  ( cd "$dir" && exec nohup "$@" ) >"$LOGS/$name.log" 2>&1 &   # nohup: survives closing the terminal
  echo $! >"$RUN/$name.pid"
  set +m
}

wait_until() {   # wait_until <seconds> <description> <name> <command...>
  local secs="$1" what="$2" name="$3"; shift 3
  local end=$((SECONDS + secs))
  while (( SECONDS < end )); do
    if "$@"; then return 0; fi
    if ! alive "$name"; then fail "$what: process exited (see .run/logs/$name.log)"; tail -5 "$LOGS/$name.log" >&2; return 1; fi
    sleep 2
  done
  fail "$what: not ready after ${secs}s (see .run/logs/$name.log)"; return 1
}

preflight() {
  local bad=0
  docker info >/dev/null 2>&1 || { fail "Docker is not running"; bad=1; }
  [[ -n "$(java21)" ]] || { fail "JDK 21 not found (brew install openjdk@21)"; bad=1; }
  command -v mvn >/dev/null || { fail "Maven not found"; bad=1; }
  for w in scraper classifier enricher; do
    [[ -x "$ROOT/backend/workers/$w/venv/bin/python" ]] || { fail "missing $w venv: run 'make setup-workers'"; bad=1; }
  done
  [[ -d "$ROOT/backend/workers/classifier/cls_model_output" ]] \
    || { fail "classifier model missing: train it (README, Classifier Worker)"; bad=1; }
  for k in "$(unquote "${JWT_PRIVATE_KEY_PATH:-}")" "$(unquote "${JWT_PUBLIC_KEY_PATH:-}")"; do
    [[ -z "$k" || -f "$k" || -f "$ROOT/$k" || -f "$ROOT/backend/manager/$k" ]] || { fail "JWT key not found: $k"; bad=1; }
  done
  return $bad
}

start_infra() {
  say "Infra (neo4j, rabbitmq, postgres)"
  (cd "$ROOT" && docker compose up -d >/dev/null 2>&1) || { fail "docker compose up failed"; return 1; }
  local c end=$((SECONDS + 180))
  for c in neo4j rabbitmq postgres; do
    until [[ "$(docker inspect -f '{{.State.Health.Status}}' "news-mm-$c-1" 2>/dev/null)" == healthy ]]; do
      (( SECONDS < end )) || { fail "$c not healthy after 180s"; return 1; }
      sleep 3
    done
    ok "$c"
  done
}

start_service() {
  local name="$1" jh; jh="$(java21)"
  if alive "$name"; then ok "$name already running"; return 0; fi
  if [[ "$name" == manager ]]; then
    launch manager "$ROOT/backend/manager" env JAVA_HOME="$jh" \
      SPRING_DATASOURCE_PASSWORD="$(unquote "${POSTGRES_PASSWORD:-}")" \
      DB_USERNAME="$(unquote "${POSTGRES_USER:-}")" DB_NAME="$(unquote "${POSTGRES_DB:-}")" \
      mvn -q spring-boot:run
  else
    launch "$name" "$ROOT/backend/$name" env JAVA_HOME="$jh" mvn -q spring-boot:run
  fi
}

start_worker() {
  local name="$1"
  if alive "$name"; then ok "$name already running"; return 0; fi
  launch "$name" "$ROOT/backend/workers" "$name/venv/bin/python" -m "$(module_of "$name")"
}

start() {
  say "Checking prerequisites"
  preflight || exit 1
  ok "docker, JDK 21, maven, worker venvs, classifier model, JWT keys"
  start_infra || exit 1

  say "Services (manager, provider, then analytics) — first start compiles, allow a few minutes"
  start_service manager; start_service provider
  wait_until 300 manager manager healthy manager && ok "manager   $(base_url manager)" || exit 1
  wait_until 300 provider provider healthy provider && ok "provider  $(base_url provider)" || exit 1
  start_service analytics
  wait_until 300 analytics analytics healthy analytics && ok "analytics $(base_url analytics)" || exit 1

  say "Workers (classifier, enricher, scraper)"
  local w
  for w in "${WORKERS[@]}"; do start_worker "$w"; done
  for w in "${WORKERS[@]}"; do
    wait_until 240 "$w" "$w" has_consumer "$(queue_of "$w")" && ok "$w consuming $(queue_of "$w")" || exit 1
  done

  say "All up. Logs: .run/logs/   Check: make status   End-to-end test: make e2e   Stop: make stop"
}

stop_one() {
  local name="$1" p; p="$(pid_of "$name")" || return 0
  if [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null; then
    kill -TERM -- "-$p" 2>/dev/null || kill -TERM "$p" 2>/dev/null
    local i; for i in $(seq 1 20); do kill -0 "$p" 2>/dev/null || break; sleep 1; done
    kill -KILL -- "-$p" 2>/dev/null || true
    ok "$name stopped"
  fi
  rm -f "${RUN:?}/$name.pid"
}

kill_group_of() {   # TERM, then KILL, the whole process group of a pid
  local pg; pg="$(ps -o pgid= -p "$1" 2>/dev/null | tr -d ' ')"; [[ -n "$pg" ]] || return 0
  kill -TERM -- "-$pg" 2>/dev/null || kill -TERM "$1" 2>/dev/null
  local i; for i in $(seq 1 20); do kill -0 "$1" 2>/dev/null || return 0; sleep 1; done
  kill -KILL -- "-$pg" 2>/dev/null || kill -KILL "$1" 2>/dev/null || true
}

# Anything of ours still running without a pid file (started by hand, or a
# pid file lost): service listeners on our ports and this project's workers.
stop_strays() {
  local port p n
  for port in 9000 8080 8090; do
    for p in $(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null); do
      kill_group_of "$p"; ok "stopped leftover listener on :$port"
    done
  done
  for n in classifier enricher scraper; do
    # match the module, not the interpreter: macOS reports the resolved framework Python path
    for p in $(pgrep -f -- "-m $(module_of "$n" | sed 's/\./\\./g')( |\$)" 2>/dev/null); do
      kill_group_of "$p"; ok "stopped leftover $n worker"
    done
  done
  pkill -f playwright_fetch_worker 2>/dev/null || true
}

stop() {
  say "Workers and services"
  local n
  for n in scraper enricher classifier analytics provider manager; do stop_one "$n"; done
  stop_strays
  say "Infra containers"
  (cd "$ROOT" && docker compose stop >/dev/null 2>&1) && ok "stopped (data kept in Docker volumes)"
}

status() {
  local n c s
  for c in neo4j rabbitmq postgres; do
    s="$(docker inspect -f '{{if .State.Running}}{{.State.Health.Status}}{{else}}stopped{{end}}' "news-mm-$c-1" 2>/dev/null || echo 'not created')"
    printf '  %-11s %s\n' "$c" "$s"
  done
  for n in "${SERVICES[@]}"; do
    if healthy "$n"; then s="healthy  $(base_url "$n")"
    elif alive "$n"; then s="starting (see .run/logs/$n.log)"
    else s="stopped"; fi
    printf '  %-11s %s\n' "$n" "$s"
  done
  for n in "${WORKERS[@]}"; do
    if alive "$n"; then s="running  ($(consumers "$(queue_of "$n")") consumer(s) on $(queue_of "$n"))"; else s="stopped"; fi
    printf '  %-11s %s\n' "$n" "$s"
  done
}

case "${1:-}" in
  start)      start ;;
  stop)       stop ;;
  status)     status ;;
  *) echo "usage: $0 start|stop|status" >&2; exit 2 ;;
esac
