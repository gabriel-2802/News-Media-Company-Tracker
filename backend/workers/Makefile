# Matches the python:3.11-slim base of the worker Docker images. The macOS
# Command Line Tools python3 (3.9) segfaults launching Playwright helpers.
PYTHON          ?= /opt/homebrew/bin/python3.11

SCRAPER_VENV    := scraper/venv
CLASSIFIER_VENV := classifier/venv
CLUSTERER_VENV  := clusterer/venv
ENRICHER_VENV   := enricher/venv

.PHONY: test validate-sources check-sources setup setup-scraper setup-classifier setup-clusterer setup-enricher scrape-worker classify-worker cluster-worker enrich-worker clean

setup: setup-scraper setup-classifier setup-clusterer setup-enricher

setup-scraper:
	$(PYTHON) -m venv $(SCRAPER_VENV)
	$(SCRAPER_VENV)/bin/pip install --quiet --upgrade pip
	$(SCRAPER_VENV)/bin/pip install --quiet -r scraper/requirements.txt lxml_html_clean charset-normalizer
	$(SCRAPER_VENV)/bin/python -m playwright install chromium

setup-classifier:
	$(PYTHON) -m venv $(CLASSIFIER_VENV)
	$(CLASSIFIER_VENV)/bin/pip install --quiet --upgrade pip
	$(CLASSIFIER_VENV)/bin/pip install --quiet -r classifier/requirements.txt

setup-clusterer:
	$(PYTHON) -m venv $(CLUSTERER_VENV)
	$(CLUSTERER_VENV)/bin/pip install --quiet --upgrade pip
	$(CLUSTERER_VENV)/bin/pip install --quiet -r clusterer/requirements.txt

setup-enricher:
	$(PYTHON) -m venv $(ENRICHER_VENV)
	$(ENRICHER_VENV)/bin/pip install --quiet --upgrade pip
	$(ENRICHER_VENV)/bin/pip install --quiet -r enricher/requirements.txt

# Run from workers/ (this directory) so `scraper`/`classifier`/`clusterer`
# resolve as packages and all can import the shared provider_client.py.
scrape-worker:
	$(SCRAPER_VENV)/bin/python -m scraper.scraper_worker

classify-worker:
	$(CLASSIFIER_VENV)/bin/python -m classifier.classifier_worker

cluster-worker:
	$(CLUSTERER_VENV)/bin/python -m clusterer.clusterer_worker

enrich-worker:
	$(ENRICHER_VENV)/bin/python -m enricher.enricher_worker

clean:
	rm -rf $(SCRAPER_VENV) $(CLASSIFIER_VENV) $(CLUSTERER_VENV) $(ENRICHER_VENV) __pycache__ scraper/__pycache__ classifier/__pycache__ clusterer/__pycache__

# ── Tests and source checks (run from this directory) ─────────────────────────
test:
	$(SCRAPER_VENV)/bin/python -m unittest discover -s scraper/tests -t .
	$(CLASSIFIER_VENV)/bin/python -m unittest discover -s classifier/tests -t .
	$(ENRICHER_VENV)/bin/python -m unittest discover -s enricher/tests -t .
	$(CLUSTERER_VENV)/bin/python -m unittest discover -s clusterer/tests -t .

# Validate every candidate and rewrite data/source_validation/report.*
validate-sources:
	$(SCRAPER_VENV)/bin/python -m scraper.tools.validate_sources

# Health check of the seeded sources; fails when more than 20 % are not PASS.
SOURCE_REPORT_DIR ?= /tmp/source-health
check-sources:
	$(SCRAPER_VENV)/bin/python -m scraper.tools.validate_sources --seeded --max-fail-ratio 0.2 --report-dir $(SOURCE_REPORT_DIR)
