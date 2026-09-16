"""
The AI event wire format, as the Python side sees it -- every version it accepts.

Deliberately free of dependencies -- no pika, no fastapi, no
sentence-transformers. `main.py` imports its field names and event types from
here, and the contract test imports this module directly. Importing `main.py`
to test the dispatcher would drag in a model download, which is why the
literals were never tested before.

The definitions live in contracts/ai-events/: v1/ and v2/ hold the schemas, and
versions.json says which versions the backend produces and this service accepts.
This module is the Python half; AiEventContractTest is the Java half. Both are
checked against those files, so neither language can change the format alone.

TWO SHAPES
    Version 1 is a bare object: {"eventType", "studentId", "collegeId", "metadata"}.
    Version 2 and later are an envelope -- eventId, eventType, schemaVersion,
    occurredAt, aggregate, collegeId, traceId -- around a typed `payload`.
    parse_event() reads both into one AiEvent, so nothing after it knows or cares
    which version arrived. docs/EVENT_SCHEMA.md has the rules for changing them.

A TOLERANT READER
    Fields this module does not know are ignored, never an error. That is what
    lets the producer add an optional field without a version bump. What IS an
    error is a version this service does not accept: that raises EventRejected,
    loudly, rather than guessing at a shape it was never written for.
"""

from dataclasses import dataclass
from typing import Optional

# Version 1 field names, exactly as the backend's old AIEvent record serialised
# them (it is gone; version 1 messages may not be). Java uses record component
# names, so these are camelCase, and a rename on either side is a silent no-op at
# runtime -- the consumer simply reads None.
FIELD_EVENT_TYPE = "eventType"
FIELD_STUDENT_ID = "studentId"
FIELD_COLLEGE_ID = "collegeId"
FIELD_METADATA = "metadata"

EVENT_SKILL_UPDATED = "SKILL_UPDATED"
EVENT_PROFILE_UPDATED = "PROFILE_UPDATED"

#: Event types with a handler. Anything else is logged and skipped, so this
#: tuple must stay equal to the schema's `eventType` enum -- a value in the
#: schema but not here is an event the producer is entitled to send and the
#: consumer silently discards.
HANDLED_EVENT_TYPES = (EVENT_SKILL_UPDATED, EVENT_PROFILE_UPDATED)


def metadata_of(payload: dict) -> dict:
    """
    The event's metadata as a dict, whatever the producer actually sent.

    ``payload.get("metadata", {})`` is NOT enough, and this function exists
    because that was the bug:

    * ``{"metadata": None}`` returns ``None``, not ``{}`` -- a default only
      applies to a *missing* key, never to a present-but-null one. Java sends
      an explicit ``null`` for PROFILE_UPDATED.
    * ``{"metadata": 5}`` returns ``5``. Java used to send the bare skillId
      here, typed ``Object``, which serialises as a JSON number.

    Both then hit ``.get("skills", [])`` and raise ``AttributeError``, and
    ``_rabbitmq_callback`` nacks with ``requeue=False``. With no dead-letter
    queue the event is simply gone. Every AI event published before
    2026-09-13 was discarded this way.

    The producer now sends an object and the schema forbids a scalar, so this
    is a belt-and-braces guard on a wire we do not fully control.
    """
    value = payload.get(FIELD_METADATA)
    return value if isinstance(value, dict) else {}


# ---------------------------------------------------------------------------
# Versions and the envelope, as defined in contracts/ai-events/.
# ---------------------------------------------------------------------------
FIELD_EVENT_ID = "eventId"
FIELD_SCHEMA_VERSION = "schemaVersion"
FIELD_OCCURRED_AT = "occurredAt"
FIELD_TRACE_ID = "traceId"
FIELD_REPLAY_OF = "replayOf"
FIELD_PAYLOAD = "payload"
FIELD_SKILL_ID = "skillId"

#: The version of every event published before the envelope existed.
UNENVELOPED_VERSION = 1

#: Payload versions this service can process, per event type. Must equal
#: `accepted` in contracts/ai-events/versions.json -- the backend may only
#: produce a version listed here, and the contract tests hold both sides to it.
SUPPORTED_SCHEMA_VERSIONS = {
    EVENT_SKILL_UPDATED: (1, 2),
    EVENT_PROFILE_UPDATED: (1, 2),
}

