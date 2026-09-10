# Caching strategy

What this application caches, what it deliberately does not, and the measurement
behind each decision.

**Status: two entries built, and the third withdrawn on evidence.** Active
colleges and the curriculum tree per batch are cached at L1 and measured below.
Dashboard stats was in this table and is not any more — see § 5, which is the
most useful paragraph in this file. There is no Redis dependency and § 7 says
why.

Sections 1–6 are Phase 06 Task 1 — the decision — and were written before any of
it was built, because half of what the phase plan proposed caching turned out to
have no reader at all.

---

## 1. How these numbers were taken

Two instruments, measured 2026-09-10:

**Statement counts** come from Hibernate's own statement counter, via
`QueryCountAssertion`, run against a seeded throwaway tenant (`TenantFixture`,
one batch, three students, two modules). This counts what reaches the database,
including the entity loads a lazy association triggers — which
`getQueryExecutionCount` misses.

**End-to-end latency** is `curl -w %{time_total}`, three runs each, against the
running application and the live database.

The probe that produced the statement counts was written, run and deleted in the
same session. It asserted nothing, so keeping it would have been keeping a test
that cannot fail. The numbers below are its output; re-derive them the same way
rather than trusting this table if a decision turns on one.

> **Single-run numbers on a shared free-tier database are not a benchmark.**
> Gotcha 8 in `further-plans/START-HERE.md`: identical configurations have
> differed by 2–3× between runs here. What survives is the *ordering* and the
> *ratio to statement count*, and that is all this file leans on.

---

## 2. The number that decides everything

| Read path | Statements | Latency (3 runs) |
|---|---|---|
| `GET /colleges/active` | 1 | 211 / 154 / 154 ms |
| `GET /students/skills` | 2 | 449 / 507 / 455 ms |
| `GET /student/dashboard/stats` | 2 | 539 / 452 / 459 ms |
| `GET /auth/me` | 3 | 670 / 645 / 662 ms |

TCP connect to the pooler, same session: **268 / 142 / 154 ms**.

Latency tracks statement count at roughly 150–220 ms each, and that is the same
number as one network round trip to `ap-northeast-2`. **The cost of these reads
is not query complexity. It is distance.** Every one of them is already a
bounded, flat, indexed query — Phase 05 saw to that — and still takes half a
second, because it crosses an ocean two or three times.

That cuts both ways, and it is the whole argument of this file:

- A cache **hit** here saves 150 ms per statement, not the 2–5 ms a co-located
  database would give back. The value of a hit is unusually high.
- A cache **miss** costs the same 150 ms it always did, plus the lookup. So the
  hit *rate* is what matters, and a key nobody asks for twice is worse than no
  cache at all.

Which is why the table in § 4 is shorter than the one Phase 06 proposed. The
question is never "is this query slow" — they all are, for the same reason. It is
"is this key read again before it goes stale".

---

## 3. What changed under the plan

Phase 06 § 1.2 listed nine candidates. Four of them no longer describe this
codebase, and three of those four were the *highest-traffic* rows in the table.
The plan is not wrong; it was written before Phase 05.

| Phase 06 row | Its stated reason | What is true now |
|---|---|---|
| **Role lookups**, L1, 1h | "Four rows. Read on **every authenticated request**." | Not read on the request path at all. Since 2026-09-09 `TokenAuthenticationFilter` builds the principal from the token's claims. The remaining `RoleRepository` readers are user-creation paths — `StudentService`, `TrainerService`, `CollegeAdminService`, `BulkUploadJobService` — each reading once per call, and the bulk-upload one is hoisted out of its row loop. **Dropped.** |
| **User + roles by id**, L2, 5m | "Per-user, hit on every request" | Also not on the request path, for the same reason. Nor is it on the refresh path, which is where you would next look: `AuthService.refreshToken` reaches the user through `storedToken.getUser()`, an association of a row it must read anyway, so a by-id cache would never be consulted. The one surviving `findById(caller.getId())` is in `describeCurrentUser`. **Dropped.** |
| **Skill catalogue**, L1, 1h | "~200 rows, changes monthly, read on every profile page" | 44 rows, not ~200. And `GET /students/skills` is in the API-drift script's orphan list: **no frontend code calls it**, on a profile page or anywhere else. **Deferred** — see § 6. |
| **Skill-gap report**, L2, 1h | Phase 12 | Phase 12 is untouched. **Deferred**, correctly. |

`GET /auth/me` deserves its own note, because it is the trap this exercise exists
to avoid. Its javadoc calls it "the most frequently hit endpoint in any SPA",
it costs 3 statements and ~660 ms, and it is the most tempting thing in the
codebase to cache. **Nothing calls it.** `getCurrentUser` exists in
`src/api/auth.ts` and no component imports it; `AuthContext` rehydrates from
`localStorage` and the token's own claims. The API-drift script does not flag it,
because that script counts the wrapper as a call site — it compares routes, not
reachability.

> Three times now in this project a thing's stated hotness has not been its
> actual usage: the grading grid nothing could render, the flat progress endpoint
> nothing called, and this. Before caching an endpoint because it is hot, grep
> for a caller.

