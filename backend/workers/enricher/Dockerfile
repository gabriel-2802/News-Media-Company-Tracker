# syntax=docker/dockerfile:1
#
# Build context is workers/ (not workers/enricher/): this worker imports the
# shared modules one level up (env_config.py, provider_client.py, retry.py,
# log_config.py), same as `python -m enricher.enricher_worker` locally.

FROM python:3.11-slim AS build
WORKDIR /build
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
COPY enricher/requirements.txt enricher/requirements.txt
# CPU-only torch first, so pip does not pull gigabytes of CUDA wheels.
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu "torch>=2.5,<3" \
    && pip install --no-cache-dir -r enricher/requirements.txt
# Bake the FinBERT weights into the image so containers start without a download.
ARG SENTIMENT_MODEL=ProsusAI/finbert
ENV HF_HOME=/opt/hf
RUN python -c "from transformers import AutoTokenizer, AutoModelForSequenceClassification as M; \
    AutoTokenizer.from_pretrained('${SENTIMENT_MODEL}'); M.from_pretrained('${SENTIMENT_MODEL}')"

FROM python:3.11-slim AS runtime
ENV PATH="/opt/venv/bin:$PATH" \
    HF_HOME=/opt/hf \
    HF_HUB_OFFLINE=1 \
    PYTHONUNBUFFERED=1
COPY --from=build /opt/venv /opt/venv
COPY --from=build /opt/hf /opt/hf
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
USER appuser
WORKDIR /app/workers
COPY --chown=appuser:appuser env_config.py log_config.py provider_client.py retry.py ./
COPY --chown=appuser:appuser enricher/ ./enricher/
CMD ["python", "-m", "enricher.enricher_worker"]
