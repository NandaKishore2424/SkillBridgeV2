"""
embedder.py — Singleton Embedding Model Wrapper
=================================================
Senior Engineering Note:
  Loading the MiniLM model takes ~3 seconds and ~300MB of RAM.
  If we loaded it inside the callback() function, it would reload on EVERY
  RabbitMQ message, making the service extremely slow and memory-hungry.

  The Singleton pattern ensures the model is loaded ONCE at startup and then
  reused for every subsequent request. This is how production ML services work.
"""

from langchain_huggingface import HuggingFaceEmbeddings
from config import EMBEDDING_MODEL_NAME

# Module-level singleton (loaded once, lives forever)
_embedder: HuggingFaceEmbeddings | None = None


def initialize_embedder() -> None:
    """
    Called ONCE at application startup.
    Downloads the model if not cached (~90MB first time), then loads it into RAM.
    """
    global _embedder
    print(f"[EMBEDDER] Loading model: {EMBEDDING_MODEL_NAME}")
    print("[EMBEDDER] First run will download ~90MB. Subsequent runs use local cache.")
    _embedder = HuggingFaceEmbeddings(
        model_name=EMBEDDING_MODEL_NAME,
        model_kwargs={"device": "cpu"},
        encode_kwargs={"normalize_embeddings": True}  # Required for cosine similarity math
    )
    print("[EMBEDDER] ✓ Model loaded and ready")


def embed_text(text: str) -> list[float]:
    """
    Convert a raw text string into a 384-dimensional vector.
    This is the same function the ingest.py ETL pipeline used to build the DB,
    so the vectors will be mathematically comparable (same model = same space).

    Args:
        text: A plain English string (e.g., "Java Spring Boot PostgreSQL")
    Returns:
        A list of 384 float numbers representing the meaning of the text
    """
    if _embedder is None:
        raise RuntimeError("Embedder not initialized. Call initialize_embedder() first.")
    return _embedder.embed_query(text)
