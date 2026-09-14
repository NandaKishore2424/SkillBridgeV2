"""
resilient_consumer.py -- a RabbitMQ consumer that survives the broker going away,
and retries a failed message on an escalating delay instead of losing it.

The consumer this replaced opened one BlockingConnection with no retry loop,
under a docstring claiming "auto-reconnects on connection drops". It did not.
One broker restart killed the daemon thread, and the service went on answering
{"status": "healthy"} for ever.

Deliberately free of pika and of config at import time. The connection is made
by a callable passed in, so this is tested with fakes on a machine with neither a
broker nor environment variables -- which is what the CI job has.

HOW A RETRY WORKS (Phase 09 Task 2)
    A RETRY republishes a copy of the message to the retry exchange, routed to the
    delay queue for its attempt number (5s, then 30s, then 5m), with the attempt
    count in a header, and only then acks the original. The delay queue has a TTL
    and no consumer: when the TTL runs out the broker dead-letters the message
    straight back into this consumer's queue. After the last tier the message is
    nacked without requeue, and the queue's dead-letter exchange puts it in the
    DLQ, where a person can see it.

    The consumer picks the tier because the broker cannot. The phase document
    gave the main queue one static x-dead-letter-routing-key, so every nack went
    to the 5s queue and the other tiers were unreachable.

    Republish-then-ack is at-least-once: dying between the two delivers the
    message twice. That is why consumers deduplicate on the event id.

    If the republish itself fails -- the tier is unroutable, or the broker nacks
    it -- the message is dead-lettered rather than acked. A retry that cannot be
    scheduled must not become a message that silently disappears.
"""

from __future__ import annotations

import copy
import enum
import logging
import random
import threading
from typing import Any, Callable, Optional, Sequence

log = logging.getLogger(__name__)


class Outcome(enum.Enum):
    """What the handler decided about one delivery."""

    #: Handled. Acknowledge it.
    PROCESSED = "processed"

    #: Failed for a reason that may pass (a database hiccup). Scheduled on the
    #: next retry tier, or dead-lettered once every tier has been used.
    RETRY = "retry"

    #: Can never succeed (unparseable, unknown type). Nacked without requeue so
    #: the dead-letter exchange puts it in the DLQ. Retrying a message that cannot
    #: work only blocks the ones behind it; dropping it would hide the problem.
    DEAD_LETTER = "dead_letter"


def backoff_seconds(attempt: int, jitter: float) -> float:
    """
    Seconds to wait before reconnect attempt ``attempt``.

    Doubles from 2s, capped at 60s, plus up to 3s of jitter. The jitter matters
    most when it seems least needed: after a broker restart every consumer
    reconnects at once, and without spreading them they knock it over again.
    """
    attempt = max(1, attempt)
    return min(2 ** attempt, 60) + max(0.0, min(jitter, 3.0))


def retry_attempt(headers: Optional[dict], header: str) -> int:
    """How many retries this message has already had. Absent or malformed means none."""
    try:
        value = int((headers or {}).get(header, 0))
    except (TypeError, ValueError):
        return 0
    return max(0, value)


def retry_route(attempt: int, tiers: Sequence[str]) -> Optional[str]:
    """The delay queue for a message that has had ``attempt`` retries, or None when every tier is used."""
    if 0 <= attempt < len(tiers):
        return tiers[attempt]
    return None


