"""
End-to-end check of the running system (Phase 10).

Against running manager, provider and analytics services plus the three workers it:
  1. checks the three services are healthy;
  2. creates throwaway users who together watch every listed ticker (50 per user, minImpact 0),
     so any new article about a listed company must produce an alert;
  3. triggers a scrape of every source (as the seeded admin);
  4. waits until new articles are scraped and every one has topics, sentiment and its story,
     new Story->Company events exist, and a watcher received a TICKER_ALERT;
  5. checks /overlay returns price bars and event markers for at least 5 banks;
  6. deletes the throwaway users.
Prints one line per check and exits 1 if any check failed.

Usage (from the repository root, services running):
    backend/workers/scraper/venv/bin/python backend/e2e/e2e_check.py     # or: make e2e

Environment (defaults match local runs): MANAGER_URL, PROVIDER_URL, ANALYTICS_URL,
APP_ADMIN_EMAIL / APP_ADMIN_PASSWORD (the manager's seeded admin), E2E_TIMEOUT_MINUTES (20),
E2E_MIN_NEW_ARTICLES (3).
"""
from __future__ import annotations

import os
import sys
import time
import uuid
from collections import Counter
from datetime import date, datetime, timedelta, timezone

import requests

MANAGER = os.environ.get("MANAGER_URL", "http://localhost:9000/manager").rstrip("/")
PROVIDER = os.environ.get("PROVIDER_URL", "http://localhost:8080/news-provider").rstrip("/")
ANALYTICS = os.environ.get("ANALYTICS_URL", "http://localhost:8090/analytics").rstrip("/")
ADMIN_EMAIL = os.environ.get("APP_ADMIN_EMAIL", "admin@example.com")
ADMIN_PASSWORD = os.environ.get("APP_ADMIN_PASSWORD", "Admin123!")
TIMEOUT = float(os.environ.get("E2E_TIMEOUT_MINUTES", "20")) * 60
MIN_NEW = int(os.environ.get("E2E_MIN_NEW_ARTICLES", "3"))
POLL_SECONDS = 20

failures: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    print(f"{'PASS' if ok else 'FAIL'}  {name}{'  - ' + detail if detail else ''}", flush=True)
    if not ok:
        failures.append(name)
    return ok


def get(url: str, **kw):
    r = requests.get(url, timeout=30, **kw)
    r.raise_for_status()
    return r.json()


def login(email: str, password: str) -> dict:
    r = requests.post(f"{MANAGER}/api/auth/login", json={"email": email, "password": password}, timeout=30)
    r.raise_for_status()
    return r.json()


def all_article_urls() -> set[str]:
    urls, page = set(), 0
    while True:
        body = get(f"{PROVIDER}/api/articles", params={"page": page, "count": 200})
        urls.update(a["url"] for a in body["content"])
        page += 1
        if page >= body["totalPages"]:
            return urls


def all_events(since: str = "2000-01-01T00:00:00Z") -> list[dict]:
    events, page = [], 0
    while True:
        batch = get(f"{PROVIDER}/api/events", params={"since": since, "page": page, "count": 1000})
        events += batch
        page += 1
        if len(batch) < 1000:
            return events


def article(url: str) -> dict:
    return get(f"{PROVIDER}/api/articles/by-url", params={"url": url})


def has_story(url: str) -> bool:
    r = requests.get(f"{PROVIDER}/api/stories/by-article", params={"url": url}, timeout=30)
    return r.status_code == 200