---

## 4. What to cache

Every entry names its invalidation trigger. An entry that cannot name one does
not belong in this table.

| Data | Tier | TTL | Invalidation trigger | Measured justification |
|---|---|---|---|---|
| **Active colleges** — `GET /colleges/active` ✅ built | L1 | 30 m | Any write to `colleges`: create, status change, soft delete | 1 statement, 154 ms, **1 row**, and the only unauthenticated list on the platform. Read once per registration-page load by anyone at all, which also makes it the one key an anonymous flood would land on. Highest value per unit of risk in the table: global data, no tenant in the key, and a single row to hold. |
| **Curriculum tree per batch** — `GET /batches/{id}/syllabus` ✅ built | L1, keyed by batch | 15 m | Any write under `SyllabusService` for that batch — module, sub-module or topic create/update/delete/reorder | 3 statements, flat regardless of tree size (`QueryEfficiencyTest`, `docs/PERFORMANCE_BUDGET.md`), ~450 ms. Read by every student and every trainer who opens the batch; written only when a trainer edits the syllabus. The read/write ratio is the best in the application. |

Two entries. That is the honest output of measuring nine, and both share the
property § 5 ends on: **their key is shared between users.**

### Measured after building the first one

`GET /colleges/active`, against the running application and the live database:

| | |
|---|---|
| Cold key, warm JVM | 527 / 330 ms |
| Warm key | 3.8 / 3.0 / 4.1 ms |
| First request after startup | 731 ms — connection creation, not the query |

Roughly **100×**, and it is the round trip that goes away rather than any query
work. `cache.gets` on the actuator carries `hit` and `miss` tags per cache, so
the hit rate is a number rather than an argument — the property § 7 insists on.

> Cold-key timings drift by 1.6× between two runs a minute apart here. Gotcha 8:
> this is a shared free-tier instance. The ratio survives that; the absolute
> numbers do not.

### The curriculum tree, and the trap in it

A warm read of a batch's curriculum costs **1 statement instead of 3**: the two
tree queries are gone and the tenant check remains. That last part is the whole
design.

Measured on the running application, all three in the same minute so they are
comparable to each other:

| Read | Statements | Latency |
|---|---|---|
| `/colleges/active`, cached | 0 | 2.9–3.9 ms |
| `/batches/1/syllabus`, cached | 1 | 523–864 ms |
| `/auth/me`, uncached | 3 | 1215–1975 ms |

Roughly 500–650 ms per statement, so the curriculum read drops from about two
seconds to about seven hundred milliseconds and **everything left is the
authorisation read**. Cache the check too and it would join the first row.

> Those per-statement numbers are three times this morning's 150–220 ms, on the
> same code and the same database, four hours apart. Gotcha 8 again, and a useful
> demonstration of it: the *ratios* between the three rows are the finding, the
> milliseconds are weather.

`SyllabusService.getCurriculumByBatchId` called `requireBatch` — the tenant
check — and then assembled the tree, in one method. **Annotating that method
`@Cacheable` caches the check away.** On a hit the body never runs, so
`requireBatch` never runs, and a trainer from another college asking for the same
batch id is handed the curriculum with a 200. Nothing about the annotation looks
wrong: batch ids are globally unique, so the key genuinely cannot collide. The
leak is the skipped authorisation, and it is invisible at the call site.

So the check stays in the caller and only the assembly is cached, in
`CurriculumReader`. It has to be a *separate bean*, not a private method —
Spring's caching is proxy-based, and a call from one method of a class to another
never leaves the object, so `@Cacheable` on a self-invoked method is silently
inert.

`CurriculumCacheTest.aWarmCacheStillRefusesAnotherCollege` is the test for this,
and it was confirmed to fail against the obvious implementation: with
`@Cacheable` on `getCurriculumByBatchId`, the second college's trainer gets the
tree instead of a 404.

> **A cache hit is a code path that skips your method body.** Anything that body
> did — authorisation, tenant filtering, an audit record, a rate-limit decrement —
> stops happening on every hit. Ask what else the method was doing before caching
> it, not just what it returned.

Eviction is `allEntries` rather than per batch, on all eleven writes. Most of
those methods are addressed by module, sub-module or topic id and never see a
batch id, so a targeted eviction would mean an extra read on every write and a
fresh way to be subtly wrong. Curricula are authored by hand: writes are rare,
over-evicting costs the other batches one ~450 ms read each, and under-evicting
is a student reading a syllabus that is fifteen minutes out of date.

---

## 5. What not to cache, and why

The list Phase 06 § 1.3 gives is right and is not repeated here. These are the
entries that were *proposed* for caching and did not survive.

