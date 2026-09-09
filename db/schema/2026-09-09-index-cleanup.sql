-- Redundant indexes and two dead tables
-- ============================================================================
-- Applied to Supabase on 2026-09-09. Flyway was removed from this project, so
-- schema changes live here as a dated record of what was run rather than as a
-- migration the application replays.
--
-- The catalogue in docs/INDEX_STRATEGY.md 3 was re-derived before acting on it,
-- and three of its numbers were wrong. Recording that, because the corrections
-- are the useful part:
--
--   claimed                          actual
--   14 exact-duplicate index pairs   1  (comparing indkey alone conflates a
--                                        unique index with a non-unique one and
--                                        ignores partial predicates entirely)
--   2 unindexed foreign keys         10 in the public schema
--   "do not drop the _live indexes"  true for four of the five; idx_students_live
--                                    is genuinely redundant, see below
--
-- Two indexes are comparable only if EVERYTHING matches: access method, key
-- columns and their order, opclasses, expressions, uniqueness AND predicate.
-- The audit here normalises pg_get_indexdef() minus the index's own name, which
-- makes all of that structural rather than a judgement call.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Two dead tables
-- ---------------------------------------------------------------------------
-- Both empty, mapped by no entity, referenced by no Java, TypeScript, Python or
-- SQL in the repository, and depended on by nothing -- no inbound foreign key,
-- no view, no rule. They have only OUTBOUND foreign keys, to batches and
-- students, which is precisely their cost: every DELETE on a parent scans them
-- to enforce a constraint that protects nothing.
--
-- batch_enrollments is the trainer_batches bug in another costume: a table the
-- schema carries, that models a relationship the code already models elsewhere
-- (enrollments, which holds the real rows), and that nothing ever writes.

-- Emptiness and isolation were re-checked immediately before running these, not
-- just when the change was written: 0 rows each, 0 inbound foreign keys, 0 views
-- or rules.

DROP TABLE IF EXISTS batch_enrollments;
DROP TABLE IF EXISTS syllabi;

-- They carried the last two redundant indexes with them,
-- idx_batch_enrollments_batch_id and idx_syllabi_batch_id, so the audit in
-- section 2 now returns nothing at all.

-- ---------------------------------------------------------------------------
-- 2. Redundant indexes
-- ---------------------------------------------------------------------------
-- Each of these is covered by another index on the same table with the same
-- predicate, where "covered" means a B-tree can serve every query the dropped
-- index could: either an identical definition, or a UNIQUE index over the same
-- columns, or a wider index that leads with the same columns.
--
-- The trade being made: a narrower index is smaller, so a scan that reads many
-- rows through it touches fewer pages than through the wider one. That is a
-- real cost, and it is worth paying -- each of these is maintained on every
-- insert and update of its table, forever, to save a fraction of a page read on
-- a table with fifteen rows.
--
-- APPLIED AS ONE TRANSACTION, not with CONCURRENTLY, which is a deliberate
-- departure from this project's convention. CONCURRENTLY cannot run inside a
-- transaction block, so twenty of them are twenty separate commits: if one
-- fails halfway the schema is left in a state nobody wrote down. These indexes
-- are 8-40 kB on tables of fifteen rows, so the exclusive lock is measured in
-- microseconds, and `SET LOCAL lock_timeout` makes the whole batch abort rather
-- than queue behind a long transaction -- a waiting DDL statement blocks every
-- new query on the table behind it, which is the actual hazard the convention
-- exists to avoid. On a large table, use CONCURRENTLY and accept the
-- non-atomicity.

SET LOCAL lock_timeout = '3s';

-- 2a. Exact duplicate: same table, same column, same everything.
--     Kept idx_syllabus_modules_batch_id, which matches the idx_<table>_<column>
--     convention the rest of the schema uses.
DROP INDEX IF EXISTS idx_modules_batch;