class ResilientConsumer:
    """
    Consumes one queue, reconnecting with jittered backoff until stopped.

    ``handler(body, properties)`` returns an :class:`Outcome`. The consumer does
    the acking, so a handler cannot forget to, and an exception from the handler
    is treated as RETRY and never reaches pika, where it would kill the channel.

    Without ``retry_exchange`` a RETRY is dead-lettered, never silently dropped.
    """

    def __init__(
        self,
        queue: str,
        handler: Callable[[bytes, Any], Outcome],
        connect: Callable[[], Any],
        *,
        prefetch: int = 1,
        wait: Optional[Callable[[float], Any]] = None,
        jitter: Optional[Callable[[], float]] = None,
        retry_exchange: Optional[str] = None,
        retry_queues: Sequence[str] = (),
        attempt_header: str = "x-retry-attempt",
    ) -> None:
        self._queue = queue
        self._handler = handler
        self._connect = connect
        self._prefetch = prefetch
        self._stop = threading.Event()
        self._connected = threading.Event()
        # Waiting on the stop event, not time.sleep, is what makes a stop during a
        # 60 second backoff take effect now rather than in a minute.
        self._wait = wait if wait is not None else self._stop.wait
        self._jitter = jitter if jitter is not None else (lambda: random.uniform(0, 3))
        self._retry_exchange = retry_exchange
        self._retry_queues = tuple(retry_queues)
        self._attempt_header = attempt_header
        self._lock = threading.Lock()
        self._connection: Any = None
        self._round_connected = False

    @property
    def is_healthy(self) -> bool:
        """True only while connected and consuming. What /health reports."""
        return self._connected.is_set() and not self._stop.is_set()

    def run_forever(self) -> None:
        """Blocks until :meth:`stop`. Meant for a background thread."""
        attempt = 0
        while not self._stop.is_set():
            self._round_connected = False
            try:
                self._consume()
                attempt = 0
            except Exception as exc:  # noqa: BLE001 -- any failure means reconnect
                self._connected.clear()
                if self._stop.is_set():
                    break
                # A connection that lived and then dropped restarts the backoff from
                # the bottom; only consecutive failures to connect wait longer.
                attempt = 1 if self._round_connected else attempt + 1
                delay = backoff_seconds(attempt, self._jitter())
                log.warning("AMQP consumer lost its connection (attempt %d): %s; reconnecting in %.1fs",
                            attempt, exc, delay)
                self._wait(delay)
        self._connected.clear()
        log.info("AMQP consumer stopped")

    def stop(self) -> None:
        """Stops consuming. Safe from any thread; a running delivery finishes first."""
        self._stop.set()
        with self._lock:
            connection = self._connection
        if connection is not None:
            try:
                # BlockingConnection is not thread-safe: closing it from here would race
                # its own I/O loop. This schedules the close on it.
                connection.add_callback_threadsafe(connection.close)
            except Exception:  # noqa: BLE001
                log.exception("Could not schedule the AMQP connection close")

    def _consume(self) -> None:
        connection = self._connect()
        with self._lock:
            self._connection = connection
        try:
            channel = connection.channel()
            if self._retry_exchange is not None:
                # Confirm mode, so a republish to a retry tier either reaches a queue
                # or raises -- and a retry that raises is dead-lettered, not lost.
                channel.confirm_delivery()
            channel.basic_qos(prefetch_count=self._prefetch)
            channel.basic_consume(queue=self._queue, on_message_callback=self._on_message, auto_ack=False)
            self._round_connected = True
            self._connected.set()
            log.info("AMQP consumer listening on '%s' (prefetch=%d)", self._queue, self._prefetch)
            channel.start_consuming()
        finally:
            self._connected.clear()
            with self._lock:
                self._connection = None
            try:
                if getattr(connection, "is_open", False):
                    connection.close()
            except Exception:  # noqa: BLE001
                pass

    def _on_message(self, channel: Any, method: Any, properties: Any, body: bytes) -> None:
        tag = method.delivery_tag
        try:
            outcome = self._handler(body, properties)
        except Exception:  # noqa: BLE001
            log.exception("Handler raised on delivery %s; treating it as transient", tag)
            outcome = Outcome.RETRY

        if not isinstance(outcome, Outcome):
            log.error("Handler returned %r, not an Outcome; treating it as RETRY", outcome)
            outcome = Outcome.RETRY

        # Deliberately NOT caught: if ack or nack fails the channel is broken, and the
        # exception ending start_consuming is how the reconnect loop finds out. The
        # unacked message is then redelivered.
        if outcome is Outcome.PROCESSED:
            channel.basic_ack(delivery_tag=tag)
        elif outcome is Outcome.RETRY and self._schedule_retry(channel, properties, body, tag):
            channel.basic_ack(delivery_tag=tag)
        else:
            channel.basic_nack(delivery_tag=tag, requeue=False)

    def _schedule_retry(self, channel: Any, properties: Any, body: bytes, tag: Any) -> bool:
        """Republishes to the next tier. False means dead-letter it instead."""
        if self._retry_exchange is None or properties is None:
            return False

        headers = getattr(properties, "headers", None) or {}
        attempt = retry_attempt(headers, self._attempt_header)
        route = retry_route(attempt, self._retry_queues)
        if route is None:
            log.error("Delivery %s failed after %d retries; dead-lettering it", tag, attempt)
            return False

        retried = copy.copy(properties)
        retried.headers = {**headers, self._attempt_header: attempt + 1}
        try:
            channel.basic_publish(exchange=self._retry_exchange, routing_key=route, body=body,
                                  properties=retried, mandatory=True)
        except Exception:  # noqa: BLE001
            log.exception("Could not schedule retry %d for delivery %s; dead-lettering it instead",
                          attempt + 1, tag)
            return False

        log.warning("Delivery %s failed; retry %d of %d scheduled via %s",
                    tag, attempt + 1, len(self._retry_queues), route)
        return True
