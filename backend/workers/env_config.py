"""
Shared .env loading for all workers.

Every worker/module that reads configuration imports `require_env` (or the
typed variants) from here instead of calling `os.getenv` with an inline
default — this makes the root .env file the single source of truth and
fails loudly if a value is missing, instead of silently falling back to a
value baked into the code.
"""
from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent.parent.parent / ".env")


def _unquote(value: str) -> str:
    """Drop one pair of matching surrounding quotes.

    The shell and docker compose strip quotes from `.env` values, but the root
    Makefile's `include .env` + `export` passes them through literally, and
    load_dotenv never overrides a variable that is already set. A quoted
    USER_AGENT then went out as `"NewsMonitorBot/0.1 (...)"` and CDNs answered
    403 (seen as `make check-sources` failing 11 sources)."""
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def require_env(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    value = _unquote(value)
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def require_int(name: str) -> int:
    return int(require_env(name))


def require_float(name: str) -> float:
    return float(require_env(name))
