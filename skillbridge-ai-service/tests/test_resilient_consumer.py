"""
The consumer's resilience, tested with fakes.

No pika, no broker, no environment variables -- the CI job has none of them,
and the old consumer went untested for exactly that reason. Every behaviour the
previous docstring claimed and the code did not have is asserted here.

    python3 -m unittest discover -s skillbridge-ai-service/tests -t skillbridge-ai-service -v
"""

import sys
import types
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from resilient_consumer import (  # noqa: E402
    Outcome, ResilientConsumer, backoff_seconds, retry_count,
)


class FakeChannel:
    def __init__(self, on_start=None):
        self.acks, self.nacks = [], []
        self.qos = None
        self.consumed = None
        self.callback = None
        self._on_start = on_start

    def basic_qos(self, prefetch_count):
        self.qos = prefetch_count

    def basic_consume(self, queue, on_message_callback, auto_ack):
        self.consumed = (queue, auto_ack)
        self.callback = on_message_callback

    def start_consuming(self):
        if self._on_start:
            self._on_start(self)

    def basic_ack(self, delivery_tag):
        self.acks.append(delivery_tag)

    def basic_nack(self, delivery_tag, requeue):
        self.nacks.append((delivery_tag, requeue))


class FakeConnection:
    def __init__(self, channel):
        self._channel = channel
        self.is_open = True
        self.closed = False
        self.scheduled = []

    def channel(self):
        return self._channel

    def close(self):
        self.closed = True
        self.is_open = False

    def add_callback_threadsafe(self, callback):
        self.scheduled.append(callback)


def delivery(tag=7):
    return types.SimpleNamespace(delivery_tag=tag)


class Backoff(unittest.TestCase):

    def test_doubles_then_caps_at_sixty(self):
        self.assertEqual([backoff_seconds(a, 0.0) for a in (1, 2, 3, 5, 6, 7, 30)],
                         [2, 4, 8, 32, 60, 60, 60])

    def test_jitter_adds_at_most_three_seconds(self):
        self.assertEqual(backoff_seconds(1, 3.0), 5.0)
        self.assertEqual(backoff_seconds(1, 99.0), 5.0)
        self.assertEqual(backoff_seconds(1, -5.0), 2.0)

    def test_attempt_below_one_is_treated_as_one(self):
        self.assertEqual(backoff_seconds(0, 0.0), 2)


class RetryCount(unittest.TestCase):

    def test_sums_x_death_counts_across_queues(self):
        headers = {"x-death": [{"queue": "retry.5s", "count": 2}, {"queue": "ai.analysis.queue", "count": 3}]}
        self.assertEqual(retry_count(headers), 5)

    def test_absent_headers_mean_never_retried(self):
        self.assertEqual(retry_count(None), 0)
        self.assertEqual(retry_count({}), 0)

    def test_malformed_entries_are_skipped_not_trusted(self):
        headers = {"x-death": [{"count": "not a number"}, "garbage", {"count": 4}]}
        self.assertEqual(retry_count(headers), 4)


class Reconnection(unittest.TestCase):

    def test_reconnects_after_failures_and_then_consumes(self):
        # The defect: one failed connection and the old consumer was gone for good.
        waits = []
        attempts = {"n": 0}
        consumer = None

        def on_start(channel):
            self.assertTrue(consumer.is_healthy, "healthy while actually consuming")
            consumer.stop()

        channel = FakeChannel(on_start=on_start)

        def connect():
            attempts["n"] += 1
            if attempts["n"] <= 2:
                raise ConnectionError("broker unreachable")
            return FakeConnection(channel)

        consumer = ResilientConsumer("q", lambda b, p: Outcome.PROCESSED, connect,
                                     wait=waits.append, jitter=lambda: 0.0)
        consumer.run_forever()

        self.assertEqual(attempts["n"], 3)
        self.assertEqual(waits, [2, 4], "backoff grows across consecutive failures to connect")
        self.assertEqual(channel.qos, 1)
        self.assertEqual(channel.consumed, ("q", False), "manual acks, never auto_ack")
        self.assertFalse(consumer.is_healthy, "not healthy once stopped")

    def test_a_connection_that_lived_and_dropped_restarts_the_backoff(self):
        waits = []
        rounds = {"n": 0}
        consumer = None

        def drop(channel):
            raise ConnectionResetError("broker closed the connection")

        def connect():
            rounds["n"] += 1
            if rounds["n"] == 1:
                return FakeConnection(FakeChannel(on_start=drop))
            if rounds["n"] == 2:
                raise ConnectionError("still restarting")
            consumer.stop()
            return FakeConnection(FakeChannel())

        consumer = ResilientConsumer("q", lambda b, p: Outcome.PROCESSED, connect,
                                     wait=waits.append, jitter=lambda: 0.0)
        consumer.run_forever()

        # After the drop the first wait is the base, not a continuation of an
        # earlier streak; the failed reconnect after it then doubles.
        self.assertEqual(waits, [2, 4])

    def test_unhealthy_while_disconnected(self):
        consumer = ResilientConsumer("q", lambda b, p: Outcome.PROCESSED, lambda: None)
        self.assertFalse(consumer.is_healthy)

    def test_stop_during_backoff_exits_without_another_attempt(self):
        attempts = {"n": 0}
        consumer = None

        def connect():
            attempts["n"] += 1
            raise ConnectionError("down")

        def wait(delay):
            consumer.stop()

        consumer = ResilientConsumer("q", lambda b, p: Outcome.PROCESSED, connect,
                                     wait=wait, jitter=lambda: 0.0)
        consumer.run_forever()

        self.assertEqual(attempts["n"], 1)

    def test_stop_schedules_the_close_on_the_connections_own_thread(self):
        consumer = ResilientConsumer("q", lambda b, p: Outcome.PROCESSED, lambda: None)
        connection = FakeConnection(FakeChannel())
        consumer._connection = connection

        consumer.stop()

        self.assertEqual(connection.scheduled, [connection.close],
                         "closing a BlockingConnection from another thread races its I/O loop")


class Acknowledgement(unittest.TestCase):

    def deliver(self, handler):
        channel = FakeChannel()
        consumer = ResilientConsumer("q", handler, lambda: None)
        consumer._on_message(channel, delivery(7), None, b"{}")
        return channel

    def test_processed_is_acked(self):
        channel = self.deliver(lambda b, p: Outcome.PROCESSED)
        self.assertEqual((channel.acks, channel.nacks), ([7], []))

    def test_discard_is_acked_so_it_does_not_block_the_queue(self):
        channel = self.deliver(lambda b, p: Outcome.DISCARD)
        self.assertEqual((channel.acks, channel.nacks), ([7], []))

    def test_retry_is_nacked_without_requeue(self):
        # requeue=True would put a poison message straight back at the front
        # of the queue and spin on it. Without requeue it dead-letters.
        channel = self.deliver(lambda b, p: Outcome.RETRY)
        self.assertEqual((channel.acks, channel.nacks), ([], [(7, False)]))

    def test_a_handler_exception_is_retried_and_never_escapes_to_pika(self):
        def explode(body, props):
            raise RuntimeError("database hiccup")

        channel = self.deliver(explode)  # must not raise
        self.assertEqual(channel.nacks, [(7, False)])

    def test_a_handler_returning_nonsense_is_retried_not_acked(self):
        channel = self.deliver(lambda b, p: None)
        self.assertEqual(channel.nacks, [(7, False)])


if __name__ == "__main__":
    unittest.main()
