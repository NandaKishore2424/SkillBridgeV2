"""
Builds a test database the way every other database is built: by running the
backend's Flyway migrations, V1 upward, in order.

These tests used to load db/schema/baseline.sql, the capture Flyway's V1 was
generated from. That file stopped at V1, so a table added later (skill_gap_reports,
V7) did not exist in it. The migrations are what deploys, so they are what is
tested against.
"""

import re
from pathlib import Path

MIGRATIONS = Path(__file__).resolve().parents[2] / "skillbridge-backend" / "src" / "main" / "resources" / "db" / "migration"


def ordered():
    """V<N>__*.sql, by N as a number (V10 after V9, not after V1)."""
    files = [(int(re.match(r"V(\d+)__", p.name).group(1)), p) for p in MIGRATIONS.glob("V*__*.sql")]
    if not files:
        raise RuntimeError(f"no migrations found in {MIGRATIONS}")
    return [p for _, p in sorted(files)]


def apply_all(cursor):
    for migration in ordered():
        cursor.execute(migration.read_text())


# ─── A throwaway database per test module ─────────────────────────────────────

import os  # noqa: E402
import time  # noqa: E402
import uuid  # noqa: E402

import psycopg2  # noqa: E402


class ThrowawayDatabase:
    """
    A fresh database on the server at AI_TEST_DATABASE_URL, migrated, and
    dropped again. Fails -- never skips -- when that variable is missing: a suite
    that skips when its database is absent reports green having tested nothing.
    """

    def __init__(self, prefix: str):
        self.admin_dsn = os.environ.get("AI_TEST_DATABASE_URL")
        if not self.admin_dsn:
            raise RuntimeError("AI_TEST_DATABASE_URL is not set: this suite needs a throwaway PostgreSQL "
                               "(see the module docstring). It fails rather than skipping.")
        # This creates and drops databases. Refuse the retired live project outright.
        for forbidden in ("supabase", "pooler"):
            if forbidden in self.admin_dsn:
                raise RuntimeError("AI_TEST_DATABASE_URL points at Supabase; this suite must never run there")
        self.name = f"{prefix}_{uuid.uuid4().hex[:12]}"
        self.dsn = psycopg2.extensions.make_dsn(self.admin_dsn, dbname=self.name)

    def create(self) -> str:
        _wait_until_ready(self.admin_dsn)
        self._admin(f'CREATE DATABASE "{self.name}"')
        with psycopg2.connect(self.dsn) as conn, conn.cursor() as cur:
            apply_all(cur)
        return self.dsn

    def drop(self) -> None:
        self._admin(f'DROP DATABASE IF EXISTS "{self.name}"')

    def _admin(self, statement: str) -> None:
        admin = psycopg2.connect(self.admin_dsn)
        admin.autocommit = True
        try:
            with admin.cursor() as cur:
                cur.execute(statement)
        finally:
            admin.close()


def _wait_until_ready(dsn, timeout=60):
    # pg_isready is not readiness: the image's entrypoint runs a temporary server
    # for initialisation, answers during it, then shuts it down. Three successes a
    # second apart step over that window (the same trap as verify-schema-baseline.sh).
    deadline, streak = time.monotonic() + timeout, 0
    while streak < 3:
        if time.monotonic() > deadline:
            raise RuntimeError(f"PostgreSQL at AI_TEST_DATABASE_URL was not ready within {timeout}s")
        try:
            psycopg2.connect(dsn, connect_timeout=2).close()
            streak += 1
        except psycopg2.OperationalError:
            streak = 0
        time.sleep(1)
