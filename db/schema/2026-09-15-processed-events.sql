-- Consumer-side deduplication: processed_events
-- ============================================================================
-- Applied to Supabase on 2026-09-15, after the store was proven against a
-- throwaway PostgreSQL built from baseline.sql (8 tests, including 12 threads
-- racing to claim one event: exactly one won). Idempotent: applied twice locally.
--
-- VERIFIED after applying, not assumed: live and db/schema/baseline.sql applied to
-- an empty pgvector/pgvector:pg17 both fingerprint to
--     589 catalogue objects, digest 215daa33f8d95ea1bfaeacc1ac15cd0c
-- which is the previous 575 plus exactly this table's 7 columns, 4 constraints and
-- 3 indexes. Row-level security is on (checked: relrowsecurity = true); the
-- fingerprint does not compare it.
--
-- WHY
--   Every hop from the outbox to the AI service is at-least-once. The relay
--   republishes after a crash between publish and record; the consumer's
--   republish-then-ack retry can deliver twice; a lost ack redelivers a message
--   that was already handled. Each duplicate carries the same message_id -- the
--   outbox event_id -- and this table is how a consumer notices.
--
-- ONE ROW PER (consumer, event_id)
--   Keyed on the consumer as well as the event, so a second consumer of the same
--   events deduplicates independently instead of skipping events because the
--   first one processed them.
--
-- STATUS AND THE LEASE
--   IN_PROGRESS  claimed; lease_until says until when. A claim whose lease has
--                passed can be taken over, so a worker killed mid-event blocks
--                nothing for longer than the lease.
--   DONE         processed; a later delivery of the same event is acknowledged
--                and skipped.
--
--   The claim is one INSERT .. ON CONFLICT DO UPDATE .. WHERE lease expired, so
--   two deliveries racing for the same event cannot both win. That is the
--   difference from the phase document's check-then-mark, where both could.
--
-- attempts doubles as a fencing token: a worker whose lease was taken over
-- releases nothing, because the attempt count it holds is no longer current.
--
-- TIMESTAMPTZ, unlike most of this schema: the lease is compared with now() by a
-- different service (Python) from the one that writes most tables, and a session
-- time zone should not be able to move a lease.
-- ============================================================================

CREATE TABLE IF NOT EXISTS processed_events (
    consumer       VARCHAR(100) NOT NULL,
    event_id       UUID NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    attempts       INTEGER NOT NULL DEFAULT 1,
    lease_until    TIMESTAMPTZ NOT NULL,
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT processed_events_pkey PRIMARY KEY (consumer, event_id),
    CONSTRAINT chk_processed_events_status CHECK (status IN ('IN_PROGRESS', 'DONE')),
    -- DONE exactly when completed: a DONE row with no completion time could never
    -- be purged, and an IN_PROGRESS row with one would be a lie about the event.
    CONSTRAINT chk_processed_events_completed CHECK ((status = 'DONE') = (completed_at IS NOT NULL)),
    CONSTRAINT chk_processed_events_attempts CHECK (attempts >= 1)
);

-- The retention purge of DONE rows. Partial: the claim path uses the primary key.
CREATE INDEX IF NOT EXISTS idx_processed_events_done
    ON processed_events (completed_at) WHERE status = 'DONE';

-- The purge of abandoned claims (released, then dead-lettered, never completed).
CREATE INDEX IF NOT EXISTS idx_processed_events_in_progress
    ON processed_events (lease_until) WHERE status = 'IN_PROGRESS';

-- Row-level security ON, with no policies: nothing reachable through Supabase's
-- REST API (the anon and authenticated roles) can read or write this table. The
-- services connect as postgres, which bypasses RLS, so they are unaffected.
-- Supabase's advisor reports the other 30 public tables with RLS OFF; that is a
-- separate decision, not taken here -- but a new table should not add a 31st.
ALTER TABLE processed_events ENABLE ROW LEVEL SECURITY;
