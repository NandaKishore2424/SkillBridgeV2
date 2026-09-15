"""
dedup.py -- process each event once, although RabbitMQ delivers it at least once.

WHERE DUPLICATES COME FROM
    Every hop in this pipeline is at-least-once, on purpose:
      - OutboxRelay publishes, then records it. A relay that dies between the two
        publishes the event again when its lease runs out.
      - ResilientConsumer republishes a retry, then acks the original. Dying
        between the two delivers both.
      - An ack lost with its connection redelivers a message that was handled.
    The relay stamps every message with the outbox event id as message_id, and a
    duplicate carries the same one. That id is what this module keys on.

WHY A CLAIM, AND NOT "CHECK, RUN, MARK"
    The phase document's version asked "already processed?", ran the handler, then
    marked the event done. Two deliveries of one event arriving together -- a
    redelivery to a second instance while the first is still working, which is
    exactly when duplicates happen -- both get "no" and both run. Its own comment
    called SET NX atomic, but the check was not SET NX; the mark was, and the mark
    comes after the work.

    Here the first step is the atomic one. claim() is a single INSERT .. ON
    CONFLICT: exactly one caller gets ACQUIRED, and everyone else learns whether
    the event is DONE (a duplicate: ack it and do nothing) or IN_PROGRESS under a
    live lease (someone is on it: retry later, and by then it will be DONE).

WHY A LEASE
    A worker that claims an event and is then killed must not block that event for
    ever. The claim expires after lease_seconds, and the next delivery takes it
    over. For that to happen before the message runs out of retry tiers, the lease
    must be shorter than the tiers' total delay -- test_amqp_topology_contract.py
    asserts it against contracts/amqp/topology.json.

    The residual risk is stated rather than hidden: a handler that runs LONGER than
    the lease can overlap a second worker that took the claim over. The handler
    has to tolerate that, which is why "effectively once" is about processing
    cost, not a transactional guarantee.

WHY POSTGRES AND NOT REDIS
    The phase document assumed Redis. This project has none, by design, and adding
    a datastore to deduplicate a handful of events a minute is not a trade worth
    making. The service already holds a Postgres pool.

No connection is held while the handler runs. claim() borrows one for a single
statement and returns it, so the handler -- which borrows its own from a pool of
two -- is never starved by the thing deciding whether it should run.
"""

from __future__ import annotations

import enum
import logging
import time
import uuid
from typing import Any, Callable, Optional

from resilient_consumer import Outcome

log = logging.getLogger(__name__)

#: How long a claim protects an event from a second worker. Longer than an
#: analysis takes, and shorter than the retry tiers' total delay (asserted in
#: test_amqp_topology_contract.py), so a killed worker's event is taken over
#: before it runs out of retries.
LEASE_SECONDS = 120


class Claim(enum.Enum):
    #: This caller owns the event and should process it.
    ACQUIRED = "acquired"
    #: Already processed. Acknowledge the delivery and do nothing else.
    DUPLICATE = "duplicate"
    #: Another worker holds a live claim. Try again later.
    BUSY = "busy"