#: Why an event was refused, as a metric label and in EventRejected.kind.
REJECTED_MALFORMED = "malformed"
REJECTED_UNKNOWN_TYPE = "unknown_type"
REJECTED_UNSUPPORTED_VERSION = "unsupported_version"


class EventRejected(ValueError):
    """
    A message this service can never process. str() is the reason.

    event_type and schema_version are whatever could be read, for the metric;
    either may be None.
    """

    def __init__(self, reason: str, kind: str = REJECTED_MALFORMED,
                 event_type: object = None, schema_version: object = None) -> None:
        super().__init__(reason)
        self.kind = kind
        self.event_type = event_type
        self.schema_version = schema_version


@dataclass(frozen=True)
class AiEvent:
    """One event, whichever version it arrived in."""

    event_type: str
    schema_version: int
    student_id: int
    college_id: Optional[int] = None
    skill_id: Optional[int] = None
    #: Version 1 only: skill names the producer resolved in advance. Never sent in practice.
    skills: tuple = ()
    #: Version 2 on. For version 1 the AMQP message_id is the only id.
    event_id: Optional[str] = None
    trace_id: Optional[str] = None
    occurred_at: Optional[str] = None
    replay_of: Optional[str] = None


def parse_event(body: object) -> AiEvent:
    """
    Reads a decoded message body, in any accepted version.

    Raises EventRejected for anything it cannot process: not an object, an unknown
    event type, a version this service does not accept, or a shape that does not
    match the version it claims.
    """
    if not isinstance(body, dict):
        raise EventRejected(f"message body is JSON but not an object: {type(body).__name__}")

    if FIELD_SCHEMA_VERSION in body or FIELD_PAYLOAD in body:
        version = body.get(FIELD_SCHEMA_VERSION)
        event_type = body.get(FIELD_EVENT_TYPE)
        if not _is_int(version) or version <= UNENVELOPED_VERSION:
            raise EventRejected(
                f"an envelope must carry an integer schemaVersion of 2 or more, not {version!r}",
                event_type=event_type, schema_version=version)
        _require_supported(event_type, version)
        parse = _ENVELOPED.get(version)
        if parse is None:
            # SUPPORTED_SCHEMA_VERSIONS lists a version nobody wrote a reader for.
            # The contract tests forbid it; this is the runtime backstop.
            raise EventRejected(f"no reader for schemaVersion {version}",
                                REJECTED_UNSUPPORTED_VERSION, event_type, version)
        return parse(body, event_type, version)

    event_type = body.get(FIELD_EVENT_TYPE)
    _require_supported(event_type, UNENVELOPED_VERSION)
    return _parse_v1(body, event_type)


def _require_supported(event_type: object, version: int) -> None:
    if not isinstance(event_type, str) or event_type not in SUPPORTED_SCHEMA_VERSIONS:
        raise EventRejected(f"no handler for event type {event_type!r}",
                            REJECTED_UNKNOWN_TYPE, event_type, version)
    accepted = SUPPORTED_SCHEMA_VERSIONS[event_type]
    if version not in accepted:
        # Loudly: an unknown version means the producer has moved past this
        # consumer, and someone has to deploy it. Guessing would be worse.
        raise EventRejected(
            f"unsupported schemaVersion {version} for {event_type}; this consumer accepts "
            f"{', '.join(str(v) for v in accepted)}",
            REJECTED_UNSUPPORTED_VERSION, event_type, version)


def _parse_v1(body: dict, event_type: str) -> AiEvent:
    student_id = _positive_int(body.get(FIELD_STUDENT_ID))
    if student_id is None:
        raise EventRejected(f"version 1 {event_type} without a valid studentId",
                            event_type=event_type, schema_version=UNENVELOPED_VERSION)
    metadata = metadata_of(body)
    skills = metadata.get("skills")
    return AiEvent(
        event_type=event_type,
        schema_version=UNENVELOPED_VERSION,
        student_id=student_id,
        college_id=_positive_int(body.get(FIELD_COLLEGE_ID)),
        skill_id=_positive_int(metadata.get(FIELD_SKILL_ID)),
        skills=tuple(s for s in skills if isinstance(s, str)) if isinstance(skills, list) else (),
    )


