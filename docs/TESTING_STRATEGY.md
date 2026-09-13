# Testing strategy

> What the suite is for, what each tier costs, and which failures each one can
> actually catch. Written 2026-09-13, after four sessions in which the suite
> grew from 1 test to 169 with none of this written down.

---

## The shape, as measured

| Tier | Classes | Tests | Runtime | Needs | Command |
|---|---|---|---|---|---|
| **Fast** — unit + architecture | 17 | 108 | **4.9 s** | nothing | `mvn test` |
| **Integration** — Spring + real Postgres | 20 | 64 | **~10 min** | live database, broker | `mvn verify` |

Both measured on 2026-09-13: `mvn verify` end to end was **10 m 38 s**, of which
the fast tier is five seconds. The integration tier's time is dominated by
Spring context starts against a database in `ap-northeast-2` — a single
`@SpringBootTest` costs 5–46 s to boot — so **that number measures the link, not
the code**, and it is not worth optimising until the tier runs locally.

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

## Why the integration tier is not in CI yet

`mvn verify` needs a real PostgreSQL with `pgvector`, `pg_trgm`, generated
columns, partial GIN indexes and advisory locks. H2 has none of those, and a
green tick from H2 would prove less than no tick at all.

Until 2026-09-13 there was nowhere to get one: Flyway had been removed, and the
schema existed only inside the live Supabase project — there was not one
`CREATE TABLE` statement in the repository. `db/schema/baseline.sql` closes that,
and Phase 11 Task 4 moves the tier onto Testcontainers seeded from it.

Pointing CI at the live database instead is not an option and should not be
attempted: the pooler allows this whole project 15 connections, the running
application already holds 12 of them, and every push would write test rows into
production data.

---

## The integration tier is not reliably green, and that is a property of where it runs

The first full `mvn verify` after the split ran **64 integration tests, 0
skipped, 1 error** — `SearchTextMaintenanceTest.findableByEmail`. The same class
passed on retry with nothing changed.

It was not the code. Every root cause in the stack was transport-level:

```
Caused by: java.net.SocketException: Connection reset
Caused by: org.postgresql.util.PSQLException: An I/O error occurred while sending to the backend.
Caused by: java.sql.SQLException: Connection is closed
```

That is gotcha 10, and the tell is that the root causes are `Connection reset`
and `I/O error` rather than an assertion. Before believing a red integration
run, check:

```bash
grep "Caused by:" target/failsafe-reports/*.txt | sort | uniq -c
python3 -c "import socket,time; t=time.perf_counter(); socket.create_connection(('aws-1-ap-northeast-2.pooler.supabase.com',5432),timeout=8).close(); print(f'{(time.perf_counter()-t)*1000:.0f} ms')"
```

**One transport error per ten-minute run is not a suite you can gate a merge
on.** This is the strongest argument for Task 4: a tier that is flaky for
reasons unrelated to the code teaches people to re-run it until it is green,
which is how a real failure gets re-run away. Moving it onto a local container
removes the network from the equation entirely.

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

---

## Running it

```bash
cd skillbridge-backend && ./mvnw -B clean test   # fast tier: 108, expect 0 skipped
./scripts/check-test-budget.sh                   # budget + zero-skip
./scripts/check-api-drift.sh                     # expect 0 phantoms
cd skillbridge-frontend && npm run build
```

The integration tier, when you have the database to yourself:

```bash
for p in $(lsof -ti:8080); do kill -9 $p; done   # gotcha 13: it wins, you lose
docker start skillbridge-rabbit
cd skillbridge-backend && ./mvnw -B verify
```

`pkill -f "spring-boot:run"` does **not** free port 8080 — it kills the Maven
wrapper and leaves the forked JVM holding the port. Check the port, not the
process list.
