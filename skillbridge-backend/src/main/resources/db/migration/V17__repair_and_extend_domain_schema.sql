-- ============================================================================
-- V17 — Repair and extend the domain schema
-- ============================================================================
-- Runs on top of the live database as well as a freshly bootstrapped one, so
-- every statement is additive and idempotent. Three things happen here:
--
--   1. Tenancy denormalisation — college_id lands on every tenant-scoped table
--      that lacked it. Hibernate's @Filter cannot traverse a join, so a tenant
--      predicate needs the column locally or it cannot be expressed at all.
--   2. Optimistic locking — a `version` column on every row a human edits.
--   3. The columns the enrollment and progress modules need to stop being stubs.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Curriculum: tenancy column + the constraint the entity declares
-- ---------------------------------------------------------------------------
ALTER TABLE syllabus_modules ADD COLUMN IF NOT EXISTS college_id BIGINT;

UPDATE syllabus_modules m
SET    college_id = b.college_id
FROM   batches b
WHERE  b.id = m.batch_id
  AND  m.college_id IS NULL;

-- Orphan modules cannot be made tenant-safe; there should be none.
DELETE FROM syllabus_modules WHERE college_id IS NULL;

ALTER TABLE syllabus_modules ALTER COLUMN college_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_modules_college') THEN
        ALTER TABLE syllabus_modules
            ADD CONSTRAINT fk_modules_college
            FOREIGN KEY (college_id) REFERENCES colleges(id) ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_submodule_display_order') THEN
        ALTER TABLE syllabus_topics
            ADD CONSTRAINT uk_submodule_display_order UNIQUE (submodule_id, display_order);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_modules_college ON syllabus_modules(college_id, batch_id);

-- ---------------------------------------------------------------------------
-- 2. Batches: capacity, soft delete, optimistic locking
-- ---------------------------------------------------------------------------
ALTER TABLE batches ADD COLUMN IF NOT EXISTS capacity   INTEGER;
ALTER TABLE batches ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE batches ADD COLUMN IF NOT EXISTS version    BIGINT NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_batches_capacity') THEN
        ALTER TABLE batches ADD CONSTRAINT chk_batches_capacity
            CHECK (capacity IS NULL OR capacity > 0);
    END IF;
END $$;

COMMENT ON COLUMN batches.capacity IS 'Maximum enrolled students. NULL means uncapped.';
COMMENT ON COLUMN batches.deleted_at IS 'Soft delete marker. Every read path must filter on IS NULL.';

-- Partial index: the overwhelming majority of reads want live batches only.
CREATE INDEX IF NOT EXISTS idx_batches_college_live
    ON batches(college_id, status) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 3. Enrollments: lifecycle status, attribution, tenancy, locking
-- ---------------------------------------------------------------------------
ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS college_id   BIGINT;
ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS enrolled_by  BIGINT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;
ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS version      BIGINT NOT NULL DEFAULT 0;

UPDATE enrollments e
SET    college_id = b.college_id
FROM   batches b
WHERE  b.id = e.batch_id
  AND  e.college_id IS NULL;

