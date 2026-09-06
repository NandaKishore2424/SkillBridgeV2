-- Soft delete, and a per-college roll number
-- ============================================================================
-- Applied to Supabase on 2026-09-06. Flyway was removed from this project, so
-- schema changes live here as a dated record of what was run rather than as a
-- migration the application replays. Every statement is idempotent.
--
-- WHY SOFT DELETE
--   Nothing in this application can be deleted today -- there is no DELETE
--   mapping for any top-level entity. A hard DELETE on a college cascades to
--   its users, students, batches, enrollments, feedback and audit trail, which
--   is unrecoverable and, for academic records, probably not lawful to do.
--   A deleted_at timestamp keeps the row and hides it.
-- ============================================================================

ALTER TABLE colleges  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE colleges  ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE companies ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE companies ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE trainers  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE trainers  ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE students  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE students  ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES users(id) ON DELETE SET NULL;
-- batches.deleted_at already exists (V17); it never had deleted_by.
ALTER TABLE batches   ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON COLUMN colleges.deleted_at IS 'Soft delete marker. Every read path must filter on IS NULL.';

-- Partial indexes: index only the live rows, which is what every read wants.
CREATE INDEX IF NOT EXISTS idx_colleges_live  ON colleges(id)                  WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_companies_live ON companies(college_id)         WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_trainers_live  ON trainers(college_id)          WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_students_live  ON students(college_id)          WHERE deleted_at IS NULL;

-- ============================================================================
-- Roll numbers: globally unique -> unique per college, among live rows only
--
-- Two separate bugs in one constraint.
--
--   1. TENANCY. students_roll_number_key is UNIQUE (roll_number) across the
--      whole table, so once one college has "CS-001" no other college can ever
--      use it. With one college live this has never been hit; it breaks on the
--      day a second one onboards. The Java already assumes per-college --
--      existsByRollNumberAndCollegeId, findByRollNumberAndCollegeId -- so the
--      schema was the half that was wrong.
--
--   2. SOFT DELETE. A deleted student keeps their row, and with a plain unique
--      constraint keeps their roll number reserved forever. Filtering the index
--      on deleted_at IS NULL frees it for reissue.
--
-- Verified before running: 0 duplicate (college_id, roll_number) pairs, 0 null
-- roll numbers, 15 students across 1 college.
-- ============================================================================

ALTER TABLE students DROP CONSTRAINT IF EXISTS students_roll_number_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_students_roll_live
    ON students(college_id, roll_number) WHERE deleted_at IS NULL;
