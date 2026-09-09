-- Idempotency keys: the lifecycle columns the table was missing
-- ============================================================================
-- Applied to Supabase on 2026-09-09. Flyway was removed from this project, so
-- schema changes live here as a dated record of what was run. Idempotent.
--
-- WHY
--   idempotency_keys has existed since Phase 03 and nothing has ever written to
--   it. Implementing the interceptor needs two columns the original DDL left
--   out, and both carry the part of the contract that is easy to skip:
--
--   state         An idempotency record is written BEFORE the handler runs and
--                 completed after, so a second request arriving while the first
--                 is still in flight must be told to wait rather than served a
--                 half-written response. Without a state column there is no way
--                 to tell "in progress" from "finished", and the 409 case
--                 cannot exist.
--   completed_at  When the response was recorded, as distinct from when the key
--                 was claimed. created_at alone cannot answer "how long did the
--                 in-flight request take" or "is this row stuck".
--
-- ON THE UNIQUE CONSTRAINT
--   The phase doc specifies UNIQUE (idempotency_key, user_id, endpoint); the
--   table has UNIQUE (idempotency_key, user_id). The existing one is kept, and
--   it is the stricter of the two: a key reused on a DIFFERENT endpoint is a
--   client bug of exactly the same kind as a key reused with a different body,
--   and this constraint makes it detectable instead of allowing two unrelated
--   records to share a key. The interceptor returns 422 for it, the same as the
--   body mismatch. endpoint is still stored, which is what makes that check
--   possible.
-- ============================================================================

ALTER TABLE idempotency_keys
    ADD COLUMN IF NOT EXISTS state VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS';

ALTER TABLE idempotency_keys
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;

-- The application is the only writer, but a CHECK is what stops a typo in a
-- future migration or a manual fix putting an unreadable state in the column.
ALTER TABLE idempotency_keys DROP CONSTRAINT IF EXISTS ck_idempotency_state;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT ck_idempotency_state
    CHECK (state IN ('IN_PROGRESS', 'COMPLETED'));

-- A COMPLETED row must carry the response it is going to replay, and an
-- IN_PROGRESS row must not pretend to have one. This is the invariant the
-- replay path depends on: without it a COMPLETED row with a null status would
-- serve a 0-status empty response and look like a network failure to the client.
ALTER TABLE idempotency_keys DROP CONSTRAINT IF EXISTS ck_idempotency_completed;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT ck_idempotency_completed
    CHECK ( (state = 'IN_PROGRESS' AND response_status IS NULL AND completed_at IS NULL)
         OR (state = 'COMPLETED'   AND response_status IS NOT NULL AND completed_at IS NOT NULL) );
