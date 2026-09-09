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

## 2. Trigram indexes for `?search=` — and the OR that could not use them

Phase 02 moved search server-side. The predicate is `lower(col) LIKE '%term%'`,
and a B-tree cannot serve a leading wildcard — Postgres has no option but a
sequential scan. `pg_trgm` indexes character trigrams, which it can.

Installed 2026-09-08 (`CREATE EXTENSION pg_trgm`), on the lowercased value,
because the predicate is `lower(col) LIKE` and an index on the raw column cannot
serve it.

### The finding: the student and trainer searches could not use them

`StudentSpecifications.matches` ORed five columns, and one — `email` — lives on
`users`, not `students`. **A disjunction that spans two relations cannot be
pushed to either side**, so Postgres joins first and filters after:

```
Merge Join
  Join Filter: (lower(s.full_name) ~~ '%arjun%' OR ... OR lower(u.email) ~~ '%arjun%')
```

`Join Filter`, not `Index Cond`. Structural, not a cost decision, so it does not
change with volume. `TrainerSpecifications` had the identical shape.

### Resolved 2026-09-09, and measured before it was chosen

The real tables hold 15 students, which is far too small for any plan to mean
anything — at that size Postgres correctly ignores every index. So the designs
were compared on a **50,000-row copy of this schema in a throwaway
`search_probe` schema, built, measured and dropped in the same session.**

The old plan, at 50k rows:

```
Hash Join
  Join Filter: (lower(s.full_name) ~~ ... OR lower(u.email) ~~ '%kulkarni%')
  Rows Removed by Join Filter: 8500
  ->  Seq Scan on users u  (actual rows=50000)     <-- every user, every keystroke
  ->  Bitmap Heap Scan on students s (actual rows=9000)
Execution Time: 43.7 ms
```

Four designs were measured, with the harness asserting that each returned
**exactly the same rows** as the baseline before timing it:

| design | 000042 (0 hits) | arjun.kumar1 (0) | sharma2 (84) | kulkarni (500) | correct? |
|---|---|---|---|---|---|
| OR across the join (before) | 50.21 ms | 50.74 ms | 50.68 ms | 20.42 ms | — |
| drop email, own columns only | 0.36 ms | 1.94 ms | **0.27 ms** | 2.50 ms | **no — missed all 84** |
| own column OR semi-join on users | 16.08 ms | 11.73 ms | 12.24 ms | 14.99 ms | yes |
| **`search_text` incl. email** | **0.58 ms** | **0.74 ms** | **1.24 ms** | **3.06 ms** | **yes** |

Two things that table settles:

- **Dropping email is not a cheap alternative, it is a wrong answer.** For
  `sharma2` it returned 0 rows where the truth was 84 — those students match on
  their email address and on nothing else. That is the cost the earlier draft of
  this document described as "loses a real thing admins do"; it is 100% of the
  results for that term.
- **The winning design is 33–87× faster on selective terms**, and on `@sbu.edu`,
  which matches 9001 of 9001 rows, it is **1.0×** — no better, and no worse.
  When a term matches nearly everything, a sequential scan *is* the right plan,
  and this design lets the planner choose it.

The plan it produces is the one worth wanting:

```
Bitmap Heap Scan on students s
  ->  BitmapAnd
        ->  Bitmap Index Scan on idx_students_search_live
              Index Cond: (search_text ~~ '%sharma2%')
        ->  Bitmap Index Scan on idx_students_college
```

4× fewer buffers than the baseline, and one GIN index (5496 kB at 50k rows)
replaces three trigram indexes (6216 kB) — smaller as well as usable.

### Why it needs a trigger and not just a generated column

A `GENERATED ALWAYS AS ... STORED` expression may only read columns of the same
row. Verified against this database:

```
cross-table (subquery on users.email)  -> REJECTED:
    cannot use subquery in column generation expression
same-row (full_name || roll_number)    -> ALLOWED
```

So email is denormalised onto `students.user_email` / `trainers.user_email` by
`trg_students_user_email` / `trg_trainers_user_email`, and `search_text` is
generated over that plus the row's own text.

**The split is the point.** Postgres itself guarantees `search_text` is never
stale with respect to four of its five inputs, so the trigger surface shrinks to
the one input it cannot cover — email. That one is kept current by
`trg_users_email_propagate` on `users`, which is the half that is easy to
forget: without it, changing someone's address leaves them findable only by
their **old** one, and nothing errors.

`SearchTextMaintenanceTest` guards exactly that, and its assertions were
confirmed non-vacuous by making `user_email` stale and watching the current
address become unfindable.

### Still not dropped

`idx_students_name_trgm`, `idx_students_roll_trgm` and `idx_trainers_name_trgm`
were built for a predicate that could never use them and are now unreferenced.
They are kept for one release so the new plan can be observed in production
first; dropping an index is the step that is expensive to undo on a large table.
`idx_users_email_trgm` stays permanently — the users list searches email on its
own table, where a trigram index does work.

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
