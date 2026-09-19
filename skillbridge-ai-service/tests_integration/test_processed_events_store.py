"""
ProcessedEventStore against a real PostgreSQL, built from the backend's Flyway migrations.

The claim's whole value is that it is atomic, and a fake store answers whatever it
is told to, so only a real database can show that two racing claims do not both
win. Built from the migrations, not from a CREATE TABLE of its own, so the table
definition under test is the one deployed.

Needs AI_TEST_DATABASE_URL: a server this test may create and drop a database on.
Without it the test FAILS, it does not skip -- a suite that skips when its
database is missing reports green having tested nothing.

    docker run -d --name sb-dedup-pg -e POSTGRES_PASSWORD=dedup -e POSTGRES_USER=dedup \\
        -p 127.0.0.1:55439:5432 pgvector/pgvector:pg17
    AI_TEST_DATABASE_URL=postgresql://dedup:dedup@127.0.0.1:55439/dedup \\
        python -m unittest discover -s tests_integration -t . -v
"""

import os
import sys
import threading
import time
import unittest
import uuid
from pathlib import Path

import psycopg2
from psycopg2 import pool

SERVICE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE))

from dedup import Claim, ProcessedEventStore  # noqa: E402
from tests_integration import migrations  # noqa: E402

CONSUMER = "store-test"


def setUpModule():
    global _db, _pool
    _db = migrations.ThrowawayDatabase("dedup_it")
    _pool = pool.ThreadedConnectionPool(1, 16, _db.create())


def tearDownModule():
    if "_pool" in globals():
        _pool.closeall()
    if "_db" in globals():
        _db.drop()


def sql(statement, params=()):
    conn = _pool.getconn()
    try:
        with conn, conn.cursor() as cur:
            cur.execute(statement, params)
            return cur.fetchall() if cur.description else cur.rowcount
    finally:
        _pool.putconn(conn)


class StoreTest(unittest.TestCase):

    def setUp(self):
        sql("DELETE FROM processed_events")
        self.store = ProcessedEventStore(_pool.getconn, _pool.putconn)
        self.event = uuid.uuid4()

    def test_first_claim_acquires_and_a_second_is_busy_while_the_lease_lives(self):
        self.assertEqual(self.store.claim(CONSUMER, self.event, 60), (Claim.ACQUIRED, 1))
        self.assertEqual(self.store.claim(CONSUMER, self.event, 60), (Claim.BUSY, None))

    def test_after_complete_every_claim_is_a_duplicate(self):
        self.store.claim(CONSUMER, self.event, 60)
        self.store.complete(CONSUMER, self.event)
        self.assertEqual(self.store.claim(CONSUMER, self.event, 60), (Claim.DUPLICATE, None))
        status, completed = sql("SELECT status, completed_at FROM processed_events WHERE event_id = %s",
                                (str(self.event),))[0]
        self.assertEqual(status, "DONE")
        self.assertIsNotNone(completed)

    def test_racing_claims_for_one_event_have_exactly_one_winner(self):
        workers = 12
        barrier = threading.Barrier(workers)
        results, errors = [], []

        def contend():
            try:
                barrier.wait()
                results.append(self.store.claim(CONSUMER, self.event, 60)[0])
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)

        threads = [threading.Thread(target=contend) for _ in range(workers)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        self.assertEqual(errors, [])
        self.assertEqual(results.count(Claim.ACQUIRED), 1, results)
        self.assertEqual(results.count(Claim.BUSY), workers - 1, results)

    def test_an_expired_lease_is_taken_over_with_a_new_token(self):
        self.store.claim(CONSUMER, self.event, 0.2)
        time.sleep(0.4)
        self.assertEqual(self.store.claim(CONSUMER, self.event, 60), (Claim.ACQUIRED, 2))

    def test_a_stale_release_does_not_free_the_new_owners_claim(self):
        _, stale = self.store.claim(CONSUMER, self.event, 0.2)
        time.sleep(0.4)
        _, current = self.store.claim(CONSUMER, self.event, 60)

        self.store.release(CONSUMER, self.event, stale)
        self.assertEqual(self.store.claim(CONSUMER, self.event, 60), (Claim.BUSY, None),
                         "the worker that lost its lease released the new owner's claim")

        self.store.release(CONSUMER, self.event, current)
        self.assertEqual(self.store.claim(CONSUMER, self.event, 60), (Claim.ACQUIRED, 3))

    def test_consumers_deduplicate_independently(self):
        self.store.claim(CONSUMER, self.event, 60)
        self.store.complete(CONSUMER, self.event)
        self.assertEqual(self.store.claim("another-consumer", self.event, 60), (Claim.ACQUIRED, 1))

    def test_purge_removes_old_done_rows_and_abandoned_claims_only(self):
        old_done, new_done, abandoned, live = (uuid.uuid4() for _ in range(4))
        for e in (old_done, new_done):
            self.store.claim(CONSUMER, e, 60)
            self.store.complete(CONSUMER, e)
        self.store.claim(CONSUMER, abandoned, 60)
        self.store.claim(CONSUMER, live, 60)
        sql("UPDATE processed_events SET completed_at = now() - interval '8 days' WHERE event_id = %s",
            (str(old_done),))
        sql("UPDATE processed_events SET lease_until = now() - interval '8 days' WHERE event_id = %s",
            (str(abandoned),))

        self.assertEqual(self.store.purge(7), 2)
        remaining = {row[0] for row in sql("SELECT event_id::text FROM processed_events")}
        self.assertEqual(remaining, {str(new_done), str(live)})

    def test_the_schema_refuses_a_done_row_without_a_completion_time(self):
        with self.assertRaises(psycopg2.errors.CheckViolation):
            sql("INSERT INTO processed_events (consumer, event_id, status, lease_until) "
                "VALUES (%s, %s, 'DONE', now())", (CONSUMER, str(self.event)))


if __name__ == "__main__":
    unittest.main()
