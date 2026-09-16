"""
The Python half of the broker topology contract.

contracts/amqp/topology.json names every exchange, queue and routing key. The
backend declares them; this service connects to them. Both sides are tested
against that one file, so a rename on either side fails a build instead of
leaving a consumer listening on a queue nobody publishes to.
"""

import json
import sys
import unittest
from pathlib import Path

SERVICE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE))
import ai_event_contract as contract  # noqa: E402
import dedup  # noqa: E402

TOPOLOGY = json.loads((SERVICE.parent / "contracts" / "amqp" / "topology.json").read_text())


class TopologyContract(unittest.TestCase):

    def test_exchange_names_match(self):
        self.assertEqual(contract.EVENTS_EXCHANGE, TOPOLOGY["exchanges"]["events"]["name"])
        self.assertEqual(contract.RETRY_EXCHANGE, TOPOLOGY["exchanges"]["retry"]["name"])
        self.assertEqual(contract.DEAD_LETTER_EXCHANGE, TOPOLOGY["exchanges"]["deadLetter"]["name"])

    def test_queue_names_match(self):
        self.assertEqual(contract.AI_ANALYSIS_QUEUE, TOPOLOGY["queues"]["aiAnalysis"]["name"])
        self.assertEqual(contract.DEAD_LETTER_QUEUE, TOPOLOGY["queues"]["deadLetter"]["name"])

    def test_retry_tiers_match_in_order(self):
        # Order is the escalation. A swapped pair would retry after 5 minutes, then 30 seconds.
        self.assertEqual(list(contract.RETRY_TIER_QUEUES),
                         [t["name"] for t in TOPOLOGY["queues"]["retryTiers"]])
        self.assertEqual(contract.MAX_RETRIES, len(TOPOLOGY["queues"]["retryTiers"]))

    def test_every_tier_returns_messages_to_this_services_queue(self):
        for tier in TOPOLOGY["queues"]["retryTiers"]:
            with self.subTest(tier=tier["name"]):
                self.assertEqual(tier["arguments"]["x-dead-letter-routing-key"], contract.AI_ANALYSIS_QUEUE)

    def test_tier_ttls_escalate(self):
        ttls = [t["arguments"]["x-message-ttl"] for t in TOPOLOGY["queues"]["retryTiers"]]
        self.assertEqual(ttls, sorted(ttls))

    def test_dedup_lease_expires_before_the_retries_run_out(self):
        # A worker killed mid-event leaves a claim. Deliveries that meet it are
        # retried, and the last retry arrives after every tier's delay has passed.
        # If the lease outlived that, the event would reach the DLQ never processed.
        total_ms = sum(t["arguments"]["x-message-ttl"] for t in TOPOLOGY["queues"]["retryTiers"])
        self.assertLess(dedup.LEASE_SECONDS * 1000, total_ms,
                        "a killed worker's claim would outlast every retry of its event")

    def test_attempt_header_matches(self):
        self.assertEqual(contract.RETRY_ATTEMPT_HEADER, TOPOLOGY["retry"]["attemptHeader"])

    def test_dead_lettering_headers_match(self):
        # The backend's recorder reads these; a rename on one side would record
        # every failure with no reason and nobody would notice until they needed one.
        spec = TOPOLOGY["deadLettering"]
        self.assertEqual(contract.FAILURE_REASON_HEADER, spec["reasonHeader"])
        self.assertEqual(contract.FAILED_AT_HEADER, spec["failedAtHeader"])
        self.assertEqual(contract.FAILED_QUEUE_HEADER, spec["failedQueueHeader"])
        self.assertEqual(contract.MAX_FAILURE_REASON_LENGTH, spec["maxReasonLength"])

    def test_routing_keys_match_and_cover_every_handled_event(self):
        self.assertEqual(contract.ROUTING_KEYS, TOPOLOGY["routingKeys"])
        self.assertEqual(set(contract.ROUTING_KEYS), set(contract.HANDLED_EVENT_TYPES),
                         "a handled event with no routing key can never be delivered")

    def test_every_routing_key_reaches_this_services_queue(self):
        binding = TOPOLOGY["queues"]["aiAnalysis"]["bindingKey"]
        self.assertTrue(binding.endswith(".#"))
        prefix = binding[:-1]  # "ai."
        for event, key in contract.ROUTING_KEYS.items():
            with self.subTest(event=event):
                self.assertTrue(key.startswith(prefix), f"{key} does not match binding {binding}")


if __name__ == "__main__":
    unittest.main()