def _parse_v2(body: dict, event_type: str, version: int) -> AiEvent:
    payload = body.get(FIELD_PAYLOAD)
    if not isinstance(payload, dict):
        raise EventRejected(f"version {version} {event_type} without a payload object",
                            event_type=event_type, schema_version=version)
    student_id = _positive_int(payload.get(FIELD_STUDENT_ID))
    skill_id = _positive_int(payload.get(FIELD_SKILL_ID))
    if student_id is None:
        raise EventRejected(f"version {version} {event_type} without a valid payload.studentId",
                            event_type=event_type, schema_version=version)
    if event_type == EVENT_SKILL_UPDATED and skill_id is None:
        raise EventRejected(f"version {version} {event_type} without a valid payload.skillId",
                            event_type=event_type, schema_version=version)
    return AiEvent(
        event_type=event_type,
        schema_version=version,
        student_id=student_id,
        college_id=_positive_int(body.get(FIELD_COLLEGE_ID)),
        skill_id=skill_id,
        event_id=_text(body.get(FIELD_EVENT_ID)),
        trace_id=_text(body.get(FIELD_TRACE_ID)),
        occurred_at=_text(body.get(FIELD_OCCURRED_AT)),
        replay_of=_text(body.get(FIELD_REPLAY_OF)),
    )


#: A reader for every enveloped version this service accepts. A new version
#: needs an entry here AND in SUPPORTED_SCHEMA_VERSIONS; the tests check both.
_ENVELOPED = {
    2: _parse_v2,
}


def _is_int(value: object) -> bool:
    # bool is an int in Python; true is not a version, and not a student id.
    return isinstance(value, int) and not isinstance(value, bool)


def _positive_int(value: object) -> Optional[int]:
    return value if _is_int(value) and value >= 1 else None


def _text(value: object) -> Optional[str]:
    return value if isinstance(value, str) else None


# ---------------------------------------------------------------------------
# AMQP topology, as named in contracts/amqp/topology.json.
#
# The backend's RabbitMQConfig DECLARES all of this; this service only connects
# to it. Two declarers would have to agree on every argument, and a disagreement
# is a PRECONDITION_FAILED that closes the channel. tests/test_amqp_topology_contract.py
# checks these constants against the contract file, as the Java side does.
# ---------------------------------------------------------------------------
EVENTS_EXCHANGE = "skillbridge.events"
RETRY_EXCHANGE = "skillbridge.retry"
DEAD_LETTER_EXCHANGE = "skillbridge.dlx"
AI_ANALYSIS_QUEUE = "skillbridge.ai.analysis"
DEAD_LETTER_QUEUE = "skillbridge.dlq"

#: Delay queues in escalation order. A message on its Nth retry goes to the Nth
#: tier; after the last one it is dead-lettered instead.
RETRY_TIER_QUEUES = (
    "skillbridge.ai.analysis.retry.5s",
    "skillbridge.ai.analysis.retry.30s",
    "skillbridge.ai.analysis.retry.5m",
)
MAX_RETRIES = len(RETRY_TIER_QUEUES)

#: How many retries a message has already had. Set by this service when it
#: republishes to a tier, because the count has to travel with the message.
RETRY_ATTEMPT_HEADER = "x-retry-attempt"

#: Set when this service dead-letters a message itself, so the DLQ says why.
#: x-failed-at is epoch milliseconds; the reason is cut to MAX_FAILURE_REASON_LENGTH.
FAILURE_REASON_HEADER = "x-failure-reason"
FAILED_AT_HEADER = "x-failed-at"
FAILED_QUEUE_HEADER = "x-failed-queue"
MAX_FAILURE_REASON_LENGTH = 2000

ROUTING_KEYS = {
    EVENT_SKILL_UPDATED: "ai.skill.updated",
    EVENT_PROFILE_UPDATED: "ai.profile.updated",
}
