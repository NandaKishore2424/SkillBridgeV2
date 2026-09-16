"""
The dispatcher: every delivery decoded, version-checked, counted, and handled or refused.

Needs `prometheus_client`, and nothing else beyond the standard library.
"""

import json
import sys
import unittest
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE_ROOT))

import ai_event_contract as contract  # noqa: E402
from event_dispatch import Dispatcher, EventMetrics  # noqa: E402
from prometheus_client import generate_latest  # noqa: E402
from resilient_consumer import Outcome, PermanentFailure  # noqa: E402

CONTRACTS = SERVICE_ROOT.parent / "contracts" / "ai-events"


def example(path, **changes):
    body = json.loads((CONTRACTS / path).read_text())
    body.update(changes)
    return json.dumps(body).encode()


class Recorder:
    def __init__(self, error=None):
        self.events, self.error = [], error

    def __call__(self, event):
        self.events.append(event)
        if self.error:
            raise self.error


def dispatcher(handler=None):
    handler = handler or Recorder()
    metrics = EventMetrics()
    return Dispatcher({t: handler for t in contract.SUPPORTED_SCHEMA_VERSIONS}, metrics), handler, metrics


class Dispatching(unittest.TestCase):

    def test_each_version_reaches_the_handler_and_is_counted_under_its_version(self):
        dispatch, handler, metrics = dispatcher()

        self.assertIs(dispatch(example("v1/skill-updated.example.json")), Outcome.PROCESSED)
        self.assertIs(dispatch(example("v2/skill-updated.example.json")), Outcome.PROCESSED)
        self.assertIs(dispatch(example("v2/skill-updated.example.json")), Outcome.PROCESSED)

        self.assertEqual([e.schema_version for e in handler.events], [1, 2, 2])
        self.assertEqual(metrics.value("SKILL_UPDATED", "1", "processed"), 1)
        self.assertEqual(metrics.value("SKILL_UPDATED", "2", "processed"), 2)

    def test_an_unsupported_version_is_refused_loudly_and_counted(self):
        dispatch, handler, metrics = dispatcher()

        with self.assertRaises(PermanentFailure) as caught:
            dispatch(example("v2/profile-updated.example.json", schemaVersion=3))

        self.assertIn("unsupported schemaVersion 3 for PROFILE_UPDATED", str(caught.exception))
        self.assertEqual(handler.events, [], "the handler never sees a version it was not written for")
        self.assertEqual(metrics.value("PROFILE_UPDATED", "3", "unsupported_version"), 1)

    def test_refusals_are_counted_without_minting_series_from_hostile_values(self):
        dispatch, _, metrics = dispatcher()
        for body in (b"not json", example("v2/skill-updated.example.json", eventType=["x"]),
                     example("v2/skill-updated.example.json", eventType="WHATEVER", schemaVersion=10 ** 9)):
            with self.assertRaises(PermanentFailure):
                dispatch(body)

        self.assertEqual(metrics.value("other", "invalid", "malformed"), 1)
        self.assertEqual(metrics.value("other", "2", "unknown_type"), 1)
        self.assertEqual(metrics.value("other", "invalid", "unknown_type"), 1)

    def test_a_failing_handler_is_counted_and_its_error_propagates_for_a_retry(self):
        dispatch, _, metrics = dispatcher(Recorder(error=ConnectionError("database down")))

        with self.assertRaises(ConnectionError):
            dispatch(example("v2/skill-updated.example.json"))

        self.assertEqual(metrics.value("SKILL_UPDATED", "2", "failed"), 1)
        self.assertEqual(metrics.value("SKILL_UPDATED", "2", "processed"), 0)

    def test_every_accepted_type_needs_a_handler_before_anything_starts(self):
        with self.assertRaises(ValueError):
            Dispatcher({contract.EVENT_SKILL_UPDATED: Recorder()}, EventMetrics())

    def test_the_counter_is_exposed_in_prometheus_format(self):
        dispatch, _, metrics = dispatcher()
        dispatch(example("v2/skill-updated.example.json"))

        text = generate_latest(metrics.registry).decode()

        self.assertIn('ai_events_consumed_total{event_type="SKILL_UPDATED",outcome="processed",'
                      'schema_version="2"} 1.0', text)


if __name__ == "__main__":
    unittest.main()
