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
AMQP_URL: str = os.getenv(
    "AMQP_URL",
    "amqps://gvcxbqgm:Nm-4KJfWbJ73S0vHX-RZgWKcdDD0rsLW@puffin.rmq2.cloudamqp.com/gvcxbqgm"
)
RABBITMQ_QUEUE: str = "ai.analysis.queue"

# ─── Supabase / PostgreSQL ──────────────────────────────────────────────────────
SUPABASE_DB_URL: str = _require_env("SUPABASE_DB_URL")

# ─── AI Model ──────────────────────────────────────────────────────────────────
EMBEDDING_MODEL_NAME: str = "sentence-transformers/all-MiniLM-L6-v2"
EMBEDDING_DIMENSIONS: int = 384

# ─── RAG Search Config ─────────────────────────────────────────────────────────
TOP_JOBS_TO_RETURN: int = 5
SIMILARITY_THRESHOLD: float = 0.3  # Minimum cosine similarity to be considered a match
