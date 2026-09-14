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

    def test_attempt_header_matches(self):
        self.assertEqual(contract.RETRY_ATTEMPT_HEADER, TOPOLOGY["retry"]["attemptHeader"])

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
