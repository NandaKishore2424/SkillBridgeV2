# Performance budget

How many SQL statements each read path is allowed to emit, and what it actually
emits today.

Measured by `QueryEfficiencyTest`, which runs against two seeded tenants — a
small one and a larger one — and fails when a count grows between them. Re-run
it after changing any read path:

```bash
cd skillbridge-backend && DATABASE_URL=set mvn test -Dtest=QueryEfficiencyTest
```

The numbers below are the ones it printed on 2026-09-08.

---

## Why this file asserts an invariant, not a number

A budget of "at most 7 queries" gets raised to 8 the first time someone adds a
legitimate query, and from then on it guards nothing. The assertion that
survives every honest refactor and fails on exactly the regression worth
catching is:

> the same call over a larger result set must not cost more statements

That is the definition of an N+1. The budgets in the last column are a second
line of defence, deliberately generous.

---

## Current counts

Small tenant: 3 batches, 3 students, 2 modules per batch.
Large tenant: 12 batches, 12 students, 8 modules per batch.

| Endpoint | Before | Now (small / large) | Flat? | Doc target |
|---|---|---|---|---|
| `GET /trainer/batches` | 4 → 13 | **2 / 2** | ✅ | ≤ 4 ✅ |
| `GET /student/batches` | 10 → 28 | **5 / 5** | ✅ | — |
| `GET /batches/{id}/syllabus` | 6 → 18 | **3 / 3** | ✅ | ≤ 2 ⚠️ |
| `GET /admin/batches` | 4 | **4 / 4** | ✅ | ≤ 3 ⚠️ |
| `GET /admin/students` | 5 | **5 / 5** | ✅ | ≤ 3 ⚠️ |
| `GET /admin/batches/{id}` | 5 | **5** | n/a | ≤ 5 ✅ |
| `GET /student/dashboard/stats` | 2 | **2 / 2** | ✅ | ≤ 3 ✅ |

"Before" shows the two measurements where the count grew — those were real
N+1s, found by this test on its first run.

### The three N+1s that were fixed

**`GET /trainer/batches`** called `countByBatchId(batch.getId())` inside the
mapper: one statement per row, so twelve batches cost thirteen where three cost
four. Replaced with one grouped count over the page's ids.

**`GET /student/batches`** read `batch.getTrainers()` and
`batch.getCompanies()` — two lazy collections — inside the mapper. Twelve
enrolled batches cost 28 statements. Both are now loaded for the whole page and
passed into the mapper, which no longer touches a lazy association.

**`GET /batches/{id}/syllabus`** walked `submodule.getTopics()` per sub-module.
This one is worth remembering because the test *missed it at first*: the
original fixture gave both tenants the same curriculum, so the tree endpoint
looked flat while it was one query per sub-module. Making the large tenant's
curriculum genuinely larger turned it red at 6 → 18. The fix is a second bulk
query that initialises every sub-module's topics at once; it cannot be folded
into the first because both associations are `List` and Hibernate rejects two
collection fetches in one query (`MultipleBagFetchException`).

---

## The three still above the doc's target

All three are **flat**, which is the property that matters. The excess is real
per-page work, not per-row work:

- **`/batches/{id}/syllabus` = 3.** A batch existence check, the
  modules-with-sub-modules query, and the bulk topic fetch. Reaching 2 means
  dropping the existence check, which would turn a missing batch into an empty
  curriculum instead of a 404.
- **`/admin/batches` = 4.** The page, plus grouped counts for trainers,
  companies and enrollments. Reaching 3 means folding those counts into one
  query with three aggregates — worth doing, not yet done.
- **`/admin/students` = 5.** The page and its count, plus one query each for
  students, their skills and their projects. This was 2N+1 before Phase 04;
  three constant queries for three collections is close to the floor without
  projections (Phase 05 Task 2.4).

---

## What is not measured here

**Wall-clock time.** Latency belongs to Task 7, at realistic volume. Asserting
milliseconds here, against a database in another region, would produce a flaky
test about the network rather than about the code.

**Write paths.** Nothing in this file covers inserts or updates. The batch
configuration (`jdbc.batch_size: 50`, `order_inserts`, `order_updates`) is set
but unverified.

**Anything past a page.** Every count above is for page 0. Offset pagination
degrades with depth; the audit log is the one endpoint that avoids this, with
keyset pagination — see `AuditLogRepository`.
