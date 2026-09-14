"""
resilient_consumer.py -- a RabbitMQ consumer that survives the broker going away.

The consumer this replaces opened one BlockingConnection with no retry loop,
under a docstring claiming "auto-reconnects on connection drops". It did not.
One broker restart killed the daemon thread, and FastAPI went on answering
{"status": "healthy"} for ever -- the worst kind of failure, because nothing
anywhere notices.

Deliberately free of pika and of config at import time. The connection is made
by a callable passed in, so this module can be tested with fakes on a machine
with neither a broker nor environment variables -- which is what the CI job has.

WHAT "RETRY" MEANS TODAY
    A RETRY outcome nacks the delivery without requeueing it. That hands the
    message to the queue's dead-letter exchange -- and until Phase 09 Task 2
    declares one, there is none, so the broker discards it exactly as the old
    consumer's nack did. RETRY is the right outcome to return now; the topology
    that makes it an actual retry arrives in Task 2. Nothing here pretends
    otherwise.
"""

from __future__ import annotations

import enum
import logging
import random
import threading
from typing import Any, Callable, Optional

log = logging.getLogger(__name__)


class Outcome(enum.Enum):
    """What the handler decided about one delivery."""

    #: Handled. Acknowledge it.
    PROCESSED = "processed"

    #: Failed for a reason that may pass (a database hiccup). Nack without
    #: requeue, so it goes to the dead-letter retry chain rather than straight
    #: back to the front of this queue, where a poison message would spin.
    RETRY = "retry"

    #: Can never succeed (unparseable, unknown type). Acknowledge and log;
    #: retrying a message that cannot work only blocks the ones behind it.
    DISCARD = "discard"


def backoff_seconds(attempt: int, jitter: float) -> float:
    """
    Seconds to wait before reconnect attempt ``attempt``.

    Doubles from 2s, capped at 60s, plus up to 3s of jitter. The jitter matters
    most when it seems least needed: after a broker restart every consumer
    reconnects at once, and without spreading them they arrive together and
    knock it over again.
    """
    attempt = max(1, attempt)
    return min(2 ** attempt, 60) + max(0.0, min(jitter, 3.0))


def retry_count(headers: Optional[dict]) -> int:
    """
    How many times this message has already been dead-lettered.

    RabbitMQ keeps no retry counter. It appends to an ``x-death`` header each
    time a message is dead-lettered, with a per-queue ``count``, and summing
    those is the only way to know how many rounds of the retry chain a message
    has made. Malformed entries are skipped rather than trusted.
    """
    deaths = (headers or {}).get("x-death") or []
    total = 0
    for death in deaths:
        try:
            total += int(death.get("count", 0))
        except (TypeError, ValueError, AttributeError):
            continue
    return total


class ResilientConsumer:
    """
    Consumes one queue, reconnecting with jittered backoff until stopped.

    ``handler(body, properties)`` returns an :class:`Outcome`. The consumer does
    the acking, so a handler cannot forget to, and an exception from the handler
    is treated as RETRY and never reaches pika, where it would kill the channel.
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
    ) -> None:
        self._queue = queue
        self._handler = handler
        self._connect = connect
        self._prefetch = prefetch
        self._stop = threading.Event()
        self._connected = threading.Event()
        # Waiting on the stop event, not time.sleep, is what makes a stop during
        # a 60 second backoff take effect now rather than in a minute.
        self._wait = wait if wait is not None else self._stop.wait
        self._jitter = jitter if jitter is not None else (lambda: random.uniform(0, 3))
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
                # A connection that lived and then dropped starts the backoff
                # again from the bottom; only consecutive failures to connect
                # at all should wait longer and longer.
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
                # BlockingConnection is not thread-safe: closing it from here
                # would race its own I/O loop. This schedules the close on it.
                connection.add_callback_threadsafe(connection.close)
            except Exception:  # noqa: BLE001
                log.exception("Could not schedule the AMQP connection close")

    def _consume(self) -> None:
        connection = self._connect()
        with self._lock:
            self._connection = connection
        try:
            channel = connection.channel()
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

        # Deliberately NOT caught: if ack or nack fails the channel is broken, and
        # the exception ending start_consuming is how the reconnect loop finds out.
        if outcome is Outcome.RETRY:
            channel.basic_nack(delivery_tag=tag, requeue=False)
        else:
            channel.basic_ack(delivery_tag=tag)
