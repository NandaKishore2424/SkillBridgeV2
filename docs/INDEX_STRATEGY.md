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

## 3. The catalogued cleanups — done 2026-09-09, and what the catalogue got wrong

This section used to list gaps. They are closed. The corrections are worth more
than the closure, because all three came from re-deriving the audit instead of
trusting what was written here.

| this document claimed | actual |
|---|---|
| 14 exact-duplicate index pairs | **1** |
| 2 unindexed foreign keys | **10** in `public` |
| "do not drop the `_live` indexes" | true for four of five |

**Why 14 became 1.** The original audit compared `pg_index.indkey` — the column
list — and nothing else. That conflates a `UNIQUE` index with a plain one over
the same columns, and it ignores partial predicates entirely, which is the very
mistake this section warned about two paragraphs later. Two indexes are
interchangeable only if *everything* matches: access method, key columns and
order, opclasses, expressions, uniqueness **and** predicate. Normalising
`pg_get_indexdef()` minus the index's own name makes that structural rather than
a judgement call, and on that basis there was exactly one true duplicate.

The other 19 redundancies were real but of two different kinds, which is why
they need different reasoning:

- **7 shadowed by a `UNIQUE` index** on the same columns — `idx_users_email`
  under `users_email_key`, and similar. The unique index enforces a constraint
  so it cannot go; the plain one is pure write overhead.
- **12 a strict leading prefix of a wider index** with the same predicate —
  `idx_enrollments_student_id` under `idx_enrollments_student_status`,
  `idx_user_roles_user_id` under `user_roles_pkey`.

20 indexes dropped, 145 → 125. **Verified afterwards that the set of unindexed
foreign keys was byte-for-byte unchanged** — the drops removed redundancy, not
coverage.

### The one `_live` index that really was redundant

The standing advice here was *do not drop the `_live` indexes*, and it is right
about the mistake it describes. `idx_batches_college_live` is
`(college_id, status) WHERE deleted_at IS NULL` and `idx_batches_college_status`
is `(college_id, status)` with no predicate: a column-list comparison calls
those duplicates, and they are not. Both are kept.

But prefix redundancy still applies *within* one predicate:

```
idx_students_live      (college_id)              WHERE deleted_at IS NULL
uk_students_roll_live  (college_id, roll_number) WHERE deleted_at IS NULL
```

Identical predicate, and `college_id` leads both, so every plan that could use
the first can use the second. `idx_students_live` was dropped;
`idx_colleges_live`, `idx_companies_live`, `idx_trainers_live` and
`idx_batches_college_live` have no such partner and remain.

### The 10 unindexed foreign keys are deliberately still unindexed

`batches.deleted_by`, `colleges.deleted_by`, `companies.deleted_by`,
`students.deleted_by`, `trainers.deleted_by`, `enrollments.enrolled_by`,
`enrollment_requests.reviewed_by`, `bulk_uploads.uploaded_by_user_id`,
`idempotency_keys.user_id` → `users`, and `topic_progress.updated_by` →
`trainers`.

An unindexed foreign key costs a scan of the *child* when a **parent** row is
deleted or its key updated. Both halves were checked before deciding:

- **Nothing hard-deletes a user or a trainer.** The application soft-deletes;
  the only `@Modifying` `DELETE` in the codebase is `TopicProgress` by student
  and batch. The scan these indexes would avoid does not happen.
- **None of these columns is ever a query predicate** — no derived finder, no
  Specification, no `@Query` mentions any of them. They are written as an audit
  trail and read back only through the row that carries them.

Adding ten indexes that serve neither purpose is what Phase 05 warns about in
its own words: *an index that isn't used is worse than no index — it costs write
throughput and buys nothing.*

**What would change the answer:** a hard-delete or GDPR erasure path on `users`,
or a screen that filters by "deleted by" or "uploaded by". If `topic_progress`
grows large first, `topic_progress.updated_by` is the one to index before the
others — it is much the biggest child table.

### Two dead tables — prepared, not yet dropped

`batch_enrollments` and `syllabi`: 0 rows, mapped by no entity, referenced by no
Java, TypeScript, Python or SQL in the repository, and depended on by nothing —
no inbound foreign key, no view, no rule. They carry only *outbound* foreign
keys to `batches` and `students`, which is exactly their cost: every `DELETE` on
a parent scans them to enforce a constraint protecting nothing.
`batch_enrollments` is the `trainer_batches` bug in another costume — a second
table modelling a relationship `enrollments` already holds.

`DROP TABLE` was refused by the agent session's safety tooling, which is the
right default for irreversible DDL against the only copy of the data. The
verified statements are in `db/schema/2026-09-09-index-cleanup.sql` and need a
human to run them. Until then the index audit reports two remaining
redundancies, both on these tables.

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
