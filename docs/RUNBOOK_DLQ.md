# Runbook: dead letters and the outbox

What to do when an event fails for good, or stops moving. Each alert in
`ops/prometheus/messaging-alerts.yml` links to its section here, and
`MessagingAlertRulesTest` fails the build if a link points at a heading that does
not exist.

## How an event travels, and where it can stop

```
business transaction ──► outbox_events (PENDING)
                              │ OutboxRelay: publish, wait for the broker's confirm
                              ▼
                     skillbridge.events ──ai.#──► skillbridge.ai.analysis ──► AI service
                                                        │                        │
                         nack / delivery limit          │     transient failure: │
                                                        ▼     5s, 30s, 5m tiers  │
      dead_letter_events ◄── DeadLetterRecorder ◄── skillbridge.dlq ◄────────────┘
         (PENDING)                                     ▲     permanent failure, or
             │                                         │     retries exhausted
             ├── replay ──► outbox_events (a NEW event id)
             └── discard (with a note)
```

An event can stop in three places, and each has its own alert:

| Where | What it means | Alert |
|---|---|---|
| `outbox_events`, PENDING | the broker is not accepting it yet | `OutboxBacklogGrowing` |
| `outbox_events`, DEAD | the relay gave up; it never reached the broker | `OutboxEventsDead` |
| `dead_letter_events`, PENDING | it reached the AI service and failed for good | `DeadLetterRecorded`, `DeadLettersUnresolved` |

Nothing in this pipeline deletes an event that has not been dealt with. The
outbox purge removes only PUBLISHED rows; the dead-letter purge only REPLAYED and
DISCARDED ones, after 30 days.

## Looking at dead letters

All endpoints are SYSTEM_ADMIN only. `$TOKEN` is an access token from
`POST /api/v1/auth/login`.

```bash
# What needs a decision, newest first (status defaults to PENDING)
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/admin/dead-letters?size=50"

# One of them, with its body and headers
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/admin/dead-letters/42"
```

Each row carries:

- `deathReason` — `consumer` if the AI service dead-lettered it and said why;
  otherwise the broker's reason (`rejected`, `delivery_limit`, `expired`, `maxlen`),
  or `unknown`.
- `failureReason` — the AI service's own words, or an explanation of the broker's
  reason.
- `retryCount` — how many retry tiers it went through first.
- `replayBlocker` — null if a replay would be accepted; otherwise why not
  (`NOT_A_JSON_OBJECT`, `UNKNOWN_EVENT_TYPE`, `ALREADY_REPLAYED`, `ALREADY_DISCARDED`).

The same data, straight from the database, for a question the API does not answer:

```sql
-- What is failing, and how often, in the last day
SELECT death_reason, event_type, left(failure_reason, 80) AS reason, count(*)
FROM dead_letter_events
WHERE recorded_at > now() - interval '1 day'
GROUP BY 1, 2, 3 ORDER BY 4 DESC;

-- Everything about one student's failed events (payload_json is the parsed body)
SELECT id, status, event_type, failure_reason, recorded_at
FROM dead_letter_events
WHERE payload_json->>'studentId' = '31'
ORDER BY id DESC;
```

## Deciding: replay or discard

Replay when the cause is fixed and the event still means something: a bug in the
AI service that has been deployed, a database that was down. Discard when it
never will: a malformed message, an event about a student who no longer exists, a
test message.

Ask first whether the event is still wanted. A replay runs the analysis **now**,
against today's data. For a `SKILL_UPDATED` from last week that is usually what you
want — the analysis reads current skills either way — but check before replaying
anything whose effect depends on when it happened.

```bash
# Replay one. 202 with the new event id; 409 if already resolved; 422 if it cannot be replayed.
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/v1/admin/dead-letters/42/replay"

# Discard one. The note is required.
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"note": "test message published by hand during the 2026-09-16 drill"}' \
  "http://localhost:8080/api/v1/admin/dead-letters/43/discard"
```

A replay is a **new event** with a new id, written to the outbox and delivered by
the relay like any other. The dead letter records which event it became
(`replayEventId`). If the replay fails too, it comes back as a new dead letter;
the old row stays REPLAYED, because that is what was decided about it.

Both actions are written twice: on the row itself (`resolvedBy`, `resolvedAt`), in
the same transaction, and in the audit log (`DEAD_LETTER_REPLAYED`,
`DEAD_LETTER_DISCARDED`), including refused attempts.

### Bulk replay after a systemic fix

**Always dry-run first.** `dryRun` defaults to true, so the first call below
changes nothing.

```bash
# What would be replayed: every PENDING SKILL_UPDATED, oldest first, at most 200
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"eventType": "SKILL_UPDATED", "limit": 200}' \
  "http://localhost:8080/api/v1/admin/dead-letters/replay-batch"

# Read the result: `replayed` is what would go, `skipped` says why the rest would not.
# Then, and only then:
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"eventType": "SKILL_UPDATED", "limit": 200, "dryRun": false}' \
  "http://localhost:8080/api/v1/admin/dead-letters/replay-batch"
```

Select by `eventType`, or by `ids` (up to 500), not both. One call replays at most
500 rows in one transaction, all or nothing. If `limitReached` is true, more rows
matched; run it again. Two admins running the same bulk replay at once take
different rows (`SKIP LOCKED`), so nothing is replayed twice.

A dry run is not audited — it changes nothing. A real run writes one
`DEAD_LETTER_BATCH_REPLAYED` entry listing what it replayed and skipped.

## Alerts

### DeadLetterRecorded

**New dead letters arrived.** Warning: each is a decision someone has to make,
not an outage.