DELETE FROM enrollments WHERE college_id IS NULL;
ALTER TABLE enrollments ALTER COLUMN college_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_enrollments_status') THEN
        ALTER TABLE enrollments ADD CONSTRAINT chk_enrollments_status
            CHECK (status IN ('ACTIVE', 'COMPLETED', 'DROPPED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_enrollments_student_status ON enrollments(student_id, status);
CREATE INDEX IF NOT EXISTS idx_enrollments_college        ON enrollments(college_id, batch_id);

-- ---------------------------------------------------------------------------
-- 4. Enrollment requests: student-initiated applications
-- ---------------------------------------------------------------------------
-- V12 modelled this table as "a trainer asks an admin to add a student", so
-- trainer_id is NOT NULL. A student applying to a batch has no trainer, so the
-- column has to become nullable and a `source` discriminator has to say which
-- kind of request a row is.
ALTER TABLE enrollment_requests ALTER COLUMN trainer_id DROP NOT NULL;

ALTER TABLE enrollment_requests ADD COLUMN IF NOT EXISTS source          VARCHAR(20) NOT NULL DEFAULT 'TRAINER_REQUEST';
ALTER TABLE enrollment_requests ADD COLUMN IF NOT EXISTS decision_reason TEXT;
ALTER TABLE enrollment_requests ADD COLUMN IF NOT EXISTS college_id      BIGINT;
ALTER TABLE enrollment_requests ADD COLUMN IF NOT EXISTS version         BIGINT NOT NULL DEFAULT 0;

UPDATE enrollment_requests r
SET    college_id = b.college_id
FROM   batches b
WHERE  b.id = r.batch_id
  AND  r.college_id IS NULL;

DELETE FROM enrollment_requests WHERE college_id IS NULL;
ALTER TABLE enrollment_requests ALTER COLUMN college_id SET NOT NULL;

-- The V12 CHECK only allows PENDING/APPROVED/REJECTED. The state machine adds
-- WITHDRAWN, EXPIRED and CANCELLED, so the constraint has to be replaced.
ALTER TABLE enrollment_requests DROP CONSTRAINT IF EXISTS enrollment_requests_status_check;
ALTER TABLE enrollment_requests DROP CONSTRAINT IF EXISTS chk_requests_status;
ALTER TABLE enrollment_requests
    ADD CONSTRAINT chk_requests_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'CANCELLED'));

ALTER TABLE enrollment_requests DROP CONSTRAINT IF EXISTS chk_requests_source;
ALTER TABLE enrollment_requests
    ADD CONSTRAINT chk_requests_source
    CHECK (source IN ('TRAINER_REQUEST', 'STUDENT_APPLICATION', 'ADMIN_DIRECT'));

-- A trainer-initiated request must name its trainer; a student application need not.
ALTER TABLE enrollment_requests DROP CONSTRAINT IF EXISTS chk_requests_trainer_presence;
ALTER TABLE enrollment_requests
    ADD CONSTRAINT chk_requests_trainer_presence
    CHECK (source <> 'TRAINER_REQUEST' OR trainer_id IS NOT NULL);

-- One open application per student per batch. A partial unique index is the
-- right tool: it constrains only PENDING rows, so a student whose application
-- was rejected can apply again, but cannot hold two live applications at once.
CREATE UNIQUE INDEX IF NOT EXISTS uk_requests_one_pending_per_student_batch
    ON enrollment_requests(batch_id, student_id, request_type)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_requests_college_status ON enrollment_requests(college_id, status);

-- ---------------------------------------------------------------------------
-- 5. Topic progress: the table has existed unused since V1
-- ---------------------------------------------------------------------------
ALTER TABLE topic_progress ADD COLUMN IF NOT EXISTS batch_id     BIGINT;
ALTER TABLE topic_progress ADD COLUMN IF NOT EXISTS college_id   BIGINT;
ALTER TABLE topic_progress ADD COLUMN IF NOT EXISTS score        INTEGER;
ALTER TABLE topic_progress ADD COLUMN IF NOT EXISTS started_at   TIMESTAMP;
ALTER TABLE topic_progress ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;
ALTER TABLE topic_progress ADD COLUMN IF NOT EXISTS version      BIGINT NOT NULL DEFAULT 0;

UPDATE topic_progress tp
SET    batch_id = m.batch_id, college_id = m.college_id
FROM   syllabus_topics t
JOIN   syllabus_submodules sm ON t.submodule_id = sm.id
JOIN   syllabus_modules    m  ON sm.module_id  = m.id
WHERE  tp.syllabus_topic_id = t.id
  AND  tp.batch_id IS NULL;

DELETE FROM topic_progress WHERE batch_id IS NULL OR college_id IS NULL;

ALTER TABLE topic_progress ALTER COLUMN batch_id   SET NOT NULL;
ALTER TABLE topic_progress ALTER COLUMN college_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_progress_score') THEN
        ALTER TABLE topic_progress ADD CONSTRAINT chk_progress_score
            CHECK (score IS NULL OR (score BETWEEN 0 AND 100));
    END IF;
END $$;

-- The V1 CHECK already allows exactly the four ProgressStatus values, so it stays.

