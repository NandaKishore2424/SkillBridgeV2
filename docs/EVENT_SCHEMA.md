# Event schema: the envelope, versions, and how to change them

The backend publishes events to RabbitMQ; the AI service consumes them. They are
deployed separately, so the message format has to be able to change without both
changing at once. This document is the rulebook for that. The definitions it refers
to are files, not prose:

| File | What it defines |
|---|---|
| `contracts/ai-events/v2/ai-event.schema.json` | the current format: the envelope, and each event type's payload |
| `contracts/ai-events/v1/ai-event.schema.json` | the format before the envelope; still accepted |
| `contracts/ai-events/versions.json` | which version the backend **produces**, and which the AI service **accepts** |
| `contracts/ai-events/v*/…example.json` | one real message per event type and version |

`AiEventContractTest` (Java) and `tests/test_ai_event_contract.py` (Python) check
both sides against those files. Neither side can change the format, or which
version is on the wire, on its own.

## What an event looks like

```json
{
  "eventId": "3f1c2a9e-7b64-4d0f-9a51-6c2d8e4b7a10",
  "eventType": "SKILL_UPDATED",
  "schemaVersion": 2,
  "occurredAt": "2026-09-16T10:15:30.123Z",
  "aggregateType": "Student",
  "aggregateId": "31",
  "collegeId": 1,
  "traceId": "4bf92f3577b34da6",
  "payload": { "studentId": 31, "skillId": 7 }
}
```

| Field | Meaning |
|---|---|
| `eventId` | Unique per event. The same value is the AMQP `message_id` and the outbox row's `event_id`. Consumers deduplicate on it. |
| `eventType` | Which handler runs, and which payload shape follows. |
| `schemaVersion` | The version of **this event type's payload**. |
| `occurredAt` | When the business change happened (UTC). Not when it was sent — the relay may send it minutes later. |
| `aggregateType`, `aggregateId` | What changed. The id is text whatever its database type. |
| `collegeId` | The tenant, so a consumer can scope its work. Null only for an event that belongs to no college. |
| `traceId` | The request that caused the event, for following it across services. Null for a scheduled job. |
| `replayOf` | Only on a replay from the dead-letter queue: the `eventId` it re-sends. |
| `payload` | The event itself. Its shape depends on `eventType` and `schemaVersion`. |

The envelope lets anything route, deduplicate, trace and version-check an event
without understanding its payload. That includes the dead-letter recorder and a
replay, which is why **the version is in the body**. The relay also sets
`schemaVersion` as an AMQP header, as a convenience. But a message copied into the
DLQ, stored in `dead_letter_events` and sent again keeps its body, so the body has
to say which version it is on its own.

### Version 1: before the envelope

```json
{ "eventType": "SKILL_UPDATED", "studentId": 31, "collegeId": 1, "metadata": { "skillId": 7 } }
```

Nothing produces this any more. The AI service still accepts it, because such
messages can still exist in a queue, a retry tier, or a stored dead letter. A body
without an envelope is version 1 by definition. A body with only part of an
envelope is refused: its version cannot be trusted.

## Compatibility rules

A consumer here is a **tolerant reader**. It ignores fields it does not know, and
never treats them as an error. That is what makes the first group below safe.

### Backward-compatible — no version bump

- **Adding an optional field**, to the envelope or a payload. Add it to the schema
  in the same change: the schemas list every field (`additionalProperties: false`),
  so a new field is a reviewed decision and not an accident. Consumers that have not
  been redeployed ignore it.
- **Widening a number within `integer`** — Java `int` to `long`, say. JSON has one
  integer type, and Python's is unbounded. Widening to a *non*-integer is not in
  this group: consumers refuse `31.0` as a student id.
- **Adding an event type whose routing key the AI queue does not bind.** The AI
  queue binds `ai.#`. A `notification.*` event never reaches it.

**Not in this group, although the phase document puts it there: a new event type
under `ai.*`.** The AI service dead-letters any type it has no handler for. That is
deliberate, and loud. So the consumer has to learn the type before the producer
sends it, exactly as with a breaking change.

### Breaking — a version bump, and the consumer first

- removing or renaming a field;
- changing a field's type;
- making an optional field required;
- moving a field. Version 1 → 2 was this: `studentId` and `skillId` moved into
  `payload`, and `metadata` was removed.

### Never

**Do not give an existing field a new meaning.** A consumer that has not been
redeployed will read the old field with the new meaning, and nothing will fail.
The resulting bug is nearly impossible to find. Add a new field and retire the old
one.

## Making a breaking change

The order is the whole guarantee: **the consumer first, then the producer, and
support for the old version is removed last.**

1. **Define it.** Add `contracts/ai-events/v<N>/ai-event.schema.json` and an example
   per event type.
