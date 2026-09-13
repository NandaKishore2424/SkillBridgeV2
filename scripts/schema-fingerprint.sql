-- Canonical catalogue fingerprint for the `public` schema.
--
-- Run against two databases and compare the output line for line: identical
-- output means identical schema. Used by scripts/verify-schema-baseline.sh to
-- prove db/schema/baseline.sql still reproduces live.
--
-- WHY CONSTRAINTS ARE NORMALISED AND NOTHING ELSE IS
--
-- Postgres does not round-trip its own rendering of a cast. A CHECK written as
--     ANY ((ARRAY['X'::character varying])::text[])
-- is re-rendered, after being parsed and stored, as
--     ANY (ARRAY[('X'::character varying)::text])
-- -- the cast distributed over the elements. The expression is the same; the
-- text is not. Comparing constraint text byte-for-byte therefore reports a
-- difference between a database and a faithful rebuild of itself, forever.
--
-- So constraint definitions are compared with casts and layout stripped. That
-- is deliberately lossy and it is the only category that needs it: columns,
-- indexes, sequences, functions and triggers were all measured to round-trip
-- byte-identically on 2026-09-13, so they are compared verbatim and a
-- difference in them is real.
SELECT kind || '|' || name || '|' || def AS fingerprint FROM (

    SELECT 'column' AS kind,
           c.relname || '.' || a.attname AS name,
           format_type(a.atttypid, a.atttypmod)
             || CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END
             || COALESCE(' DEFAULT ' || pg_get_expr(ad.adbin, ad.adrelid), '')
             || CASE WHEN a.attgenerated = 's' THEN ' GENERATED' ELSE '' END AS def
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
    LEFT JOIN pg_attrdef ad ON ad.adrelid = c.oid AND ad.adnum = a.attnum
    WHERE n.nspname = 'public' AND c.relkind = 'r'

    UNION ALL
    -- Normalised: see the header.
    SELECT 'constraint',
           rel.relname || '.' || con.conname,
           translate(
               replace(replace(replace(pg_get_constraintdef(con.oid),
                   '::character varying', ''), '::text', ''), '[]', ''),
               '() ', '')
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace n ON n.oid = rel.relnamespace
    WHERE n.nspname = 'public'

    UNION ALL
    SELECT 'index', indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'

    UNION ALL
    SELECT 'sequence', c.relname, format_type(s.seqtypid, NULL) || ' inc ' || s.seqincrement
    FROM pg_sequence s
    JOIN pg_class c ON c.oid = s.seqrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'

    UNION ALL
    -- Extension-owned functions are excluded: pg_trgm and vector install dozens,
    -- they are not ours, and which schema Supabase puts them in is not our
    -- business to assert.
    SELECT 'function', p.proname, md5(pg_get_functiondef(p.oid))
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public' AND p.prokind = 'f'
      AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e')

    UNION ALL
    SELECT 'trigger', c.relname || '.' || t.tgname, pg_get_triggerdef(t.oid)
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND NOT t.tgisinternal

) t ORDER BY kind, name, def;
