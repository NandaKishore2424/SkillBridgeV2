"""
event_dispatch.py -- what happens to one delivery: decode, check the version,
count it, and hand it to the handler for its type.

Free of fastapi, pika and the model, so it is tested directly; main.py only
supplies the handlers. ResilientConsumer does the acking, dedup.py sits in front.

COUNTED BY VERSION
    ai_events_consumed_total{event_type, schema_version, outcome} says, for every
    version, how many events were processed, failed, or refused. It is the number
    that decides when support for an old version can go: once nothing arrives in
    it any more (docs/EVENT_SCHEMA.md). The backend counts the other side,
    outbox_published_total by schemaVersion.

    Labels are bounded. An event type this service does not know is counted as
    "other", and a version that is not a small integer as "invalid", so a hostile
    or broken message cannot mint new time series.
"""

from __future__ import annotations

import json
import logging
from typing import Any, Callable, Mapping, Optional

from prometheus_client import CollectorRegistry, Counter

import ai_event_contract as contract
from resilient_consumer import Outcome, PermanentFailure

log = logging.getLogger(__name__)

PROCESSED = "processed"
FAILED = "failed"

Handler = Callable[[contract.AiEvent], None]


class EventMetrics:
    """The per-version counter, on a registry of its own so tests start from zero."""

    def __init__(self, registry: Optional[CollectorRegistry] = None) -> None:
        self.registry = registry if registry is not None else CollectorRegistry()
        self.consumed = Counter(
            "ai_events_consumed",
            "AI events that reached the dispatcher, by type, schema version and outcome",
            ["event_type", "schema_version", "outcome"],
            registry=self.registry,
        )

    def count(self, event_type: object, schema_version: object, outcome: str) -> None:
        self.consumed.labels(
            # isinstance first: a list is not hashable, and a hostile eventType can be one.
            event_type=(event_type if isinstance(event_type, str)
                        and event_type in contract.SUPPORTED_SCHEMA_VERSIONS else "other"),
            schema_version=(str(schema_version)
                            if isinstance(schema_version, int) and not isinstance(schema_version, bool)
                            and 1 <= schema_version <= 99
                            else "invalid"),
            outcome=outcome,
        ).inc()

    def value(self, event_type: str, schema_version: str, outcome: str) -> float:
        """For tests and the health check: the current count for one label set."""
        sample = self.registry.get_sample_value(
            "ai_events_consumed_total",
            {"event_type": event_type, "schema_version": schema_version, "outcome": outcome},
        )
        return sample or 0.0


class Dispatcher:
    """
    A ResilientConsumer handler: (body, properties) -> Outcome.

    Refused messages raise PermanentFailure, so they reach the DLQ with the reason
    -- an unsupported version included: that is the loud rejection the phase asks
    for, and the DeadLetterRecorded alert is how someone hears it. A handler that
    raises is left to propagate: the consumer retries it.
    """

    def __init__(self, handlers: Mapping[str, Handler], metrics: EventMetrics) -> None:
        missing = set(contract.SUPPORTED_SCHEMA_VERSIONS) - set(handlers)
        if missing:
            # An accepted event type with no handler would be refused at runtime,
            # every time; better to refuse to start.
            raise ValueError(f"no handler for accepted event types: {sorted(missing)}")
        self._handlers = dict(handlers)
        self._metrics = metrics

    def __call__(self, body: bytes, properties: Any = None) -> Outcome:
        try:
            decoded = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            self._metrics.count(None, None, contract.REJECTED_MALFORMED)
            raise PermanentFailure(f"unparseable message body: {exc}") from exc

        try:
            event = contract.parse_event(decoded)
        except contract.EventRejected as exc:
            self._metrics.count(exc.event_type, exc.schema_version, exc.kind)
            log.error("Refusing event (%s): %s", exc.kind, exc)
            raise PermanentFailure(str(exc)) from exc

        try:
            self._handlers[event.event_type](event)
        except Exception:
            self._metrics.count(event.event_type, event.schema_version, FAILED)
            raise
        self._metrics.count(event.event_type, event.schema_version, PROCESSED)
        return Outcome.PROCESSED
