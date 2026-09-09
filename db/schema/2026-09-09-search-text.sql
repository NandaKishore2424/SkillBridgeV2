-- One indexable search column for people, replacing an OR that spans a join
-- ============================================================================
-- Applied to Supabase on 2026-09-09. Flyway was removed from this project, so
-- schema changes live here as a dated record of what was run rather than as a
-- migration the application replays. Every statement is idempotent.
--
-- WHY
--   The admin student and trainer lists search with `?search=`, and the
--   predicate ORed five columns -- four on students, and email on users. A
--   disjunction that spans two relations cannot be pushed to either side, so
--   Postgres joins first and filters after. The seven trigram indexes added on
--   2026-09-08 could not be used by it at all.
--
--   Measured on a 50,000-row copy of this schema (built, measured and dropped
--   on 2026-09-09 -- see docs/INDEX_STRATEGY.md 2):
--
--     term            matches   OR-across-join   search_text   speedup
--     000042                0        50.21 ms       0.58 ms      86.6x
--     arjun.kumar1          0        50.74 ms       0.74 ms      68.2x
--     sharma2              84        50.68 ms       1.24 ms      40.8x
--     kulkarni            500        20.42 ms       3.06 ms       6.7x
--     @sbu.edu           9001        14.25 ms      14.97 ms       1.0x
--
--   The old plan cost ~50 ms whatever was typed, because it read every users
--   row (Seq Scan on users, 50,000 rows) and every live student in the college.
--   The last line matters as much as the first: when a term matches nearly
--   everything a sequential scan is the right plan, and this design does not
--   make that case worse.
--
-- WHY A TRIGGER AND NOT JUST A GENERATED COLUMN
--   A GENERATED ALWAYS AS ... STORED expression may only read columns of the
--   same row, so it cannot reach users.email:
--       ERROR: cannot use subquery in column generation expression
--   So email is denormalised onto students/trainers as user_email, maintained
--   by trigger, and search_text is generated over that plus the row's own text.
--   That split is deliberate: Postgres itself guarantees search_text is never
--   stale with respect to four of its five inputs, and the trigger surface
--   shrinks to the one input it cannot cover.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. The denormalised email, and the generated search column over it
-- ---------------------------------------------------------------------------

ALTER TABLE students ADD COLUMN IF NOT EXISTS user_email VARCHAR(255);
ALTER TABLE trainers ADD COLUMN IF NOT EXISTS user_email VARCHAR(255);

-- Backfill before the generated column is added, so it is correct on creation.
UPDATE students s SET user_email = u.email
  FROM users u WHERE u.id = s.user_id AND s.user_email IS DISTINCT FROM u.email;
UPDATE trainers t SET user_email = u.email
  FROM users u WHERE u.id = t.user_id AND t.user_email IS DISTINCT FROM u.email;

-- coalesce on every nullable input: `a || NULL` is NULL, which would blank the
-- whole column for any student with no degree recorded.
ALTER TABLE students ADD COLUMN IF NOT EXISTS search_text TEXT
  GENERATED ALWAYS AS (
    lower(full_name) || ' ' || lower(roll_number) || ' ' ||
    lower(coalesce(degree, '')) || ' ' || lower(coalesce(branch, '')) || ' ' ||
    lower(coalesce(user_email, ''))
  ) STORED;

ALTER TABLE trainers ADD COLUMN IF NOT EXISTS search_text TEXT
  GENERATED ALWAYS AS (
    lower(full_name) || ' ' ||
    lower(coalesce(department, '')) || ' ' || lower(coalesce(specialization, '')) || ' ' ||
    lower(coalesce(user_email, ''))
  ) STORED;

-- ---------------------------------------------------------------------------
-- 2. Keeping user_email true
-- ---------------------------------------------------------------------------

-- On insert, and if a profile is ever repointed at a different login.
CREATE OR REPLACE FUNCTION sync_user_email() RETURNS TRIGGER AS $$
BEGIN
    SELECT u.email INTO NEW.user_email FROM users u WHERE u.id = NEW.user_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_students_user_email ON students;
CREATE TRIGGER trg_students_user_email
    BEFORE INSERT OR UPDATE OF user_id ON students
    FOR EACH ROW EXECUTE FUNCTION sync_user_email();

DROP TRIGGER IF EXISTS trg_trainers_user_email ON trainers;
CREATE TRIGGER trg_trainers_user_email
    BEFORE INSERT OR UPDATE OF user_id ON trainers
    FOR EACH ROW EXECUTE FUNCTION sync_user_email();

-- The half that is easy to forget: an email change on users must rewrite the
-- profile rows, or search_text goes stale and searching the new address finds
-- nothing. This is the trigger that makes the denormalisation honest.
CREATE OR REPLACE FUNCTION propagate_user_email() RETURNS TRIGGER AS $$
BEGIN
    UPDATE students SET user_email = NEW.email WHERE user_id = NEW.id;
    UPDATE trainers SET user_email = NEW.email WHERE user_id = NEW.id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_users_email_propagate ON users;
CREATE TRIGGER trg_users_email_propagate
    AFTER UPDATE OF email ON users
    FOR EACH ROW WHEN (OLD.email IS DISTINCT FROM NEW.email)
    EXECUTE FUNCTION propagate_user_email();

-- ---------------------------------------------------------------------------
-- 3. One GIN index each, partial on the soft-delete predicate
-- ---------------------------------------------------------------------------
-- Partial to match @SQLRestriction("deleted_at IS NULL"), which every query on
-- these entities carries -- the same shape as the existing _live indexes. On a
-- 50,000-row copy one such index was 5496 kB against 6216 kB for the three
-- trigram indexes it replaces: smaller, and unlike them, actually usable.
--
-- CREATE INDEX CONCURRENTLY on a populated table. These two are tiny today, but
-- the convention for this database is in docs/INDEX_STRATEGY.md and there is no
-- reason to break it here.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_students_search_live
    ON students USING gin (search_text gin_trgm_ops) WHERE deleted_at IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trainers_search_live
    ON trainers USING gin (search_text gin_trgm_ops) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 4. The trigram indexes this replaces
-- ---------------------------------------------------------------------------
-- idx_students_name_trgm, idx_students_roll_trgm and idx_trainers_name_trgm
-- were added for a predicate that could never use them. They are NOT dropped
-- here: nothing else references them yet, but dropping an index is the one step
-- that cannot be undone cheaply on a large table, and they cost ~16 kB each at
-- this size. Drop them in a later change once the new plan has been observed in
-- production. idx_users_email_trgm stays regardless -- the users list searches
-- email on its own table, where a trigram index does work.
