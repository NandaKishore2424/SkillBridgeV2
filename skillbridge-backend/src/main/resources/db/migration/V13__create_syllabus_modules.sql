-- ============================================================================
-- V13 — Create syllabus_modules
-- ============================================================================
-- GAP-FILLER MIGRATION.
--
-- Versions 11, 13 and 14 were never committed to this repository. The table
-- `syllabus_modules` was created by hand on the live Supabase instance and no
-- migration in this repo ever created it. V15 then ran
-- `ALTER TABLE syllabus_modules ...` and `REFERENCES syllabus_modules(id)`,
-- which means Flyway applied to an EMPTY database fails at V15 with
-- `relation "syllabus_modules" does not exist`.
--
-- This migration takes the free V13 slot so it runs BEFORE V15 on a fresh
-- database. Against the live database — where V15/V16 are already applied and
-- the table already exists — every statement here is a no-op, and Flyway needs
-- `spring.flyway.out-of-order: true` (set in application.yaml) to accept a
-- version lower than the highest already applied.
--
-- Column set is derived from com.skillbridge.syllabus.entity.SyllabusModule.
-- Scheduling columns (start_date/end_date) are deliberately NOT created here:
-- V15 adds them with ADD COLUMN IF NOT EXISTS, and letting it do so keeps this
-- file faithful to what V13 would originally have contained.
-- ============================================================================

CREATE TABLE IF NOT EXISTS syllabus_modules (
    id            BIGSERIAL PRIMARY KEY,
    batch_id      BIGINT       NOT NULL REFERENCES batches(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    display_order INTEGER      NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_batch_display_order UNIQUE (batch_id, display_order)
);

CREATE INDEX IF NOT EXISTS idx_modules_batch ON syllabus_modules(batch_id);

COMMENT ON TABLE  syllabus_modules IS 'Top level of the curriculum tree: Module -> Sub-module -> Topic';
COMMENT ON COLUMN syllabus_modules.display_order IS 'Position within the batch curriculum; unique per batch';