class ProcessedEventStore:
    """
    The SQL half, against public.processed_events (db/schema/2026-09-15-processed-events.sql).

    ``borrow`` and ``give_back`` are the pool's get and return functions, passed in so
    tests can point the store at a throwaway database.
    """

    def __init__(self, borrow: Callable[[], Any], give_back: Callable[[Any], None]) -> None:
        self._borrow = borrow
        self._give_back = give_back

    def claim(self, consumer: str, event_id: uuid.UUID, lease_seconds: float) -> tuple[Claim, Optional[int]]:
        """
        Try to take an event. Returns the claim and, when ACQUIRED, a token.

        The token is the row's attempt count at the moment of claiming. release()
        needs it: a worker whose lease expired and was taken over must not release
        the claim the new owner now holds.
        """
        rows = self._run(
            """
            INSERT INTO processed_events AS p (consumer, event_id, status, attempts, lease_until)
            VALUES (%s, %s::uuid, 'IN_PROGRESS', 1, now() + make_interval(secs => %s))
            ON CONFLICT (consumer, event_id) DO UPDATE
               SET attempts = p.attempts + 1,
                   lease_until = EXCLUDED.lease_until
             WHERE p.status = 'IN_PROGRESS' AND p.lease_until <= now()
            RETURNING attempts
            """,
            (consumer, str(event_id), lease_seconds),
        )
        if rows:
            return Claim.ACQUIRED, rows[0][0]

        # The row exists and the conditional update did not fire: DONE, or claimed
        # under a lease that is still live. A row that vanished in between (a purge)
        # is reported BUSY, so the delivery is retried and claims it cleanly.
        rows = self._run(
            "SELECT status FROM processed_events WHERE consumer = %s AND event_id = %s::uuid",
            (consumer, str(event_id)),
        )
        if rows and rows[0][0] == "DONE":
            return Claim.DUPLICATE, None
        return Claim.BUSY, None

    def complete(self, consumer: str, event_id: uuid.UUID) -> None:
        """
        Marks the event DONE.

        Not fenced by the token. If this worker's lease was taken over, the event
        has still been processed -- by this worker -- so DONE is true either way.
        """
        self._run(
            """
            UPDATE processed_events
               SET status = 'DONE', completed_at = now(), lease_until = now()
             WHERE consumer = %s AND event_id = %s::uuid AND status = 'IN_PROGRESS'
            """,
            (consumer, str(event_id)),
            fetch=False,
        )

    def release(self, consumer: str, event_id: uuid.UUID, token: int) -> None:
        """
        Gives a claim back after a failure, so the retry need not wait out the lease.

        Expires the lease rather than deleting the row, which keeps the attempt
        count. Fenced by the token: a stale worker releases nothing.
        """
        self._run(
            """
            UPDATE processed_events
               SET lease_until = now()
             WHERE consumer = %s AND event_id = %s::uuid
               AND status = 'IN_PROGRESS' AND attempts = %s
            """,
            (consumer, str(event_id), token),
            fetch=False,
        )

    def purge(self, retention_days: int) -> int:
        """
        Deletes DONE rows past retention, and abandoned claims just as old.

        The second kind is a claim released or expired whose message then went to
        the DLQ: nothing will ever complete it. Keeping DONE rows for days is
        generous -- the duplicates above arrive within minutes -- and cheap.
        """
        return self._run(
            """
            DELETE FROM processed_events
             WHERE (status = 'DONE' AND completed_at < now() - make_interval(days => %s))
                OR (status = 'IN_PROGRESS' AND lease_until < now() - make_interval(days => %s))
            """,
            (retention_days, retention_days),
            fetch=False,
        )

    def _run(self, sql: str, params: tuple, fetch: bool = True):
        conn = self._borrow()
        try:
            # `with conn` commits on success and rolls back on error; each call is
            # its own short transaction, so no lock outlives the statement.
            with conn:
                with conn.cursor() as cur:
                    cur.execute(sql, params)
                    return cur.fetchall() if fetch else cur.rowcount
        finally:
            self._give_back(conn)


def event_id_of(properties: Any) -> Optional[uuid.UUID]:
    """The outbox event id the relay put in message_id, or None if absent or not a UUID."""
    raw = getattr(properties, "message_id", None)
    if not raw:
        return None
    try:
        return uuid.UUID(str(raw))
    except ValueError:
        return None


def deduplicating(
    handler: Callable[[bytes, Any], Outcome],
    store: Any,
    consumer: str,
    *,
    lease_seconds: float,
    retention_days: int = 7,
    purge_every_seconds: float = 3600.0,
    clock: Callable[[], float] = time.monotonic,
) -> Callable[[bytes, Any], Outcome]:
    """
    Wraps a ResilientConsumer handler so each event id is processed once.

    What each case returns, and why:
      DUPLICATE          PROCESSED, handler not called. Acking is the whole point.
      BUSY               RETRY, handler not called. By the retry it is DONE.
      claim raised       RETRY. Whether it is a duplicate is unknowable, and
                         running it anyway would make dedup silently optional.
      no usable id       the handler runs, undeduplicated, with a warning. The
                         relay always sets one; refusing messages without it
                         would dead-letter anything published another way.
      handler PROCESSED  complete(), then PROCESSED. If complete() fails the
                         delivery is still acked: the work is done, and the cost
                         is that a later duplicate might run again.
      anything else      release(), then the handler's outcome (or its exception).
                         A DEAD_LETTER is released too, so a replay from the DLQ
                         after a fix is processed rather than skipped as done.
    """
    last_purge = [clock()]

    def handle(body: bytes, properties: Any) -> Outcome:
        event_id = event_id_of(properties)
        if event_id is None:
            log.warning("Delivery has no usable message_id (%r); processing without deduplication",
                        getattr(properties, "message_id", None))
            return handler(body, properties)

        try:
            claim, token = store.claim(consumer, event_id, lease_seconds)
        except Exception:  # noqa: BLE001
            log.exception("Could not claim event %s; retrying it later", event_id)
            return Outcome.RETRY

        if claim is Claim.DUPLICATE:
            log.info("Event %s was already processed; acknowledging the duplicate", event_id)
            return Outcome.PROCESSED
        if claim is Claim.BUSY:
            log.warning("Event %s is being processed by another worker; retrying it later", event_id)
            return Outcome.RETRY

        try:
            outcome = handler(body, properties)
        except Exception:
            _quietly(store.release, consumer, event_id, token)
            raise

        if outcome is Outcome.PROCESSED:
            _quietly(store.complete, consumer, event_id)
            if clock() - last_purge[0] >= purge_every_seconds:
                last_purge[0] = clock()
                _quietly(store.purge, retention_days)
        else:
            _quietly(store.release, consumer, event_id, token)
        return outcome

    return handle


def _quietly(operation: Callable[..., Any], *args: Any) -> None:
    try:
        operation(*args)
    except Exception:  # noqa: BLE001
        log.exception("processed_events %s failed", operation.__name__)