1. List the new ones (above) and read `failureReason`.
2. Many with the same reason means one cause. Fix it, then bulk-replay.
3. A single malformed message: discard it with a note saying where it came from.

### DeadLettersUnresolved

**More than ten dead letters have waited over 30 minutes.** Nobody has decided.
Work through them as above. If they are all one cause that cannot be fixed yet,
leave them PENDING — they are safe there — and say so where the team will see it.

### DeadLetterQueueNotDraining

**`skillbridge.dlq` has held messages for ten minutes.** Critical: failures are
not being recorded, so nobody can see them. Nothing is lost — they are still in
the queue — but nothing is visible either.

1. Is the backend running, and is `dead-letters.recorder.enabled` true? It is off
   under the test profile, and `DEAD_LETTER_RECORDER_ENABLED=false` turns it off
   anywhere.
2. Is the recorder connected? `skillbridge.dlq` should show exactly one consumer in
   the RabbitMQ management UI (Queues → skillbridge.dlq), or in
   `rabbitmqctl list_consumers`. None means the backend is not connected to the
   broker; its log shows the connection attempts.
3. If it is connected and not draining, it is failing to store: see the next alert.

### DeadLetterRecorderFailing

**The recorder cannot write to `dead_letter_events`.** It holds the message it
has, unacknowledged, and retries with a backoff of up to 30 seconds. It does not
drop the message and does not requeue it. (A requeue would spin, and would count
toward RabbitMQ 4's default quorum delivery limit; past it the broker deletes the
message, because the DLQ has nowhere to dead-letter to.)

1. Search the log for `Could not record dead letter`. The exception says why.
2. Database down or out of connections: fix that; the recorder catches up by
   itself.
3. Anything else is a bug in turning that message into a row — `DeadLetterMessage`
   is meant to accept any message. The log names the message id and fingerprint.
   To unblock the queue while it is fixed, move that one message aside by hand in
   the management UI (get it with requeue off, publish it to a holding queue), and
   file the bug with its body.

### OutboxBacklogGrowing

**More than 1000 events are committed but not yet confirmed by the broker.**
Nothing is lost; they are rows in `outbox_events` and will be sent when the broker
accepts them.

1. Is RabbitMQ up? `docker ps`, or the hosted broker's console.
2. Is the relay running? `outbox.relay.enabled` must be true, and the log shows
   `Outbox event … failed on attempt` for each failure, with the reason.
3. Is `skillbridge.ai.analysis` full? At 100000 messages it refuses publishes
   (`x-overflow: reject-publish`) and the relay retries. See
   [AnalysisQueueBacklog](#analysisqueuebacklog).
4. What the relay is stuck on:

   ```sql
   SELECT event_type, attempts, left(last_error, 100), count(*)
   FROM outbox_events WHERE status = 'PENDING'
   GROUP BY 1, 2, 3 ORDER BY 4 DESC LIMIT 20;
   ```

### OutboxEventsDead

**The relay gave up on events after 8 attempts.** They never reached the broker,
so they are **not** in the DLQ and not in `dead_letter_events`. There is no
endpoint for these yet.

```sql
SELECT id, event_id, event_type, routing_key, attempts, last_error, created_at
FROM outbox_events WHERE status = 'DEAD' ORDER BY id DESC;
```

The usual cause is `unroutable`: a routing key no queue is bound to. Fix the
binding or the key; then, to send them again, put them back in the relay's path —
it is safe, because the relay publishes each at least once and the consumer
deduplicates on `event_id`:

```sql
UPDATE outbox_events
SET status = 'PENDING', attempts = 0, next_attempt_at = now()
WHERE status = 'DEAD' AND id IN (/* the ids you checked */);
```

### AnalysisQueueBacklog

**More than 10000 messages are waiting for the AI service.** Its consumer is down
or slower than the events arriving.

1. `GET /health` on the AI service: 503 with `amqp_consumer: down` means it is not
   consuming. It reconnects by itself; its log says why it cannot.
2. If it is up and slow, look at what it is doing — every event is a skill-gap
   analysis with a database round trip.
3. At 100000 the queue refuses new messages and the outbox backs up behind it.
   Nothing is lost, but nothing new is analysed.

### MessagingMetricsUnavailable

**A backlog gauge has read NaN for ten minutes.** `MessagingMetrics` reports NaN
when it cannot read a source — the database for the outbox and dead-letter counts,
the broker for the queue depths — because zero would claim there is no backlog at
exactly the moment nobody can tell. While a gauge is NaN, the alert above that
uses it cannot fire.

The alert's `metric` label names the gauge (`outbox_events`, `dead_letter_events`
or `messaging_queue_messages`), and its other labels say which series. The backend
log says which source, once, when it starts failing:
`Messaging metric source '…' is unreadable`.

## Before the first run against an existing broker

The topology changed in Phase 09 Task 2. A broker that ran an older backend still
has the classic `ai.analysis.queue` and the `skillbridge-ai-exchange`, which
nothing declares or consumes any more. Drain and delete them by hand, after
checking what is in them: the local development broker's copy held one message
from the old publisher.

## What this does not cover

- **A person's attention.** The alerts only reach someone once Alertmanager is
  deployed (Phase 10); until then, `GET /api/v1/admin/dead-letters` is the
  place to look.
- **A UI.** The endpoints exist; no screen calls them yet.
- **DEAD outbox events**, which have SQL above but no endpoint.
- **An analysis longer than the dedup lease (120 s)** can overlap a second worker,
  so a replay is at-least-once processing, like everything else here.
