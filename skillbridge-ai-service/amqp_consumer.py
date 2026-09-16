"""
amqp_consumer.py -- the service's RabbitMQ consumer, built the one way it is built.

main.py uses it with the real handler; tests_broker/ uses it against a real,
killable broker with a recording one. Both get exactly this connection code and
these settings, which is the point: the resilience tests exercise what runs.

Imports pika, and nothing heavier. Free of config: the URL is an argument, so a
test can point it at its own broker without the service's environment.
"""

from __future__ import annotations

from typing import Any, Callable

import pika

import ai_event_contract as contract
from resilient_consumer import Outcome, ResilientConsumer

#: Seconds between AMQP heartbeats. A connection that goes silent -- a broker
#: that died without closing its sockets -- is noticed after about two missed.
HEARTBEAT_SECONDS = 30


def connect(url: str) -> pika.BlockingConnection:
    """
    One broker connection. ResilientConsumer calls this again after every drop.

    The old consumer made exactly one of these, under a docstring promising it
    reconnected, and a single broker restart left it dead for good while the
    service reported healthy.
    """
    parameters = pika.URLParameters(url)
    parameters.heartbeat = HEARTBEAT_SECONDS
    parameters.blocked_connection_timeout = 60
    connection = pika.BlockingConnection(parameters)
    # PASSIVE: check the queue exists, declare nothing. The backend's
    # RabbitMQConfig owns the topology, and a second declarer would have to match
    # every quorum argument exactly or fail with PRECONDITION_FAILED. If the
    # backend has not started yet this raises, ResilientConsumer backs off and
    # retries, and /health reports 503 meanwhile -- which is the truth.
    try:
        check = connection.channel()
        check.queue_declare(queue=contract.AI_ANALYSIS_QUEUE, passive=True)
        check.close()
    except Exception:
        connection.close()
        raise
    return connection


def build(url: str, handler: Callable[[bytes, Any], Outcome], **overrides: Any) -> ResilientConsumer:
    """
    The analysis-queue consumer: retry tiers, dead-lettering with a reason, and a
    connection that is re-made after every drop.

    ``overrides`` exist for tests (a shorter backoff, say); production passes none.
    """
    settings = dict(
        retry_exchange=contract.RETRY_EXCHANGE,
        retry_queues=contract.RETRY_TIER_QUEUES,
        attempt_header=contract.RETRY_ATTEMPT_HEADER,
        # A dead letter goes to the DLQ with the reason in a header, so the backend's
        # record of it says why (see resilient_consumer.py).
        dead_letter_exchange=contract.DEAD_LETTER_EXCHANGE,
        reason_header=contract.FAILURE_REASON_HEADER,
        failed_at_header=contract.FAILED_AT_HEADER,
        failed_queue_header=contract.FAILED_QUEUE_HEADER,
        max_reason_length=contract.MAX_FAILURE_REASON_LENGTH,
    )
    settings.update(overrides)
    return ResilientConsumer(contract.AI_ANALYSIS_QUEUE, handler, lambda: connect(url), **settings)
