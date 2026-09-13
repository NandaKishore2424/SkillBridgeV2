# Testing strategy

> What the suite is for, what each tier costs, and which failures each one can
> actually catch. Written 2026-09-13, after four sessions in which the suite
> grew from 1 test to 169 with none of this written down.

---

## The shape, as measured

| Tier | Files | Tests | Runtime | Needs | Command |
|---|---|---|---|---|---|
| **Backend fast** — unit + architecture | 17 | 108 | **4.9 s** | nothing | `mvn test` |
| **Backend integration** — Spring + real Postgres | 20 | 64 | **~50 s** | Docker | `mvn verify` |
| **Frontend** — hooks, auth, component | 4 | 22 | **2.5 s** | nothing | `npm test` |

**194 tests, 0 skipped, under a minute end to end**, measured 2026-09-13. All of
it runs in CI.

The integration tier used to run against the live Supabase database and took
**10 m 38 s** for the same 64 tests — 11× longer, because the time was Spring
contexts booting against `ap-northeast-2`, 5–46 s each. That number measured the
link, not the code. It also produced one transport-level flake per run, needed
credentials CI does not have, and wrote rows into production data.

Budget for the fast tier is **30 s**, enforced by `scripts/check-test-budget.sh`
and wired into CI. At 4.9 s there is room; the budget exists to notice the day
somebody puts a Spring context in the fast tier, which costs seconds rather than
milliseconds.

There is deliberately **no slice tier yet** (`@WebMvcTest`, `@DataJpaTest`).
The phase document asks for ~120 such tests. Adding them is worth doing, but it
is worth doing *after* the integration tier can run on a throwaway database,
because most of what a slice test would assert here is currently asserted by an
integration test that does run — just not in CI.

---

## The rule the tiers exist to enforce

**A test must not decide for itself whether to run.**

Eighteen classes used to carry:

```
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
```

It reads as caution. What it produced, on every CI push from the day the
workflow was written, was this:

```
[WARNING] Tests run: 169, Failures: 0, Errors: 0, Skipped: 64
[INFO] BUILD SUCCESS
[INFO] Total time:  6.352 s
```

Sixty-four of 169 tests — 38% of the suite, including the tenant filter, token
revocation, idempotency and the query-count budget — never ran there. The build
was green and the number 169 was reported. Both were true, and together they
were misleading, which is worse than a red build: a red build gets fixed.

So the build owns the decision instead:

- `mvn test` runs the fast tier, always, in full. **Zero skips is the expected
  output**, and two independent things fail the build otherwise —
  `SuiteTieringTest` catches the annotation, `check-test-budget.sh` catches the
  outcome.
- `mvn verify` additionally runs the integration tier. With no database
  reachable it **fails**, which is what a missing database should produce.

A CI job that cannot reach a database is then *absent* from the run, which a
human notices, rather than *green having tested nothing*, which nobody does.

---

## How the integration tier gets its database

`mvn verify` needs a real PostgreSQL with `pgvector`, `pg_trgm`, generated
columns, partial GIN indexes and advisory locks. H2 has none of those, and a
green tick from H2 would prove less than no tick at all.

Until 2026-09-13 there was nowhere to get one, and the blocker was not
Testcontainers — it was that there was no schema to build from. Flyway had been
removed and the schema existed only inside the live Supabase project; there was
not one `CREATE TABLE` statement in the repository.

`db/schema/baseline.sql` is that schema, captured and diff-verified against live.
`PostgresContainerInitializer` starts one `pgvector/pgvector:pg17` container per
JVM and builds each run's database from the baseline plus
`db/schema/reference-data.sql` (the four `roles` rows, which are reference data
rather than fixture). It is registered through `src/test/resources/META-INF/spring.factories`,
so no test class has to know it exists.

The container is deliberately **not** `withReuse(true)`. Reuse leaves a container
holding the previous run's rows, and a test that passes because of data another
test left behind is the exact failure this tier exists to catch.

To run against the live database instead — occasionally useful for confirming a
schema change landed — set `SKILLBRIDGE_TEST_DB=live`. Do it knowing that it
writes to production data and competes with the running application for the
pooler's 15 connections.

---

## What moving to a clean database exposed

The first run of the integration tier against an empty container failed **4 of
64 tests**, in two classes. None of them was a container problem. All four were
tests that had been quietly reading whatever the live database happened to
contain.

**`TenantFilterAspectTest`** asserts its own precondition — *"needs batches in at
least two colleges to prove scoping"* — and then failed it, finding 1. Its
fixture created one college and borrowed the second from the database. Its class
comment meanwhile said, in as many words, that it creates its own second college
*"because depending on whatever happens to be in the database would make it pass
vacuously on a single-tenant instance — which is the state the original bug hid
in."* The comment described the right design. The code was one college short,
and against live nothing ever revealed the difference.