def main() -> int:
    started = datetime.now(timezone.utc)
    since = (started - timedelta(seconds=5)).strftime("%Y-%m-%dT%H:%M:%SZ")

    # 1. health
    for name, base in (("manager", MANAGER), ("provider", PROVIDER), ("analytics", ANALYTICS)):
        try:
            status = requests.get(f"{base}/actuator/health", timeout=10).status_code
        except requests.RequestException as exc:
            status = str(exc)
        check(f"{name} healthy", status == 200, str(status))
    if failures:
        return 1

    companies = get(f"{PROVIDER}/api/companies")
    kind = {c["id"]: c["kind"] for c in companies}
    listed = Counter(e["symbol"] for e in all_events() if e.get("symbol") and not e.get("deleted"))

    # 2. throwaway users watching every listed ticker, most-affected first
    symbols = sorted({t["symbol"] for c in companies for t in c["tickers"]}, key=lambda s: -listed[s])
    users: list[dict] = []                                   # {"id", "headers"}
    for i in range(0, len(symbols), 50):
        email = f"e2e-{uuid.uuid4().hex[:12]}@example.test"
        password = "E2e-" + uuid.uuid4().hex
        requests.post(f"{MANAGER}/api/auth/register", json={"email": email, "password": password, "name": "E2E"},
                      timeout=30).raise_for_status()
        auth = login(email, password)
        user = {"id": auth["user"]["id"], "headers": {"Authorization": f"Bearer {auth['token']}"}}
        users.append(user)
        for symbol in symbols[i:i + 50]:
            requests.put(f"{MANAGER}/api/users/me/watchlist/{symbol}", json={"minImpact": 0.0},
                         headers=user["headers"], timeout=30).raise_for_status()
    wl = get(f"{MANAGER}/api/users/me/watchlist", headers=users[0]["headers"])
    check("watchlists created", len(wl) == min(50, len(symbols)) > 0,
          f"{len(symbols)} tickers over {len(users)} users; first list: {sum(1 for i in wl if i['quote'])}/{len(wl)} "
          f"with quotes, {sum(1 for i in wl if i['latestEvent'])} with a latest event")

    admin = login(ADMIN_EMAIL, ADMIN_PASSWORD)
    admin_headers = {"Authorization": f"Bearer {admin['token']}"}
    try:
        # 3. scrape
        before = all_article_urls()
        r = requests.post(f"{PROVIDER}/api/articles/trigger-scrape", headers=admin_headers, timeout=30)
        check("scrape triggered", r.status_code == 202, f"{r.text} jobs queued")

        # 4. pipeline
        deadline = time.monotonic() + TIMEOUT
        new: set[str] = set()
        complete: dict[str, bool] = {}
        alerts: list[dict] = []
        while time.monotonic() < deadline:
            time.sleep(POLL_SECONDS)
            new = all_article_urls() - before
            for url in new:
                if not complete.get(url):
                    a = article(url)
                    complete[url] = bool(a.get("topics")) and a.get("sentimentLabel") is not None and has_story(url)
            alerts = [n for u in users for n in get(f"{MANAGER}/api/users/me/notifications", params={"size": 100},
                                                     headers=u["headers"])["content"] if n["type"] == "TICKER_ALERT"]
            done = sum(complete.get(u, False) for u in new)
            print(f"      {int(time.monotonic() - deadline + TIMEOUT)}s: {len(new)} new, {done} fully processed, "
                  f"{len(alerts)} alert(s)", flush=True)
            if len(new) >= MIN_NEW and done == len(new) and alerts:
                break

        done = [u for u in new if complete.get(u)]
        check(f"at least {MIN_NEW} new articles scraped", len(new) >= MIN_NEW, f"{len(new)} new")
        check("every new article has topics, sentiment and a story", bool(new) and len(done) == len(new),
              f"{len(done)}/{len(new)}" + ("" if len(done) == len(new) else
                                           f"; incomplete e.g. {sorted(set(new) - set(done))[:3]}"))
        topics = Counter(t["name"] for u in done for t in article(u)["topics"])
        print(f"      topics: {dict(topics.most_common(6))}")
        new_events = [e for e in all_events(since) if not e.get("deleted")]
        check("new articles produced Story->Company events", bool(new_events),
              f"{len(new_events)} events updated since start, {len({e['symbol'] for e in new_events if e.get('symbol')})} listed symbols")
        check("a watcher received a ticker alert", bool(alerts),
              f"{len(alerts)} alert(s), e.g. {alerts[0]['message'][:110]}" if alerts else "none")

        # 5. overlay for 5 banks
        frm = (date.today() - timedelta(days=365)).isoformat()
        events_by_symbol = Counter(
            e["symbol"] for e in all_events()
            if e.get("symbol") and not e.get("deleted") and kind.get(e["companyId"]) == "BANK"
            and e.get("exchange") in ("XNYS", "XNAS"))                     # price data covers US listings
        good = []
        for symbol in sorted(events_by_symbol, key=events_by_symbol.get, reverse=True):
            o = requests.get(f"{ANALYTICS}/api/charts/companies/{symbol}/overlay", params={"from": frm}, timeout=30)
            if o.status_code == 200 and o.json()["bars"] and o.json()["markers"]:
                good.append(f"{symbol}({len(o.json()['markers'])})")
            if len(good) == 5:
                break
        check("overlay has bars and markers for 5 banks", len(good) == 5, ", ".join(good))
    finally:
        # 6. cleanup
        codes = [requests.delete(f"{MANAGER}/api/users/{u['id']}", headers=admin_headers, timeout=30).status_code
                 for u in users]
        check("throwaway users deleted", all(c in (200, 204) for c in codes), str(codes))

    print(f"\n{'ALL CHECKS PASSED' if not failures else 'FAILED: ' + ', '.join(failures)} "
          f"({(datetime.now(timezone.utc) - started).seconds}s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
