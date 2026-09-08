# Index strategy

Every index this application relies on, the query it serves, and how that was
verified. Written 2026-09-08 during Phase 05 Task 3.

> **Read this first.** The database holds 15 students, 1 batch and ~155 audit
> rows. At that size Postgres correctly prefers a sequential scan almost
> everywhere, so "the planner chose a Seq Scan" is *not* evidence an index is
> missing or broken. Where volume made the planner's own choice uninformative,
> the question asked instead was **can this index serve this predicate at all**
> — `SET enable_seqscan = off` and read the plan. That is the part under our
> control; volume is not.

---

## 1. Keyset pagination on the audit log

| Index | Serves |
|---|---|
| `idx_audit_log_college_seek (college_id, occurred_at DESC, id DESC)` | the college-scoped seek |
| `idx_audit_log_seek (occurred_at DESC, id DESC)` | the SYSTEM_ADMIN seek |

Equality column first, then the sort key in the direction it is read. Verified:
the planner picks them unprompted and produces an **Index Only Scan** whose
`Index Cond` carries the whole row comparison:

```
Index Cond: ((college_id = 1) AND (ROW(occurred_at, id) < ROW($1, $2)))
```

No Sort node, no heap access. The seek is satisfied inside the index, which is
what makes page 5000 cost what page 1 costs.

Two older indexes were dropped as part of this: `idx_audit_college` and
`idx_audit_time` were each a strict prefix of one of the above. All nine reads
`AuditLogRepository` issues were EXPLAINed before and after — every plan
byte-identical.

---

## 2. Trigram indexes for `?search=`

Phase 02 moved search server-side. The predicate is `lower(col) LIKE '%term%'`,
and a B-tree cannot serve a leading wildcard — Postgres has no option but a
sequential scan. `pg_trgm` indexes character trigrams, which it can.

Installed 2026-09-08 (`CREATE EXTENSION pg_trgm`), with:

| Index | Column |
|---|---|
| `idx_batches_name_trgm` | `lower(batches.name)` |
| `idx_batches_desc_trgm` | `lower(batches.description)` |
| `idx_students_name_trgm` | `lower(students.full_name)` |
| `idx_students_roll_trgm` | `lower(students.roll_number)` |
| `idx_trainers_name_trgm` | `lower(trainers.full_name)` |
| `idx_companies_name_trgm` | `lower(companies.name)` |
| `idx_users_email_trgm` | `lower(users.email)` |

They index the **lowercased** value, because the predicate is `lower(col) LIKE`
— an index on the raw column cannot serve it.

**Verified working for a single column.** With seqscan disabled:

```
Bitmap Heap Scan on students s
  Recheck Cond: (lower(full_name) ~~ '%arjun%')
  ->  Bitmap Index Scan on idx_students_name_trgm
        Index Cond: (lower(full_name) ~~ '%arjun%')
```

### The finding that matters: the student and trainer searches cannot use them

`StudentSpecifications.matches` ORs across five columns, and one of them —
`email` — lives on `users`, not `students`. A disjunction that spans two
relations **cannot be pushed to either side**: Postgres must join first and
then filter. The plan shows exactly that:

```
Merge Join
  Join Filter: (lower(s.full_name) ~~ '%arjun%' OR ... OR lower(u.email) ~~ '%arjun%')
```

`Join Filter`, not `Index Cond`. This is structural, not a cost decision, so it
does not change with volume. **The trigram indexes on `students` and `trainers`
buy nothing for the search as currently written.** They are kept because every
fix below needs them and they cost ~16 kB each at this size.

`TrainerSpecifications` has the same shape. `batches` and `companies` search
only their own columns, so they are not affected by this.

### What would fix it

In rough order of cost:

1. **Drop email from the people searches.** One-line change; loses "find a
   student by email", which is a real thing admins do.
2. **Rewrite as a `UNION`** of two single-table searches. Indexable, but does
   not compose with the Specification API that gives these endpoints their
   filtering.
3. **A maintained search column** — `students.search_text` as a
   `GENERATED ALWAYS AS (...) STORED` column over the entity's own text, plus
   one GIN index, with email denormalised onto it. One index instead of five,
   one predicate instead of an OR, and indexable. This is the design worth
   having; it is a schema change and has not been made.

### What is NOT established

Whether a **single-table** OR across several trigram-indexed columns becomes a
`BitmapOr` of index scans at volume. The real tables are far too small to make
the planner reveal it, and the 50k-row experiment set up to answer it was
aborted for time. Assume nothing here until it is measured.

---

## 3. Known gaps, not yet acted on

**Two foreign keys have no index on the referencing column.** Every `DELETE` on
the parent scans the child to enforce the constraint:

- `batches.deleted_by → users`
- `bulk_uploads.uploaded_by_user_id → users`

**Fourteen exact-duplicate index pairs**, same columns and same order — for
example `users_email_key` and `idx_users_email`, `students_user_id_key` and
`idx_students_user_id`. Each pair is maintained on every write and serves one
purpose.

> **Do not drop the `_live` indexes.** `idx_students_live`, `idx_batches_college_live`,
> `idx_colleges_live`, `idx_companies_live` and `idx_trainers_live` look like
> duplicates by column list and are **partial** (`WHERE deleted_at IS NULL`).
> A partial index over the same columns is a different, smaller index that
> serves the soft-delete-filtered queries every entity issues via
> `@SQLRestriction`. An audit that compares only `pg_index.indkey` will report
> them as duplicates. Check `pg_get_expr(indpred, indrelid)` before dropping
> anything.

**Two dead tables**, both empty and mapped by nothing:
`batch_enrollments` and `syllabi`. `batch_enrollments` is the same shape as the
`trainer_batches` bug fixed earlier — a table the schema carries and the code
does not know about. Nothing writes them; their only cost is that a `DELETE` on
`batches` scans them for FK enforcement.

---

## How to re-run the audit

The queries used are in the Phase 05 doc, Task 3.1. The three worth keeping:

```sql
-- indexes that are a strict prefix of another (redundant at any size)
SELECT ia.indrelid::regclass::text AS tbl, a.relname AS narrower, b.relname AS wider
FROM pg_index ia JOIN pg_class a ON a.oid = ia.indexrelid
JOIN pg_index ib ON ib.indrelid = ia.indrelid AND ib.indexrelid <> ia.indexrelid
JOIN pg_class b ON b.oid = ib.indexrelid
WHERE NOT ia.indisunique
  AND ib.indkey::text LIKE ia.indkey::text || '%'
  AND array_length(ib.indkey::int2[],1) > array_length(ia.indkey::int2[],1);

-- foreign keys with no index on the referencing column
SELECT c.conrelid::regclass::text AS tbl, a.attname AS col, c.conname
FROM pg_constraint c
JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
WHERE c.contype = 'f' AND k.ord = 1
  AND NOT EXISTS (SELECT 1 FROM pg_index i
                  WHERE i.indrelid = c.conrelid AND (i.indkey::int2[])[0] = k.attnum);

-- and ALWAYS check the predicate before calling two indexes duplicates
SELECT c.relname, pg_get_expr(i.indpred, i.indrelid) AS predicate
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
WHERE i.indrelid = 'students'::regclass;
```

`CREATE INDEX CONCURRENTLY` for anything applied to this database: it takes no
write lock, cannot run inside a transaction, and leaves an **INVALID** index
behind if it fails — one that does nothing and is still maintained on every
write. Check `pg_index.indisvalid` afterwards.
