"""
database.py — PostgreSQL Connection Pool
=========================================
Senior Engineering Note:
  Opening a new DB connection for EVERY query is the #1 beginner mistake.
  It's slow (TCP handshake + auth each time) and will exhaust Supabase's
  connection limits very quickly.

  We use psycopg2's built-in ThreadedConnectionPool instead:
  - Opens min_conn connections at startup
  - Reuses them across all RabbitMQ messages
  - Expands up to max_conn under load
  - Thread-safe (our RabbitMQ consumer runs in a separate thread)
"""

import psycopg2
from psycopg2 import pool
from config import AI_DATABASE_URL

# Min 2 connections ready at all times; max 2 under load.
#
# maxconn was 10. Measured 2026-09-09: Supabase fronts this project with
# Supavisor, and the pooler allows exactly 15 server connections in total --
# not 15 each. The backend's Hikari pool and this pool use the same pooler
# host, port and role, so they draw on the same 15. At 10 here plus the
# backend's 10 plus the ETL script's 1, the three of us claimed 21 against a
# ceiling of 15, and whoever asked last would have waited or failed.
#
# The backend is the user-facing claimant and takes 12; this service is
# best-effort background work, so it takes 2 -- which the comment above already
# said was plenty -- and the ETL script's single connection makes 15.
# See docs/CONNECTION_POOL.md.
_connection_pool: pool.ThreadedConnectionPool | None = None


def initialize_pool() -> None:
    """
    Called ONCE at application startup.
    Creates the connection pool. If Supabase is unreachable, fail immediately.
    """
    global _connection_pool
    print("[DB] Initializing PostgreSQL connection pool...")
    _connection_pool = psycopg2.pool.ThreadedConnectionPool(
        minconn=2,
        maxconn=2,
        dsn=AI_DATABASE_URL
    )
    print("[DB] ✓ Connection pool ready (2 connections; the shared Supavisor ceiling is 15)")


def get_connection():
    """
    Borrow a connection from the pool.
    IMPORTANT: Always call return_connection() after you are done.
    Use this with a try/finally block.
    """
    if _connection_pool is None:
        raise RuntimeError("DB pool not initialized. Call initialize_pool() first.")
    return _connection_pool.getconn()


def return_connection(conn) -> None:
    """Return a borrowed connection back to the pool for reuse."""
    if _connection_pool is not None:
        _connection_pool.putconn(conn)


def ping() -> bool:
    """
    Whether the database answers, for the /health endpoint.

    An EXHAUSTED pool counts as up. The pool has two connections and the
    consumer may be holding both mid-analysis; that means connections work, not
    that the database is down, and reporting 503 for it would page someone
    because the service was busy.
    """
    if _connection_pool is None:
        return False
    conn = None
    try:
        conn = _connection_pool.getconn()
    except pool.PoolError:
        return True
    except Exception:
        return False
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
            cur.fetchone()
        return True
    except Exception:
        return False
    finally:
        _connection_pool.putconn(conn)


def close_pool() -> None:
    """Called at application shutdown to cleanly close all connections."""
    if _connection_pool is not None:
        _connection_pool.closeall()
        print("[DB] Connection pool closed.")
