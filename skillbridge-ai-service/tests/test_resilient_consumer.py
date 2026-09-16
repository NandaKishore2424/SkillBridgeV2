"""
The consumer's resilience and retry scheduling, tested with fakes.

No pika, no broker, no environment variables -- the CI job has none of them.
MessagingTopologyBrokerTest (Java) checks the broker side of the same design
against a real RabbitMQ: that a tier really returns a message after its TTL, and
that a nack really lands in the DLQ.

    python3 -m unittest discover -s skillbridge-ai-service/tests -t skillbridge-ai-service -v
"""

import sys
import types
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from resilient_consumer import (  # noqa: E402
    Outcome, PermanentFailure, ResilientConsumer, backoff_seconds, retry_attempt, retry_route,
)

TIERS = ("tier.5s", "tier.30s", "tier.5m")
HEADER = "x-retry-attempt"


class FakeChannel:
    def __init__(self, on_start=None, publish_error=None):
        self.acks, self.nacks, self.publishes = [], [], []
        self.qos = None
        self.consumed = None
        self.confirming = False
        self._on_start = on_start
        self._publish_error = publish_error

    def confirm_delivery(self):
        self.confirming = True

    def basic_qos(self, prefetch_count):
        self.qos = prefetch_count

    def basic_consume(self, queue, on_message_callback, auto_ack):
        self.consumed = (queue, auto_ack)

    def start_consuming(self):
        if self._on_start:
            self._on_start(self)

    def basic_ack(self, delivery_tag):
        self.acks.append(delivery_tag)

    def basic_nack(self, delivery_tag, requeue):
        self.nacks.append((delivery_tag, requeue))

    def basic_publish(self, exchange, routing_key, body, properties, mandatory):
        if self._publish_error:
            raise self._publish_error
        self.publishes.append(dict(exchange=exchange, routing_key=routing_key, body=body,
                                   headers=dict(properties.headers), mandatory=mandatory))


class FakeConnection:
    def __init__(self, channel):
        self._channel = channel
        self.is_open = True
        self.scheduled = []

    def channel(self):
        return self._channel

    def close(self):
        self.is_open = False

    def add_callback_threadsafe(self, callback):
        self.scheduled.append(callback)


def delivery(tag=7, routing_key="ai.skill.updated"):
    return types.SimpleNamespace(delivery_tag=tag, routing_key=routing_key)


def props(**headers):
    return types.SimpleNamespace(headers=headers or None, message_id="evt-1")


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


class RetryArithmetic(unittest.TestCase):

    def test_attempt_is_read_from_the_header(self):
        self.assertEqual(retry_attempt({HEADER: 2}, HEADER), 2)
        self.assertEqual(retry_attempt({HEADER: "2"}, HEADER), 2)

    def test_absent_malformed_or_negative_means_no_retries_yet(self):
        for headers in (None, {}, {HEADER: "garbage"}, {HEADER: None}, {HEADER: -4}):
            with self.subTest(headers=headers):
                self.assertEqual(retry_attempt(headers, HEADER), 0)

    def test_tiers_escalate_then_run_out(self):
        self.assertEqual([retry_route(a, TIERS) for a in (0, 1, 2, 3, 9)],
                         ["tier.5s", "tier.30s", "tier.5m", None, None])


