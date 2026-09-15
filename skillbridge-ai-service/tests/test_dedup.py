"""
The deduplicating handler's decisions, tested with a fake store.

Needs nothing installed. The SQL the real store runs -- that two claims racing
for one event cannot both win, that a lease is taken over when it expires -- is
tested against a real PostgreSQL in tests_integration/test_processed_events_store.py.
"""

import sys
import types
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from dedup import Claim, deduplicating, event_id_of  # noqa: E402
from resilient_consumer import Outcome  # noqa: E402

EVENT = uuid.UUID("5b1a3c9e-8f0d-4e7a-9c55-2d6b1e0f4a37")
CONSUMER = "test-consumer"


class FakeStore:
    def __init__(self, claim=(Claim.ACQUIRED, 1), claim_error=None, complete_error=None):
        self._claim = claim
        self._claim_error = claim_error
        self._complete_error = complete_error
        self.calls = []

    def claim(self, consumer, event_id, lease_seconds):
        self.calls.append(("claim", consumer, event_id, lease_seconds))
        if self._claim_error:
            raise self._claim_error
        return self._claim

    def complete(self, consumer, event_id):
        self.calls.append(("complete", consumer, event_id))
        if self._complete_error:
            raise self._complete_error

    def release(self, consumer, event_id, token):
        self.calls.append(("release", consumer, event_id, token))

    def purge(self, retention_days):
        self.calls.append(("purge", retention_days))
        return 0

    def names(self):
        return [c[0] for c in self.calls]


class RecordingHandler:
    def __init__(self, outcome=Outcome.PROCESSED, error=None):
        self.outcome, self.error, self.calls = outcome, error, 0

    def __call__(self, body, properties):
        self.calls += 1
        if self.error:
            raise self.error
        return self.outcome


def props(message_id=str(EVENT)):
    return types.SimpleNamespace(message_id=message_id, headers={})


def wrap(handler, store, **kwargs):
    kwargs.setdefault("lease_seconds", 30)
    return deduplicating(handler, store, CONSUMER, **kwargs)


class ClaimOutcomes(unittest.TestCase):

    def test_first_delivery_runs_the_handler_and_marks_it_done(self):
        store, handler = FakeStore(), RecordingHandler()
        self.assertIs(wrap(handler, store)(b"{}", props()), Outcome.PROCESSED)
        self.assertEqual(handler.calls, 1)
        self.assertEqual(store.calls, [("claim", CONSUMER, EVENT, 30), ("complete", CONSUMER, EVENT)])

    def test_a_duplicate_is_acked_without_running_the_handler(self):
        store, handler = FakeStore(claim=(Claim.DUPLICATE, None)), RecordingHandler()
        self.assertIs(wrap(handler, store)(b"{}", props()), Outcome.PROCESSED)
        self.assertEqual(handler.calls, 0, "a duplicate must not be processed again")
        self.assertEqual(store.names(), ["claim"])

    def test_an_event_claimed_elsewhere_is_retried_not_processed(self):
        store, handler = FakeStore(claim=(Claim.BUSY, None)), RecordingHandler()
        self.assertIs(wrap(handler, store)(b"{}", props()), Outcome.RETRY)
        self.assertEqual(handler.calls, 0)
        self.assertEqual(store.names(), ["claim"])

    def test_a_failed_claim_retries_rather_than_processing_undeduplicated(self):
        store, handler = FakeStore(claim_error=OSError("database down")), RecordingHandler()
        self.assertIs(wrap(handler, store)(b"{}", props()), Outcome.RETRY)
        self.assertEqual(handler.calls, 0, "dedup must not become optional when the database is down")


class HandlerOutcomes(unittest.TestCase):

    def test_retry_releases_the_claim_with_its_token(self):
        store, handler = FakeStore(claim=(Claim.ACQUIRED, 4)), RecordingHandler(Outcome.RETRY)
        self.assertIs(wrap(handler, store)(b"{}", props()), Outcome.RETRY)
        self.assertEqual(store.calls[1:], [("release", CONSUMER, EVENT, 4)])

    def test_dead_letter_releases_so_a_replay_is_not_skipped_as_done(self):
        store, handler = FakeStore(), RecordingHandler(Outcome.DEAD_LETTER)
        self.assertIs(wrap(handler, store)(b"{}", props()), Outcome.DEAD_LETTER)
        self.assertEqual(store.names(), ["claim", "release"])

    def test_a_raising_handler_releases_and_the_exception_still_propagates(self):
        store, handler = FakeStore(), RecordingHandler(error=RuntimeError("model failed"))
        with self.assertRaises(RuntimeError):
            wrap(handler, store)(b"{}", props())
        self.assertEqual(store.names(), ["claim", "release"])

    def test_a_failed_complete_still_acks_because_the_work_is_done(self):
        store = FakeStore(complete_error=OSError("database down"))
        self.assertIs(wrap(RecordingHandler(), store)(b"{}", props()), Outcome.PROCESSED)


class MessageIds(unittest.TestCase):

    def test_without_a_message_id_the_handler_runs_undeduplicated(self):
        for missing in (None, "", "not-a-uuid"):
            with self.subTest(message_id=missing):
                store, handler = FakeStore(), RecordingHandler()
                self.assertIs(wrap(handler, store)(b"{}", props(missing)), Outcome.PROCESSED)
                self.assertEqual(handler.calls, 1)
                self.assertEqual(store.calls, [])

    def test_event_id_is_read_from_message_id(self):
        self.assertEqual(event_id_of(props()), EVENT)
        self.assertIsNone(event_id_of(types.SimpleNamespace()))


class Purging(unittest.TestCase):

    def test_purges_at_most_once_per_interval_and_only_after_a_success(self):
        now = [1000.0]
        store = FakeStore()
        handle = wrap(RecordingHandler(), store, retention_days=7, purge_every_seconds=3600,
                      clock=lambda: now[0])

        handle(b"{}", props())
        self.assertNotIn("purge", store.names(), "no purge before the interval has passed")

        now[0] += 3600
        handle(b"{}", props())
        handle(b"{}", props())
        self.assertEqual(store.names().count("purge"), 1)
        self.assertIn(("purge", 7), store.calls)


if __name__ == "__main__":
    unittest.main()
