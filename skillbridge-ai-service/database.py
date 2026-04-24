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
from config import SUPABASE_DB_URL

# Min 2 connections ready at all times; max 10 under heavy load
# For our use case, 2 is plenty but this is the correct pattern
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
        maxconn=10,
        dsn=SUPABASE_DB_URL
    )
    print("[DB] ✓ Connection pool ready (2 warm connections to Supabase)")


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


def close_pool() -> None:
    """Called at application shutdown to cleanly close all connections."""
    if _connection_pool is not None:
        _connection_pool.closeall()
        print("[DB] Connection pool closed.")