class Reconnection(unittest.TestCase):

    def test_reconnects_after_failures_and_then_consumes(self):
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
                                     wait=waits.append, jitter=lambda: 0.0,
                                     retry_exchange="retry", retry_queues=TIERS)
        consumer.run_forever()

        self.assertEqual(attempts["n"], 3)
        self.assertEqual(waits, [2, 4])
        self.assertEqual(channel.qos, 1)
        self.assertEqual(channel.consumed, ("q", False), "manual acks, never auto_ack")
        self.assertTrue(channel.confirming, "retry republishes need confirm mode to fail loudly")
        self.assertFalse(consumer.is_healthy, "not healthy once stopped")

    def test_confirm_mode_when_only_dead_lettering_is_configured(self):
        consumer = None
        channel = FakeChannel(on_start=lambda ch: consumer.stop())
        consumer = ResilientConsumer("q", lambda b, p: Outcome.PROCESSED, lambda: FakeConnection(channel),
                                     dead_letter_exchange="dlx")
        consumer.run_forever()
        self.assertTrue(channel.confirming, "an unconfirmed publish to the DLX could be acked and lost")

    def test_no_confirm_mode_without_a_retry_exchange(self):
        consumer = None
        channel = FakeChannel(on_start=lambda ch: consumer.stop())
        consumer = ResilientConsumer("q", lambda b, p: Outcome.PROCESSED, lambda: FakeConnection(channel))
        consumer.run_forever()
        self.assertFalse(channel.confirming)

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

        consumer = ResilientConsumer("q", lambda b, p: Outcome.PROCESSED, connect,
                                     wait=lambda d: consumer.stop(), jitter=lambda: 0.0)
        consumer.run_forever()
        self.assertEqual(attempts["n"], 1)

    def test_a_delivery_after_stop_is_left_unacknowledged_for_the_next_consumer(self):
        # Graceful shutdown must not start new work. Acking would lose the message;
        # nacking with requeue could bounce it back here until the close runs.
        handled = []
        consumer = ResilientConsumer("q", lambda b, p: handled.append(b) or Outcome.PROCESSED, lambda: None,
                                     retry_exchange="retry", retry_queues=TIERS, dead_letter_exchange="dlx")
        consumer.stop()
        channel = FakeChannel()
        consumer._on_message(channel, delivery(9), props(), b"{}")
        self.assertEqual((handled, channel.acks, channel.nacks, channel.publishes), ([], [], [], []))

    def test_stop_schedules_the_close_on_the_connections_own_thread(self):
        consumer = ResilientConsumer("q", lambda b, p: Outcome.PROCESSED, lambda: None)
        connection = FakeConnection(FakeChannel())
        consumer._connection = connection
        consumer.stop()
        self.assertEqual(connection.scheduled, [connection.close])


class Outcomes(unittest.TestCase):

    def deliver(self, handler, properties=None, channel=None, retry=True):
        channel = channel or FakeChannel()
        kwargs = dict(retry_exchange="retry", retry_queues=TIERS) if retry else {}
        consumer = ResilientConsumer("q", handler, lambda: None, **kwargs)
        consumer._on_message(channel, delivery(7), properties if properties is not None else props(), b'{"x":1}')
        return channel

    def test_processed_is_acked(self):
        ch = self.deliver(lambda b, p: Outcome.PROCESSED)
        self.assertEqual((ch.acks, ch.nacks, ch.publishes), ([7], [], []))

    def test_dead_letter_is_nacked_without_requeue_so_the_dlq_gets_it(self):
        # requeue=True would put it straight back and spin; acking it would lose it.
        ch = self.deliver(lambda b, p: Outcome.DEAD_LETTER)
        self.assertEqual((ch.acks, ch.nacks, ch.publishes), ([], [(7, False)], []))

    def test_first_retry_goes_to_the_first_tier_and_then_acks(self):
        ch = self.deliver(lambda b, p: Outcome.RETRY, props(traceId="t-1"))
        self.assertEqual(ch.publishes, [dict(exchange="retry", routing_key="tier.5s", body=b'{"x":1}',
                                             headers={"traceId": "t-1", HEADER: 1}, mandatory=True)])
        self.assertEqual((ch.acks, ch.nacks), ([7], []), "ack only after the republish succeeded")

    def test_retries_escalate_through_the_tiers(self):
        for already, expected_route in ((1, "tier.30s"), (2, "tier.5m")):
            with self.subTest(already=already):
                ch = self.deliver(lambda b, p: Outcome.RETRY, props(**{HEADER: already}))
                self.assertEqual(ch.publishes[0]["routing_key"], expected_route)
                self.assertEqual(ch.publishes[0]["headers"][HEADER], already + 1)

    def test_a_message_that_used_every_tier_is_dead_lettered(self):
        ch = self.deliver(lambda b, p: Outcome.RETRY, props(**{HEADER: 3}))
        self.assertEqual((ch.acks, ch.nacks, ch.publishes), ([], [(7, False)], []))

    def test_a_retry_that_cannot_be_published_is_dead_lettered_not_acked(self):
        ch = self.deliver(lambda b, p: Outcome.RETRY, channel=FakeChannel(publish_error=RuntimeError("unroutable")))
        self.assertEqual((ch.acks, ch.nacks), ([], [(7, False)]))

    def test_without_retry_configured_a_retry_is_dead_lettered_not_dropped(self):
        ch = self.deliver(lambda b, p: Outcome.RETRY, retry=False)
        self.assertEqual((ch.acks, ch.nacks), ([], [(7, False)]))

    def test_the_original_properties_are_not_mutated(self):
        original = props(traceId="t-1")
        self.deliver(lambda b, p: Outcome.RETRY, original)
        self.assertEqual(original.headers, {"traceId": "t-1"})

    def test_a_handler_exception_is_retried_and_never_escapes_to_pika(self):
        def explode(body, properties):
            raise RuntimeError("database hiccup")

        ch = self.deliver(explode)
        self.assertEqual(ch.publishes[0]["routing_key"], "tier.5s")
        self.assertEqual(ch.acks, [7])

    def test_a_handler_returning_nonsense_is_retried_not_acked_as_done(self):
        ch = self.deliver(lambda b, p: None)
        self.assertEqual(len(ch.publishes), 1)


