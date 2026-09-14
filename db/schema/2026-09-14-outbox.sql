-- Transactional outbox
-- ============================================================================
-- NOT YET APPLIED to Supabase. Written 2026-09-14. It is applied only after the
-- outbox is proven against a throwaway container, and this header is updated
-- with the date it ran. Idempotent: safe to run twice.
--
-- WHY
--   AIEventPublisher published from an afterCommit callback on a background
--   executor and swallowed every AmqpException. A broker that was down, slow or
--   refusing meant the event was gone: no retry, no queue, no record it should
--   have existed. You cannot atomically write to a database AND publish to a
--   broker; they are separate systems with separate transactions.
--
--   The outbox turns "publish" into "insert a row", which IS transactional. The
--   business change and its event commit or roll back together, and a separate
--   relay publishes committed rows to RabbitMQ, retrying until the broker
--   confirms or the row is declared DEAD.
--
-- STATUS AND THE LEASE
--   PENDING    not yet confirmed by the broker
--   PUBLISHED  the broker confirmed it; kept for a while as an audit
--   DEAD       gave up after the maximum attempts; needs a human
--
--   There is no IN_FLIGHT or FAILED state, on purpose. The relay claims a batch
--   by pushing next_attempt_at forward by a lease and bumping attempts, in one
--   short transaction, then publishes OUTSIDE any transaction. A relay that
--   dies mid-batch leaves those rows PENDING; they become due again when the
--   lease runs out. A status column would need a sweeper to rescue rows stuck in
--   it, and would be one more way to lose an event.
--
-- DELIVERY
--   At-least-once. A relay that publishes and then dies before recording it will
--   publish again after the lease. event_id is what a consumer deduplicates on.
-- ============================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id               BIGSERIAL PRIMARY KEY,
    event_id         UUID NOT NULL,
    aggregate_type   VARCHAR(100) NOT NULL,
    aggregate_id     VARCHAR(100) NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    schema_version   INTEGER NOT NULL DEFAULT 1,
    routing_key      VARCHAR(255) NOT NULL,
    payload          JSONB NOT NULL,
    headers          JSONB,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts         INTEGER NOT NULL DEFAULT 0,
    last_error       TEXT,
    next_attempt_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at     TIMESTAMP,
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD'))
);

-- The relay's poll: WHERE status = 'PENDING' AND next_attempt_at <= now ORDER BY id.
-- Partial, so it stays small however many PUBLISHED rows accumulate.
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox_events (next_attempt_at, id) WHERE status = 'PENDING';

-- The retention purge of old PUBLISHED rows.
CREATE INDEX IF NOT EXISTS idx_outbox_published
    ON outbox_events (published_at) WHERE status = 'PUBLISHED';

-- Listing what needs a human, newest first.
CREATE INDEX IF NOT EXISTS idx_outbox_dead
    ON outbox_events (created_at DESC) WHERE status = 'DEAD';