-- Keep the denormalised columns honest no matter which code path writes the row.
CREATE OR REPLACE FUNCTION enforce_progress_denorm() RETURNS TRIGGER AS $$
BEGIN
    SELECT m.batch_id, m.college_id
      INTO NEW.batch_id, NEW.college_id
    FROM syllabus_topics t
    JOIN syllabus_submodules sm ON t.submodule_id = sm.id
    JOIN syllabus_modules    m  ON sm.module_id  = m.id
    WHERE t.id = NEW.syllabus_topic_id;

    IF NEW.batch_id IS NULL THEN
        RAISE EXCEPTION 'Cannot derive batch_id for syllabus topic %', NEW.syllabus_topic_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_progress_denorm ON topic_progress;
CREATE TRIGGER trg_progress_denorm
    BEFORE INSERT OR UPDATE OF syllabus_topic_id ON topic_progress
    FOR EACH ROW EXECUTE FUNCTION enforce_progress_denorm();

-- Indexes, each serving one named query.
-- Student progress page: this student's progress in this batch.
CREATE INDEX IF NOT EXISTS idx_progress_student_batch ON topic_progress(student_id, batch_id);
-- Trainer grading grid: every student's status for this topic.
CREATE INDEX IF NOT EXISTS idx_progress_topic_status  ON topic_progress(syllabus_topic_id, status);
-- Pending-count badge, rendered on every trainer page load.
CREATE INDEX IF NOT EXISTS idx_progress_pending
    ON topic_progress(batch_id, student_id) WHERE status = 'PENDING';
-- At-risk report.
CREATE INDEX IF NOT EXISTS idx_progress_needs_attention
    ON topic_progress(batch_id, updated_at DESC) WHERE status = 'NEEDS_IMPROVEMENT';
CREATE INDEX IF NOT EXISTS idx_progress_college ON topic_progress(college_id, batch_id);

-- ---------------------------------------------------------------------------
-- 6. Denormalised per-(student, batch) progress summary
-- ---------------------------------------------------------------------------
-- The dashboard renders a completion percentage for every enrolled batch. Doing
-- that from topic_progress means aggregating every topic row on every page load.
-- This table holds the rolled-up answer and is recomputed when grading happens.
CREATE TABLE IF NOT EXISTS student_batch_progress (
    id                   BIGSERIAL PRIMARY KEY,
    student_id           BIGINT    NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    batch_id             BIGINT    NOT NULL REFERENCES batches(id)  ON DELETE CASCADE,
    college_id           BIGINT    NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
    topics_total         INTEGER   NOT NULL DEFAULT 0,
    topics_completed     INTEGER   NOT NULL DEFAULT 0,
    topics_in_progress   INTEGER   NOT NULL DEFAULT 0,
    topics_needs_work    INTEGER   NOT NULL DEFAULT 0,
    weighted_percent     NUMERIC(5,2) NOT NULL DEFAULT 0,
    average_score        NUMERIC(5,2),
    last_activity_at     TIMESTAMP,
    recomputed_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version              BIGINT    NOT NULL DEFAULT 0,

    CONSTRAINT uk_student_batch_progress UNIQUE (student_id, batch_id),
    CONSTRAINT chk_sbp_percent CHECK (weighted_percent BETWEEN 0 AND 100)
);

CREATE INDEX IF NOT EXISTS idx_sbp_batch   ON student_batch_progress(batch_id, weighted_percent);
CREATE INDEX IF NOT EXISTS idx_sbp_college ON student_batch_progress(college_id, batch_id);

COMMENT ON TABLE student_batch_progress IS
    'Denormalised roll-up of topic_progress. Recomputed on grading; never edited directly.';

-- ---------------------------------------------------------------------------
-- 7. Idempotency keys
-- ---------------------------------------------------------------------------
-- Clients retry. Without this, a retried "apply to batch" creates a second
-- application, and a retried grading call double-writes an audit trail.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint        VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,

    CONSTRAINT uk_idempotency_key_user UNIQUE (idempotency_key, user_id)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expiry ON idempotency_keys(expires_at);

COMMENT ON COLUMN idempotency_keys.request_hash IS
    'SHA-256 of the request body. A replay with the same key but a different body is a client bug and returns 422.';