-- 2b. Non-unique index shadowed by a UNIQUE index on the same columns. The
--     unique index has to exist -- it enforces a constraint -- so the plain one
--     is pure overhead.
DROP INDEX IF EXISTS idx_college_admins_user_id;     -- college_admins_user_id_key
DROP INDEX IF EXISTS idx_colleges_code;              -- colleges_code_key
DROP INDEX IF EXISTS idx_refresh_tokens_token_hash;  -- refresh_tokens_token_hash_key
DROP INDEX IF EXISTS idx_students_user_id;           -- students_user_id_key
DROP INDEX IF EXISTS idx_trainers_user_id;           -- trainers_user_id_key
DROP INDEX IF EXISTS idx_users_email;                -- users_email_key
DROP INDEX IF EXISTS idx_submodules_order;           -- uk_module_submodule_order

-- 2c. A strict leading prefix of a wider index with the same predicate.
DROP INDEX IF EXISTS idx_batch_companies_batch_id;   -- batch_companies_pkey (batch_id, company_id)
DROP INDEX IF EXISTS idx_batch_trainers_batch_id;    -- batch_trainers_pkey (batch_id, trainer_id)
DROP INDEX IF EXISTS idx_batches_college_id;         -- idx_batches_college_status (college_id, status)
DROP INDEX IF EXISTS idx_enrollments_batch_id;       -- enrollments_batch_id_student_id_key
DROP INDEX IF EXISTS idx_enrollments_student_id;     -- idx_enrollments_student_status (student_id, status)
DROP INDEX IF EXISTS idx_student_skills_student_id;  -- student_skills_pkey (student_id, skill_id)
DROP INDEX IF EXISTS idx_submodules_module;          -- uk_module_submodule_order (module_id, display_order)
DROP INDEX IF EXISTS idx_topics_submodule;           -- uk_submodule_display_order (submodule_id, display_order)
DROP INDEX IF EXISTS idx_topic_progress_student_id;  -- idx_topic_progress_student_status (student_id, status)
DROP INDEX IF EXISTS idx_topic_progress_topic_id;    -- idx_progress_topic_status (syllabus_topic_id, status)
DROP INDEX IF EXISTS idx_user_roles_user_id;         -- user_roles_pkey (user_id, role_id)

-- 2d. The one _live index that really is redundant, against the standing advice
--     not to touch them.
--
--     That advice is right about the mistake it describes: comparing column
--     lists alone makes a partial index look like a duplicate of a non-partial
--     one, and it is not -- idx_batches_college_live (college_id, status) WHERE
--     deleted_at IS NULL is a different, smaller index than
--     idx_batches_college_status (college_id, status), and both are kept.
--
--     But prefix redundancy still applies WITHIN one predicate. These two share
--     an identical predicate:
--       idx_students_live      (college_id)              WHERE deleted_at IS NULL
--       uk_students_roll_live  (college_id, roll_number) WHERE deleted_at IS NULL
--     college_id leads both, so every plan that could use the first can use the
--     second. idx_colleges_live, idx_companies_live, idx_trainers_live and
--     idx_batches_college_live have no such partner and are kept.
DROP INDEX IF EXISTS idx_students_live;

-- ---------------------------------------------------------------------------
-- 3. The ten unindexed foreign keys are DELIBERATELY left unindexed
-- ---------------------------------------------------------------------------
-- batches.deleted_by, colleges.deleted_by, companies.deleted_by,
-- students.deleted_by, trainers.deleted_by, enrollments.enrolled_by,
-- enrollment_requests.reviewed_by, bulk_uploads.uploaded_by_user_id,
-- idempotency_keys.user_id -> users, and topic_progress.updated_by -> trainers.
--
-- An unindexed foreign key costs a scan of the child table when the PARENT row
-- is deleted or its key updated. Both halves of that were checked:
--
--   * Nothing hard-deletes a user or a trainer. The application soft-deletes;
--     the only @Modifying DELETE in the codebase is TopicProgress by student
--     and batch. So the scan these indexes would avoid does not happen.
--   * None of these columns is a query predicate anywhere -- no derived finder,
--     no Specification, no @Query mentions any of them. They are written as an
--     audit trail and read back only through the row that carries them.
--
-- Adding ten indexes that serve neither purpose is the thing this phase warns
-- about in its own words: an index that is not used is worse than no index,
-- because it costs write throughput and buys nothing.
--
-- What would change the answer: a hard-delete or GDPR erasure path on users, or
-- a screen that filters by "deleted by" or "uploaded by". If topic_progress
-- grows large first, topic_progress.updated_by -> trainers is the one to index
-- before the others, because it is the biggest child table.
