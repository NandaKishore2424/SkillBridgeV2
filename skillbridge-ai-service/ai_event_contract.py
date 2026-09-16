"""
The AIEvent wire format, as the Python side sees it.

Deliberately free of dependencies -- no pika, no fastapi, no
sentence-transformers. `main.py` imports its field names and event types from
here, and the contract test imports this module directly. Importing `main.py`
to test the dispatcher would drag in a model download, which is why the
literals were never tested before.

The definition itself lives in contracts/ai-events/v1/ai-event.schema.json.
This module is the Python half; AiEventContractTest is the Java half. Both are
checked against that one file, so neither language can change the format alone.
"""

# Field names, exactly as com.skillbridge.shared.messaging.AIEvent serialises
# them. Java uses record component names, so these are camelCase and a rename
# on either side is a silent no-op at runtime -- the consumer simply reads None.
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
