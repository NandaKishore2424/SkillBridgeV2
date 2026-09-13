-- Reference data: rows the application treats as part of its schema.
--
-- Applied after baseline.sql when building a test database. Not a fixture --
-- fixtures belong to the test that needs them (see TenantFixture). This is the
-- set of rows without which the application cannot function at all.
--
-- Only `roles` qualifies. `roles.name` carries a CHECK constraint listing
-- exactly these four values, so the table is a closed enumeration that happens
-- to live in the database, and every test that creates a user has to resolve
-- one of them.
--
-- Ids are not pinned deliberately, and the tests do not depend on them: they
-- resolve by name (`SELECT id FROM roles WHERE name = 'COLLEGE_ADMIN'`). Live
-- happens to have 1..4 in this order; nothing should rely on that.
--
-- `skills` holds 44 rows in live and is NOT reproduced here. No test reads it,
-- and copying a catalogue that tests do not use would make every test database
-- slower to build and invite tests to depend on ambient data -- which is the
-- habit TenantFixture exists to break.

INSERT INTO public.roles (name) VALUES
    ('SYSTEM_ADMIN'),
    ('COLLEGE_ADMIN'),
    ('TRAINER'),
    ('STUDENT')
ON CONFLICT (name) DO NOTHING;
