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
| `GET /admin/batches` | 4 | **2 / 2** | ✅ | ≤ 3 ✅ |
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

### `/admin/batches`: 4 → 2

Three grouped count queries — trainers, companies, enrollments — became one
statement with three correlated subqueries. Each of the three was already
bounded, one per page rather than one per row, so this is not an N+1 fix. It is
a round-trip fix: against a database in another region the cost of a read is
dominated by how many times you cross the network, not by what you do when you
get there.

Correlated subqueries rather than three left-join-group-bys, because joining
two collections in one query multiplies rows: a batch with 3 trainers and 2
companies would produce 6, and every count would be wrong in a way that looks
plausible. Verified by assigning three trainers to a batch with one company and
confirming the response says 3 and 1, not 3 and 3.

---

## The two still above the doc's target

Both are **flat**, which is the property that matters. The excess is real
per-page work, not per-row work:

- **`/batches/{id}/syllabus` = 3** vs 2. A batch existence check, the
  modules-with-sub-modules query, and the bulk topic fetch. Reaching 2 means
  dropping the existence check, which would turn a missing batch into an empty
  curriculum instead of a 404. Not worth it.
- **`/admin/students` = 5** vs 3. The page and its count, plus one query each
  for students, their skills and their projects. This was 2N+1 before Phase 04;
  three constant queries for three collections is close to the floor.

---

## On projections

Phase 05 Task 2.4 says list endpoints should return projections rather than
entities. That is right in general — load an entity when you mean to modify it,
project otherwise — and it is **not** applied to the two big admin lists here,
deliberately:

- **`/admin/batches`** is driven by JPA Specifications, which is what gives it
  `?search=`, `?status=` and `?sort=`. Interface projections do not compose with
  a `SIZE()` aggregate through the fluent-query API, so converting would mean
  choosing between the counts and the filtering. The endpoint already costs 2
  statements; the remaining prize is heap, and it is not worth losing a shipped
  capability for.
- **`/admin/students`** carries two collections per row (skills, projects). A
  flat projection cannot express them.

Where projections would be a clean win is the read-only reference lists —
`/students/skills`, `/colleges/active`. Those are small tables today, so the
win is theoretical. Left undone on purpose, and recorded here rather than
quietly skipped.

---

## What @BatchSize covers

Every collection association carries `@BatchSize(size = 25)` — enforced by an
ArchUnit rule, so a new one cannot be added without it. It is the safety net,
not the fix: where a read path needs a collection it should fetch-join it or
bulk-load it by the page's ids, both of which this codebase does. What the
annotation buys is what happens when somebody forgets — an accidental N+1
becomes an N/25+1. Bounded rather than free.

---

## What is not measured here

**Wall-clock time.** Latency belongs to Task 7, at realistic volume. Asserting
milliseconds here, against a database in another region, would produce a flaky
test about the network rather than about the code.

**Write paths.** Nothing in this file covers inserts or updates. The batch
configuration (`jdbc.batch_size: 50`, `order_inserts`, `order_updates`) is set
but unverified.

**In-memory pagination is checked separately.** `InMemoryPaginationDetector`
fails the test if Hibernate logs `HHH90003004`, which it does when a paged query
fetch-joins a collection: it reads every matching row and slices the list in the
heap. The query *count* does not catch this — verified by adding a collection
fetch to the Specification behind `/admin/batches`, where the count stayed flat
at 5 because it is still one statement, just a ruinous one.

**Anything past a page.** Every count above is for page 0. Offset pagination
degrades with depth; the audit log is the one endpoint that avoids this, with
keyset pagination — see `AuditLogRepository`.