| Data | Why not |
|---|---|
| **Progress summary per student** | The phase proposed L2/2m because it is "already denormalised". It is — and it is rewritten by `recomputeSummary` on **every grading action**, once per affected student. During the only time it is read heavily (a trainer working through a class) it is also being written continuously. Phase 06's own rule: anything written more often than read costs more to invalidate than the hit saves. |
| **Batch summary per college** | `GET /admin/batches` is 2 statements and flat, and it is paginated with search, status and sort filters. That is the cardinality explosion § 1.3 warns about: `(college, status, search, page, size, sort)` is thousands of keys each read about once. |
| **User + roles by id**, **role lookups**, **`/auth/me`** | No reader on any hot path. See § 3. |
| **Skill catalogue** | No reader at all. See § 3 and § 6. |

### Dashboard stats: withdrawn after it was decided

This entry was in § 4 — L1, keyed by user, one minute — and was removed before it
was built. Two things came out of tracing it that the first pass had not:

**Its invalidation trigger was not "TTL only".** Both numbers move when something
else writes: a student's counts change on enrolment, on application and on every
grading action, and grading happens in `ProgressService`, which knows a
*student* id where this cache would be keyed by *user* id. Evicting properly
means plumbing across three services; evicting `allEntries` on every grading
action means the cache is empty for the whole of a trainer's grading session,
which is the only time it is under load. Task 1's own rule applies: an entry that
cannot name a workable trigger does not belong in the table.

**The client already caches it, for five times as long.** This frontend
configures React Query with `staleTime: 5 minutes` and `refetchOnWindowFocus:
false`. A repeat of the same query by the same user inside five minutes never
reaches the server. A server-side per-user cache with a one-minute TTL is
therefore reachable only by a second tab, a second device, or a hard reload
inside sixty seconds of the last one — it is nearly unreachable by construction,
and it would have sat there at a hit rate nobody looked at.

> **A server cache earns its place when its key is shared between users.** Active
> colleges is the same list for everybody; a batch's curriculum is the same tree
> for every student and trainer on it, so one person's read warms it for the next.
> That is something a server can do and a browser cannot. A key that only one
> user can ever hit is the client's job, and the client is usually already doing
> it — check `staleTime` before adding a TTL behind it.


---

## 6. Deferred, with the trigger that revives them

| Entry | Revive when |
|---|---|
| Skill catalogue | A screen calls `GET /students/skills`. It is a good L1 candidate the moment it has a reader: 44 global rows, no tenant in the key, changes monthly. Cache the unpaged catalogue behind the paging rather than keying on `(page, size)`, so the key space stays at one. |
| `/auth/me` | A component calls `getCurrentUser`. At 3 statements it would be worth an L1 entry keyed by user, invalidated on any profile or role write. |
| Skill-gap report | Phase 12 exists. |

---

## 7. One tier, not two — for now

Phase 06 § 1.1 specifies Caffeine at L1 and Redis at L2. **This decision is L1
only**, and the reason is not effort.

**L2's distinguishing feature is shared invalidation across instances, and there
is more than one instance nowhere.** The application is not deployed (open
question #3 in `HANDOVER.md`), and it runs as a single JVM. At one instance, L1
*is* the shared cache: evicting in-process evicts for everybody. Redis would add
a network hop, a dependency, a failure mode and a circuit breaker (Task 6) to buy
coordination between replicas that do not exist.

The three entries in § 4 are also small enough that heap is not the constraint: 1
college row, a curriculum tree per batch (2 batches), and a stats object per user
(25 users). `maximumSize` bounds it regardless.

**Revisit when a second instance exists.** That is the trigger, and it is
specific: the moment a deploy runs more than one replica, § 4's per-batch and
per-user entries need either Redis or a broadcast invalidation channel, because
a trainer editing a syllabus on instance 1 must not leave instance 2 serving the
old tree for fifteen minutes. Until then, naming the bound is the whole of the
work: **cross-instance staleness is bounded by the TTL, and at one instance the
bound is zero.**

Three things carry over from Phase 06 unchanged when that day comes, and should
be built with L1 rather than retrofitted:

1. **A tenant-scoped key generator.** § 3.3 of the phase is the most important
   paragraph in it. A cache key without a college id is a cross-tenant leak, and
   it is one missing token in a SpEL string. Two of the three entries above are
   tenant- or user-scoped.
2. **The test that enforces it** — every `@Cacheable` on tenant data uses the
   tenant key generator or names `collegeId` in its key. Nothing else catches
   the omission.
3. **`recordStats()`**, so the hit rate is observable. § 2 of this file argues
   that hit rate is the only thing that makes these entries worth having; a cache
   whose hit rate is unmeasured is a claim, not a result.

---

## 8. What would change these answers

- **A second instance.** § 7.
- **A co-located database.** The entire argument of § 2 is that a statement costs
  a 150 ms round trip. Move the database into the same region and every entry
  here needs re-deriving, because the value of a hit drops by an order of
  magnitude while the invalidation cost stays the same.
- **Real traffic.** Every read/write ratio above is reasoned from call sites, not
  observed. `http.server.requests` is exposed on the actuator and is
  SYSTEM_ADMIN-only since 2026-09-09; once there is traffic worth counting, the
  per-endpoint call counts there settle §§ 4–6 by measurement rather than by
  argument.
- **A skills screen, or a component that calls `/auth/me`.** § 6.