2. **Teach the consumer.**
   - Add a reader for version N to `_ENVELOPED` in `ai_event_contract.py`.
   - Add N to `SUPPORTED_SCHEMA_VERSIONS`, and to `accepted` in `versions.json`.
   - The Python tests fail until the reader, the schema file and the list agree.
3. **Deploy the AI service.** It now accepts both N−1 and N.
4. **Move the producer.**
   - Change the version and payload record in `EventType`, and `produced` in
     `versions.json`.
   - `AiEventContractTest` fails this step if step 2 has not happened: nothing may
     be produced in a version outside `accepted`.
5. **Deploy the backend.** Watch the new version arrive, and the old one stop, in
   the counters below.
6. **Wait until nothing old is left.** Old-version messages can outlive the
   switch in:
   - the analysis queue;
   - the retry tiers (up to five minutes);
   - the outbox (while the broker is down);
   - `dead_letter_events`. A replay sends a dead letter in the version it failed
     in (see below).

   Before removing support, check that each of these is zero:

   ```sql
   SELECT count(*) FROM outbox_events WHERE status = 'PENDING' AND schema_version < N;
   -- A dead letter's version: its envelope's, or 1 if it has none. Guarded, because
   -- a malformed body's schemaVersion may not be a number at all.
   SELECT count(*) FROM dead_letter_events
    WHERE status = 'PENDING' AND payload_json IS NOT NULL
      AND CASE WHEN jsonb_typeof(payload_json->'schemaVersion') = 'number'
               THEN (payload_json->>'schemaVersion')::numeric ELSE 1 END < N;
   ```

   Also check that `ai_events_consumed_total{schema_version="N-1"}` has stopped
   rising.
7. **Remove the old version.** Take N−1 out of `accepted` and delete its reader.

Version 1 → 2 went through steps 1–5 on 2026-09-16, in one commit, because
nothing was deployed. **Step 6 has not happened.** Version 1 stays accepted until
something is deployed and the counters say it is safe to remove.

### What was not built: dual publishing

The phase document suggests publishing both versions at once while consumers
migrate. That is the tool for when deploy order cannot be controlled, such as
several teams or consumers nobody can redeploy. Here there is one consumer, and
this repository deploys it, so ordering the deploys does the same job with none of
the cost:

- no event published twice;
- no second copy to deduplicate;
- no old consumer dead-lettering every new-version copy.

If a second, independently deployed consumer ever appears, this is the first
thing to revisit.

## What the consumer does with each message

| Message | Outcome | Counted as |
|---|---|---|
| an accepted type, in an accepted version | processed; acknowledged | `processed` |
| a version this service does not accept | **refused**: dead-lettered with the reason, e.g. `unsupported schemaVersion 3 for SKILL_UPDATED; this consumer accepts 1, 2` | `unsupported_version` |
| an event type with no handler | refused: dead-lettered with the reason | `unknown_type` |
| not JSON, not an object, or not the shape its version defines | refused: dead-lettered with the reason | `malformed` |
| the handler raised (a database error, the model) | retried on the 5s / 30s / 5m tiers, then dead-lettered with the last error | `failed` |

Refusing an unknown version is deliberate. It means the producer has moved past
this consumer, and someone has to deploy it; guessing at the new shape would be
worse. The refusal reaches the DLQ with its reason, and the `DeadLetterRecorded`
alert fires (`docs/RUNBOOK_DLQ.md`). Once the consumer is fixed, the messages can
be replayed from there.

## Replays keep their version

A replay from `dead_letter_events` sends the body **as it failed**. It is not
upgraded, because what failed is what a fixed consumer should now process.

- **A version 2 or later envelope** gets a new `eventId`, and the old one goes in
  `replayOf`. The consumer has already recorded the old id, and would otherwise
  acknowledge the replay as a duplicate without processing it.
- **A version 1 body** goes out unchanged, under a new AMQP message id. It has no
  field to carry an id in.

## Where versions are counted

| Side | Metric | Where |
|---|---|---|
| backend | `outbox_published_total{eventType, schemaVersion}` | `/actuator/prometheus` |
| AI service | `ai_events_consumed_total{event_type, schema_version, outcome}` | `/metrics` |

The consumer's labels are bounded. An unknown type counts as `other`, and a
version that is not a small integer as `invalid`, so a malformed message cannot
create new time series.

## Not done

- **Nothing scrapes either metric yet.** There is no deployment (Phases 10 and 15).
- **No schema registry service.** The files in `contracts/` are the registry, and
  CI checks both services against them. That is enough while both live in this
  repository.
- **The AI service reads `collegeId` but does not scope anything by it yet.** That
  is Phase 12.