> **A test that asserts its precondition still has to establish it.** Otherwise
> the assertion is not a guard, it is a description of what the database happened
> to contain that day.

**`AuditLogKeysetPaginationTest`** took its tenant from
`SELECT min(id) FROM colleges`. On an empty database that is `NULL`, so fifteen
rows were written with a null `college_id` and the scoped seek matched none of
them. It failed with *"Expected size: 15 but was: 0"* — which reads like a broken
cursor and was really a missing tenant.

Both now seed what they need. Both are stronger for it: the tenant test names the
two colleges it created and asserts the other one is *excluded*, and the audit
test walks a college that contains nothing but its own fixture.

**The general form:** a suite that has only ever run against one populated
database cannot tell you which of its tests depend on that database. Running it
once against an empty one is the cheapest possible audit, and it found four in a
single run here.

### The flakiness that is now gone

Before the move, the first full `mvn verify` against live ran 64 tests with one
error in `SearchTextMaintenanceTest`, which passed on retry with nothing changed.
Every root cause was transport-level:

```
Caused by: java.net.SocketException: Connection reset
Caused by: org.postgresql.util.PSQLException: An I/O error occurred while sending to the backend.
```

That is gotcha 10, and the tell is that the root causes are `Connection reset`
rather than an assertion. One flake per ten-minute run is not something you can
gate a merge on: it teaches people to re-run until green, which is how a real
failure gets re-run away. Running locally removes the network from the question
entirely.

If you do run with `SKILLBRIDGE_TEST_DB=live` and see a wide red suite, check the
link before the code:

```bash
grep "Caused by:" target/failsafe-reports/*.txt | sort | uniq -c
python3 -c "import socket,time; t=time.perf_counter(); socket.create_connection(('aws-1-ap-northeast-2.pooler.supabase.com',5432),timeout=8).close(); print(f'{(time.perf_counter()-t)*1000:.0f} ms')"
```

---

## The frontend

It had **no test runner at all** until 2026-09-13 — no Vitest, no Testing
Library, no test files. Every guarantee came from `tsc` and
`scripts/check-api-drift.sh`, neither of which can tell you that concurrent 401s
log the user out.

Vitest + jsdom + Testing Library + MSW. **MSW intercepts at the network layer**,
so a test exercises the real axios instance and its interceptors. Mocking the
`api/` modules instead would skip exactly the code that has broken here.
`onUnhandledRequest: 'error'` is set, so an unstubbed endpoint names itself at
the moment it is called rather than surfacing later as a timeout.

The 22 tests are chosen by what has actually gone wrong, not by what is easy to
render:

| Test | Guards |
|---|---|
| `AuthContext.refresh.test.tsx` | concurrent 401s cause **one** refresh, and nobody gets logged out |
| `idempotency.test.ts` | the key identifies one filled-in form, not one HTTP call |
| `useDebouncedValue.test.ts` | a debounce, not a throttle — no intermediate value reaches the server |
| `StudentsList.test.tsx` | the admin search reaches the server instead of filtering the page on screen |

### The test that is worth copying

`StudentsList` has six tests. **Five of them pass with the bug reintroduced.**

The bug is the one this project already shipped: a search box filtering the array
already on screen, so a match on page 3 is invisible from page 1. It is nearly
untestable by looking at the rendered list, because a client-side filter and a
server-side one produce the same DOM for the normal case — you type, rows
disappear, the ones left match.

The sixth test does this instead: it asks the server for `priya` and has the
server answer with a student whose every visible field says `Arjun Kumar` /
`SBU001`. That is not contrived — a real server matches on the email column,
which this table does not display. A component that filters client-side drops
that row and shows an empty table.

> **When the correct and the broken implementation render the same thing, assert
> on the thing only one of them can do.** Here that is "render a row that does
> not match what I typed". No knowledge of the component's internals, and no
> client-side filter can survive it.

Confirmed by reintroducing the filter: exactly that one test went red, and the
other five stayed green.

### Coverage is a ratchet, not a target

13.4% of lines. Phase 11 asks for 70%. The threshold in `vitest.config.ts` is set
just under what is measured, so coverage cannot fall without failing the build
and each batch of tests raises the floor.

Setting 70% today would mean a red build with no route to green except deleting
the threshold, and a threshold everyone deletes is worse than no threshold. It is
also worth saying plainly: **do not read 13% as "the frontend is 13% tested", and
do not read a future 70% as "done".** These files are mostly JSX, and a test that
renders a page without asserting anything specific moves the number a long way
while proving almost nothing.