class DeadLetteringWithAReason(unittest.TestCase):
    """With a dead-letter exchange configured, a dead letter says why it died."""

    NOW = 1_789_000_000.123

    def deliver(self, handler, properties=None, channel=None, retry=True, max_reason_length=2000):
        channel = channel or FakeChannel()
        kwargs = dict(retry_exchange="retry", retry_queues=TIERS) if retry else {}
        consumer = ResilientConsumer("q.main", handler, lambda: None, dead_letter_exchange="dlx",
                                     max_reason_length=max_reason_length, clock=lambda: self.NOW, **kwargs)
        consumer._on_message(channel, delivery(7), properties if properties is not None else props(), b'{"x":1}')
        return channel

    def test_a_permanent_failure_is_published_with_its_reason_then_acked(self):
        def reject(body, properties):
            raise PermanentFailure("unknown event type 'NOPE'")

        ch = self.deliver(reject, props(traceId="t-1"))
        self.assertEqual(ch.publishes, [dict(
            exchange="dlx", routing_key="ai.skill.updated", body=b'{"x":1}', mandatory=True,
            headers={"traceId": "t-1", "x-failure-reason": "unknown event type 'NOPE'",
                     "x-failed-at": 1_789_000_000_123, "x-failed-queue": "q.main"})])
        self.assertEqual((ch.acks, ch.nacks), ([7], []), "acked only because the publish succeeded")

    def test_exhausted_retries_record_the_last_failure(self):
        def explode(body, properties):
            raise RuntimeError("db down")

        ch = self.deliver(explode, props(**{HEADER: 3}))
        reason = ch.publishes[0]["headers"]["x-failure-reason"]
        self.assertEqual(ch.publishes[0]["exchange"], "dlx")
        self.assertIn("retries exhausted after 3 attempts", reason)
        self.assertIn("RuntimeError: db down", reason)
        self.assertEqual(ch.publishes[0]["headers"][HEADER], 3, "the retry count travels to the DLQ")

    def test_a_failed_dead_letter_publish_falls_back_to_a_nack(self):
        # The nack still reaches the DLQ through the queue's DLX -- without the reason.
        # Acking here would lose the message.
        ch = self.deliver(lambda b, p: (_ for _ in ()).throw(PermanentFailure("bad")),
                          channel=FakeChannel(publish_error=RuntimeError("unroutable")))
        self.assertEqual((ch.acks, ch.nacks), ([], [(7, False)]))

    def test_a_returned_dead_letter_says_so(self):
        ch = self.deliver(lambda b, p: Outcome.DEAD_LETTER)
        self.assertEqual(ch.publishes[0]["headers"]["x-failure-reason"],
                         "handler returned DEAD_LETTER without a reason")

    def test_the_reason_is_cut_to_the_contract_length(self):
        ch = self.deliver(lambda b, p: (_ for _ in ()).throw(PermanentFailure("x" * 500)), max_reason_length=60)
        self.assertEqual(len(ch.publishes[0]["headers"]["x-failure-reason"]), 60)

    def test_without_properties_it_is_still_dead_lettered_by_nack(self):
        consumer = ResilientConsumer("q", lambda b, p: Outcome.DEAD_LETTER, lambda: None,
                                     dead_letter_exchange="dlx")
        ch = FakeChannel()
        consumer._on_message(ch, delivery(7), None, b"x")
        self.assertEqual((ch.acks, ch.nacks, ch.publishes), ([], [(7, False)], []))

    def test_the_original_headers_are_not_mutated(self):
        original = props(traceId="t-1")
        self.deliver(lambda b, p: Outcome.DEAD_LETTER, original)
        self.assertEqual(original.headers, {"traceId": "t-1"})


if __name__ == "__main__":
    unittest.main()
