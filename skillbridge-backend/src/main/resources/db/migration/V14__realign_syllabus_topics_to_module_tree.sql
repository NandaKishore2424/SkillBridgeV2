-- ============================================================================
-- V14 — Realign syllabus_topics with the curriculum tree
-- ============================================================================
-- GAP-FILLER MIGRATION (see V13 for the full explanation of the 11/13/14 gap).
--
-- V1 created `syllabus_topics` as a child of the old flat `syllabi` table:
--     syllabus_id NOT NULL, title NOT NULL, order_index NOT NULL,
--     difficulty, estimated_hours
--
-- The current entity (com.skillbridge.syllabus.entity.SyllabusTopic) maps a
-- completely different column set:
--     submodule_id, name, display_order, is_completed, completed_at
--
-- V15 only ever added `submodule_id`. On a fresh database that leaves three
-- legacy NOT NULL columns nothing populates, and four entity columns that do
-- not exist — so every INSERT from the application fails. On the live database
-- this reconciliation was done by hand.
--
-- Every statement below is written to be a no-op when it has already been
-- applied, so this file is safe against both an empty database and the live one.
--
-- Data loss note: V15 already declares that existing topic rows are discarded
-- ("This will DROP all existing topics data! User confirmed"). Dropping the
-- legacy columns here is consistent with that decision.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Add the columns the entity expects
-- ---------------------------------------------------------------------------
ALTER TABLE syllabus_topics ADD COLUMN IF NOT EXISTS name          VARCHAR(255);
ALTER TABLE syllabus_topics ADD COLUMN IF NOT EXISTS display_order INTEGER;
ALTER TABLE syllabus_topics ADD COLUMN IF NOT EXISTS is_completed  BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE syllabus_topics ADD COLUMN IF NOT EXISTS completed_at  TIMESTAMP;

-- ---------------------------------------------------------------------------
-- 2. Carry over any legacy data, then retire the legacy columns.
--    Guarded on column existence so this is inert on the live database.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'syllabus_topics' AND column_name = 'title') THEN
        UPDATE syllabus_topics SET name = title WHERE name IS NULL;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'syllabus_topics' AND column_name = 'order_index') THEN
        UPDATE syllabus_topics SET display_order = order_index WHERE display_order IS NULL;
    END IF;
END $$;

ALTER TABLE syllabus_topics DROP COLUMN IF EXISTS syllabus_id;
ALTER TABLE syllabus_topics DROP COLUMN IF EXISTS title;
ALTER TABLE syllabus_topics DROP COLUMN IF EXISTS order_index;
ALTER TABLE syllabus_topics DROP COLUMN IF EXISTS difficulty;
ALTER TABLE syllabus_topics DROP COLUMN IF EXISTS estimated_hours;

-- ---------------------------------------------------------------------------
-- 3. Enforce the entity's NOT NULL contract.
--    Any surviving row with a NULL here is unmappable, so backfill first.
-- ---------------------------------------------------------------------------
UPDATE syllabus_topics SET name          = 'Untitled topic' WHERE name IS NULL;
UPDATE syllabus_topics SET display_order = 0                WHERE display_order IS NULL;

ALTER TABLE syllabus_topics ALTER COLUMN name          SET NOT NULL;
ALTER TABLE syllabus_topics ALTER COLUMN display_order SET NOT NULL;

COMMENT ON COLUMN syllabus_topics.name         IS 'Topic title (replaces the legacy "title" column)';
COMMENT ON COLUMN syllabus_topics.display_order IS 'Position within the sub-module (replaces legacy "order_index")';
COMMENT ON COLUMN syllabus_topics.is_completed IS 'Trainer-level completion flag for the topic as taught, not per student';