`npm run build` type-checks the test files too, because `tsconfig.app.json`
includes all of `src`. That is deliberate — it caught an unused import on the
first run, and it means a test cannot rot into something that no longer compiles
while the suite still passes.

---

## Adding a test: which tier?

```
Does it need a database, a broker, or a Spring context?
├── No  → fast tier. Plain JUnit. No annotation needed.
└── Yes → @SpringBootTest + @IntegrationTest
```

`SuiteTieringTest` enforces both directions of that: a `@SpringBootTest` without
the tag would run in the fast tier and reach for a database that is not there; a
tag on a test that needs no database quietly removes it from the fast tier,
which is the same hole wearing the opposite disguise.

---

## Two traps in the reports themselves

Both met while building the budget check, both the kind that produce a
confident wrong number rather than an error.

**Surefire does not clear reports for classes it did not run.** A `target/` left
from an earlier configuration still holds their files. When this was written the
directory contained a report reading `Skipped: 1` from the tier that no longer
runs there, five days stale. `check-test-budget.sh` refuses to total a directory
whose reports were written more than ten minutes apart, and says why.

**The `tests` attribute on `<testsuite>` is wrong for `@Nested` classes.** It
reports `0` while the console reports the real count. Four classes here use
`@Nested` — `JwtServiceTest`, `CorrelationIdFilterTest`,
`RateLimitingFilterTest`, `EnrollmentStatusTransitionTest` — and trusting that
attribute totalled **65** where Maven said **108**: a 40% undercount, silently,
in the very script whose job is to notice when tests go missing. The
`<testcase>` elements are all present and correct, so those are what get
counted.

---

## The discipline that makes a guard worth having

**Break every new assertion once, and confirm it goes red.** This is written
down because it has repeatedly paid for itself here — the tenant-filter test
passed against a demonstrably broken filter, twice, for two different reasons.

Everything added in this phase was broken before being trusted:

| Guard | Broken by | Result |
|---|---|---|
| `SuiteTieringTest` rule 1 | adding an env gate to `SortParameterTest` | red, naming the class |
| `SuiteTieringTest` rule 2 | a `@SpringBootTest` with no tag | red, naming the class |
| `SuiteTieringTest` rule 3 | tagging a test that needs no database | red, naming the class |
| `check-test-budget.sh` budget | budget set to 1 s | red |
| `check-test-budget.sh` zero-skip | a skipped `<testcase>` injected | red, naming the class |
| `check-test-budget.sh` staleness | a real five-day-old `target/` | red |
| `verify-schema-baseline.sh` | dropped index, narrowed unique, lost `NOT NULL` | red on all three |
| `AuthContext.refresh.test.tsx` | single-flight guard removed | red on 3 of 4, including "does not log the user out" |
| `idempotency.test.ts` | key minted per call instead of per form | red on 4 of 6 |
| `useDebouncedValue.test.ts` | `clearTimeout` cleanup removed (debounce → throttle) | red, an intermediate `'dat'` leaked |
| `StudentsList.test.tsx` | client-side filter reintroduced | red on 1 of 6 — the one designed for it |
| the coverage ratchet | threshold raised to 99% | red |
| `verify-schema-baseline.sh` end to end | run against a real sabotaged reference database | red, naming both changes and their direction |

Running that last one is what found a bug in the script itself. It waited on
`pg_isready`, which answers yes while the postgres entrypoint's **temporary**
init server is up — the one it shuts down before starting the real server. The
script connected in that window and died with `FATAL: the database system is
shutting down`, which reads like a crashed container. It now requires three
consecutive successful queries a second apart. Testcontainers gets this right
already, which is why the Java side never saw it.

---

## Running it

```bash
cd skillbridge-backend && ./mvnw -B clean test   # fast tier: 108, expect 0 skipped
cd .. && ./scripts/check-test-budget.sh          # budget + zero-skip
./scripts/check-api-drift.sh                     # expect 0 phantoms
cd skillbridge-frontend && npm run build && npm test   # 22, ~2.5s
```

Everything, including the integration tier. Needs Docker; needs nothing else,
and in particular no longer needs the application stopped or the database to
yourself:

```bash
cd skillbridge-backend && ./mvnw -B clean verify   # 172 tests, ~55s, 0 skipped
```

Against the live database instead, which writes to production data and competes
with the running application for the pooler's 15 connections:

```bash
for p in $(lsof -ti:8080); do kill -9 $p; done   # gotcha 13: it wins, you lose
docker start skillbridge-rabbit
cd skillbridge-backend && SKILLBRIDGE_TEST_DB=live ./mvnw -B clean verify
```

`pkill -f "spring-boot:run"` does **not** free port 8080 — it kills the Maven
wrapper and leaves the forked JVM holding the port. Check the port, not the
process list.
