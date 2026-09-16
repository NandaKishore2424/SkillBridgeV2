"""
The consumer against a broker that is really killed, and then started again.

tests/test_resilient_consumer.py proves the reconnect loop with fakes. This proves
it with the real client library, a real RabbitMQ and the production consumer from
amqp_consumer.build: the consumer notices the broker is gone, reports it through
is_healthy, keeps trying, comes back, and processes everything -- including what
was already in the queue when the broker died, which a quorum queue kept.

The outage lasts AI_BROKER_OUTAGE_SECONDS, 60 by default: the phase's criterion.

    python -m unittest discover -s tests_broker -t . -v      (from skillbridge-ai-service)

Needs Docker, pika and prometheus-client. Fails, never skips, without Docker.
"""

import os
import sys
import threading
import time
import unittest
import uuid
from pathlib import Path

SERVICE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE))

import ai_event_contract as contract  # noqa: E402
import amqp_consumer  # noqa: E402
from event_dispatch import Dispatcher, EventMetrics  # noqa: E402
from tests_broker import rabbit  # noqa: E402

BROKER = None


def setUpModule():
    global BROKER
    rabbit.require_docker()
    BROKER = rabbit.Broker()
    BROKER.start()


def tearDownModule():
    if BROKER is not None:
        BROKER.remove()


class ConsumerSurvivesABrokerOutage(unittest.TestCase):

    def test_the_broker_is_killed_and_the_consumer_reconnects_and_drains(self):
        outage = float(os.environ.get("AI_BROKER_OUTAGE_SECONDS", "60"))
        processed = []
        lock = threading.Lock()
        gate = threading.Event()
        gate.set()
        in_flight = threading.Event()

        def analyse(event):
            if not gate.is_set():
                in_flight.set()
                gate.wait()
            with lock:
                processed.append(event.event_id)

        def seen():
            with lock:
                return set(processed)

        dispatcher = Dispatcher({t: analyse for t in contract.SUPPORTED_SCHEMA_VERSIONS}, EventMetrics())
        consumer = amqp_consumer.build(BROKER.url, dispatcher)
        thread = threading.Thread(target=consumer.run_forever, name="consumer-under-test", daemon=True)
        thread.start()
        try:
            rabbit.wait_for(lambda: consumer.is_healthy, 30, "consumer connected")

            before = BROKER.publish(str(uuid.uuid4()) for _ in range(5))
            rabbit.wait_for(lambda: set(before) <= seen(), 30, "first five processed")

            # Hold the next delivery in the handler, so the broker dies with one
            # message in flight and the rest waiting in the queue.
            gate.clear()
            waiting = BROKER.publish(str(uuid.uuid4()) for _ in range(20))
            self.assertTrue(in_flight.wait(30), "a delivery reached the handler")

            killed = time.monotonic()
            BROKER.kill()
            gate.set()   # the handler finishes; its ack has nowhere to go
            noticed = rabbit.wait_for(lambda: not consumer.is_healthy, 90, "consumer noticed the broker is gone")

            time.sleep(max(0.0, outage - (time.monotonic() - killed)))
            self.assertFalse(consumer.is_healthy, "no broker, so not healthy -- the /health 503")
            BROKER.restart()
            back = time.monotonic()

            reconnected = rabbit.wait_for(lambda: consumer.is_healthy, 150, "consumer reconnected")
            rabbit.wait_for(lambda: set(waiting) <= seen(), 60, "every waiting message processed")
            rabbit.wait_for(lambda: BROKER.depth() == 0, 30, "queue drained")

            with lock:
                duplicates = len(processed) - len(set(processed))
            self.assertLessEqual(set(before + waiting), seen())
            # At least once: the delivery in flight when the broker died was processed,
            # never acknowledged, and delivered again. That is what dedup.py is for.
            self.assertEqual(duplicates, 1, "exactly the in-flight delivery is processed twice")

            print(f"\n[outage] {outage:g}s down: noticed after {noticed:.1f}s, reconnected "
                  f"{reconnected:.1f}s after the broker was back ({time.monotonic() - back:.1f}s to drain); "
                  f"{len(set(processed))} events, {duplicates} processed twice", file=sys.stderr)
        finally:
            consumer.stop()
            thread.join(15)


if __name__ == "__main__":
    unittest.main()
