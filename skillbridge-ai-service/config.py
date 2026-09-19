"""
config.py — Single Source of Truth for All Config & Constants
==============================================================
Senior Engineering Note:
  Never scatter os.getenv() calls throughout your codebase.
  One place loads everything. If a key is missing, fail LOUDLY at startup.
  Better to crash at boot than silently fail at runtime.
"""

import os
from dotenv import load_dotenv

load_dotenv()


def _require_env(key: str) -> str:
    """Load an env variable or raise a clear error immediately at startup."""
    val = os.getenv(key)
    if not val:
        raise EnvironmentError(
            f"[FATAL] Required environment variable '{key}' is missing. "
            f"Add it to your .env file and restart."
        )
    return val


# ─── RabbitMQ ──────────────────────────────────────────────────────────────────
# No default. A hardcoded fallback here is how a live broker credential ended up
# committed to git in the first place: the value works, so nobody notices it is
# there. Missing config must fail at boot, which is exactly what _require_env does.
AMQP_URL: str = _require_env("AMQP_URL")
# Queue and exchange names live in ai_event_contract.py, checked against
# contracts/amqp/topology.json. The backend declares them; this service only connects.

# ─── PostgreSQL ─────────────────────────────────────────────────────────────────
# libpq form (postgresql://user:pass@host:port/db). Not DATABASE_URL: the backend
# reads that name as a jdbc: URL, and both services can share one .env.
AI_DATABASE_URL: str = _require_env("AI_DATABASE_URL")

# ─── AI Model ──────────────────────────────────────────────────────────────────
EMBEDDING_MODEL_NAME: str = "sentence-transformers/all-MiniLM-L6-v2"
EMBEDDING_DIMENSIONS: int = 384

# ─── RAG Search Config ─────────────────────────────────────────────────────────
TOP_JOBS_TO_RETURN: int = 5
# Minimum cosine similarity to count as a match. On the 1,500 stored jobs a
# realistic query scores a median of about 0.4-0.5 (measured 2026-09-19), so 0.3
# only drops the clearly unrelated; the top-N ranking does the real selecting.
SIMILARITY_THRESHOLD: float = 0.3
