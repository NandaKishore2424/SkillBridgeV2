-- Dead letters a person can see: dead_letter_events
-- ============================================================================
-- Applied to Supabase on 2026-09-16, after the table was proven against a throwaway
-- PostgreSQL built from baseline.sql (the service, replay races and hostile
-- messages in DeadLetterServiceTest; the recorder against a real RabbitMQ in
-- DeadLetterRecorderBrokerTest). Idempotent: applied twice locally.
--
-- VERIFIED after applying, not assumed: live and db/schema/baseline.sql applied to
-- an empty pgvector/pgvector:pg17 both fingerprint to
--     622 catalogue objects, digest 4a9095e3aa02771c7eb23690897fe40d
-- which is the previous 589 plus exactly this table's 20 columns, 8 constraints,
-- 4 indexes and 1 sequence. Row-level security is on (relrowsecurity = true); the
-- fingerprint does not compare it.
--
-- WHY
--   skillbridge.dlq collects every message that could not be processed: a
--   permanent failure, retries exhausted, the delivery limit. A queue is a bad
--   place to leave them -- nobody can search it, reading a message means taking
--   it, and "what failed last Tuesday, and why" has no answer. DeadLetterRecorder
--   copies each one here and acks it, and an admin decides: replay it (through
--   the outbox, as a new event) or discard it with a note.
--
-- WHAT A ROW HOLDS
--   payload is the body VERBATIM, as text. Not JSONB, which the phase document
--   specified: the messages most likely to be dead-lettered are the ones that do
--   not parse, and a JSONB column would refuse exactly those. payload_json is the
--   parsed copy when there is one, for querying. A body that is not even UTF-8 is
--   stored base64-encoded, and says so in payload_encoding.
--
--   death_reason is 'consumer' when the consumer dead-lettered the message itself
--   and put its reason in a header (failure_reason); otherwise it is the
--   broker's x-death reason -- rejected, delivery_limit, expired, maxlen -- and
--   failure_reason is whatever the broker's record allows.
--
-- DUPLICATES
--   Delivery into the DLQ is at-least-once too: a recorder that dies between the
--   insert and the ack sees the message again. fingerprint is a SHA-256 of the
--   message id, the failure and the body, and the insert is ON CONFLICT DO
--   NOTHING. The same event failing the same way twice -- a duplicate delivery
--   of it -- is also one row; that is the intent, not a side effect.
--
-- STATUS
--   PENDING    waiting for a person
--   REPLAYED   republished through the outbox as replay_event_id
--   DISCARDED  decided against, with resolution_note
--   A replay creates a NEW event id; if that fails too, it arrives here as a new
--   row. This row stays REPLAYED -- it records what was decided about it.
--
-- TIMESTAMPTZ, unlike most of this schema: failed_at comes from the broker and the
-- consumer as an absolute instant, and a session time zone should not move it.
--
-- resolved_by has no foreign key, as audit_log.actor_user_id has none: a record
-- of who decided must outlive the account that decided.
-- ============================================================================

CREATE TABLE IF NOT EXISTS dead_letter_events (
    id                BIGSERIAL PRIMARY KEY,
    fingerprint       VARCHAR(64) NOT NULL,
    event_id          UUID,
    event_type        VARCHAR(100),
    routing_key       VARCHAR(255),
    source_queue      VARCHAR(255),
    death_reason      VARCHAR(50) NOT NULL,
    failure_reason    TEXT,
    retry_count       INTEGER NOT NULL DEFAULT 0,
    payload           TEXT NOT NULL,
    payload_encoding  VARCHAR(10) NOT NULL DEFAULT 'utf8',
    payload_json      JSONB,
    headers           JSONB,
    failed_at         TIMESTAMPTZ NOT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolved_at       TIMESTAMPTZ,
    resolved_by       BIGINT,
    resolution_note   VARCHAR(500),
    replay_event_id   UUID,
    CONSTRAINT uk_dead_letter_fingerprint UNIQUE (fingerprint),
    CONSTRAINT chk_dead_letter_status CHECK (status IN ('PENDING', 'REPLAYED', 'DISCARDED')),
    CONSTRAINT chk_dead_letter_encoding CHECK (payload_encoding IN ('utf8', 'base64')),
    -- Resolved exactly when no longer PENDING, and a replay always names its event.
    -- A REPLAYED row without replay_event_id would be a replay nobody can trace.
    CONSTRAINT chk_dead_letter_resolved CHECK ((status = 'PENDING') = (resolved_at IS NULL)),
    CONSTRAINT chk_dead_letter_replayed CHECK ((status = 'REPLAYED') = (replay_event_id IS NOT NULL)),
    CONSTRAINT chk_dead_letter_json CHECK (payload_json IS NULL OR payload_encoding = 'utf8'),
    CONSTRAINT chk_dead_letter_retry_count CHECK (retry_count >= 0)
);

-- The admin's default view (newest PENDING first), the bulk replay's selection,
-- and the pending-count gauge. Partial, so resolved history does not grow it.
CREATE INDEX IF NOT EXISTS idx_dead_letter_pending
    ON dead_letter_events (id DESC) WHERE status = 'PENDING';

-- The retention purge of resolved rows. PENDING rows are never purged.
CREATE INDEX IF NOT EXISTS idx_dead_letter_resolved
    ON dead_letter_events (resolved_at) WHERE status <> 'PENDING';

-- Not reachable through the REST API's anon/authenticated roles; the backend
-- connects as postgres, which bypasses RLS. Payloads carry student ids.
ALTER TABLE dead_letter_events ENABLE ROW LEVEL SECURITY;
