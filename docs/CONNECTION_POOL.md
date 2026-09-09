# Connection pool

Phase 05 Task 4. Everything here was measured against the live Supabase project
on 2026-09-09, not derived from a formula. Where a number contradicts the
formula in the phase doc, the measurement is recorded alongside it.

---

## 1. The ceiling is 15, and it is not ours to choose

Supabase fronts the database with Supavisor. The backend connects to
`aws-1-ap-northeast-2.pooler.supabase.com:5432`, and **this project's pooler
allows exactly 15 server connections in total.**

That was established, not assumed:

| Configured `maximum-pool-size` | Load | Peak `hikaricp.connections.active` | Peak pending |
|---|---|---|---|
| 5 | 32 clients | 5 | 18.6 |
| 10 | 16 clients | 10 | 4.9 |
| 20 | 16 clients | 15 | 1.5 |
| **30** | **32 clients, 48 s sustained** | **15** | **26** |

The last row is the decisive one. Hikari was permitted 30 connections, had 26
requests queued and 48 seconds to grow into them, and never opened a sixteenth.
`pg_stat_activity` agreed both times it was checked: exactly 15 rows with
`application_name = 'skillbridge-backend'`.

It is not a Postgres role limit — `rolconnlimit` is `-1` for every role. The
server's own `max_connections` is 60, of which ~13 are permanently held by
Supabase's own services (Supavisor, PostgREST, `pg_cron`, `pg_net`,
`postgres_exporter`, `mgmt-api`). The 15 is Supavisor's per-project pool size.

**Anything above 15 in `maximum-pool-size` is unreachable configuration.** It
advertises capacity that cannot exist, and Hikari queues instead of growing —
so the pool looks healthy while requests wait.

## 2. Those 15 are shared, and we were oversubscribed by 40%

`skillbridge-ai-service` and `skillbridge-etl-pipeline` use the **same pooler
host, port and role** as the backend. There is one pool of 15 between them.

| Service | Configured max, before | After |
|---|---|---|
| Backend (HikariCP) | 10 | **12** |
| AI service (psycopg2 `ThreadedConnectionPool`) | 10 | **2** |
| ETL pipeline (single `psycopg2.connect`) | 1 | 1 |
| **Total** | **21** | **15** |

Twenty-one claims against fifteen. Nothing had failed yet only because the three
have never been busy at the same moment; whichever asked last would have waited
on `connection-timeout` or raised `PoolError`.

The split follows from what each one is. The backend is the only latency-
critical, user-facing claimant, so it takes 12. The AI service is explicitly
best-effort — its own comment already said "2 is plenty" while the code asked
for 10 — so it takes 2. The ETL script opens one connection and exits.

> This is the `batch_trainers` / `trainer_batches` lesson in a different costume:
> two components sharing one resource, each configured sensibly on its own terms,
> and nobody testing the crossing.

## 3. Why the pool is fixed-size

`minimum-idle` equals `maximum-pool-size`, so the pool never grows or shrinks.

**Creating a connection to the pooler measured a 1171 ms mean**
(`hikaricp.connections.creation`). That is TCP, TLS, Supavisor auth and Postgres
auth, each a round trip to ap-northeast-2 — measured TCP connect alone is
150–220 ms. Letting the pool grow on demand puts that second-plus into a user's
request. A warm fixed pool spends it once at startup instead.

The cost is that 12 of the shared 15 are held even while idle. That is the
deliberate trade: the AI service's 2 are always available to it, and the
remaining one is spare.

## 4. `connection-timeout` is 5000, not the 3000 the phase doc suggests

The doc's reasoning is right — 30 seconds of queueing is 30 seconds of a held
Tomcat thread, which turns a slow service into an unresponsive one — but 3000 ms
is unsafe **here**, because connection creation measured 1171 ms. A 3-second
budget is under three creation times: a request arriving during warm-up, or
while Hikari replaces a connection retired by `max-lifetime`, would fail rather
than wait.

5000 ms is four times the measured creation cost, and still six times tighter
than the 30 s it replaces.

**Observed working as intended.** When the database itself degraded under
sustained load (checkout time rose from 274 ms to ~2000 ms), the pool saturated
at 16 concurrent clients and requests were shed at 5 s rather than queued. That
is the design: shed load, keep threads.

## 5. Leak detection is on, and there are no leaks

`leak-detection-threshold: 20000`. It only logs; it never fails a request.

Under deliberate overload against an already-degraded database it fired 9 times.
**None were leaks.** Hikari reports a suspected leak and then, if the connection
comes back, reports that too:

```
WARN  ProxyLeakTask : Apparent connection leak detected
INFO  ProxyLeakTask : Previously reported leaked connection ... was returned
```

Nine reported, eight explicitly returned, the ninth in flight at shutdown. **A
real leak has the first line and never the second** — that is the rule for
reading these, and it is the difference between a slow query and a connection
that is never coming back.

## 6. What actually limits throughput: round trips, not the database

The database is not working hard. Under load, `pg_stat_activity` showed our
connections `idle` or `idle in transaction` and almost never `active` — they
were waiting for the application, 150 ms away, to send the next statement.

Two numbers follow from that, and they matter more than the pool size:

- **~5 connection acquisitions per HTTP request**, consistent across every run
  (`hikaricp.connections.usage` count ÷ requests). Five separate transactions,
  each paying a round trip.

  **Fixed 2026-09-09.** The cause was that the read services were not
  `@Transactional`, so every repository call opened and committed its own
  transaction. Annotating them `@Transactional(readOnly = true)` took
  `GET /admin/students` from **5.00 checkouts per request to 2.00**, and
  `/admin/trainers` from 3.00 to 2.00 — measured, and reproducible to two
  decimal places because the counts are deterministic. The remaining two are one
  for the auth filter's `findById` and one for the service transaction itself.
  `TransactionalReadRulesTest` fails the build if a service method reaches a
  repository without a transaction again.
- **Every authenticated request does a `userRepository.findById`** in
  `TokenAuthenticationFilter`, to read `isActive` — which the JWT already
  carries, along with email, role and `collegeId`. One round trip per request,
  on every endpoint including the actuator ones.

The filter's database read is a real security property, not an oversight: it is
what makes deactivating a user take effect immediately instead of at token
expiry, an hour later. Trading it for a cached or claims-only check is a
decision about that window, not a performance tweak, so it has not been made.

## 7. A caution about the measurements

Throughput figures in this document are indicative, not a benchmark. The path is
the public internet to ap-northeast-2 against a shared free-tier instance, and
identical configurations differed by 2–3× between runs — and by ~7× once
sustained load had throttled the instance. Single-request latency drifted from
~1.4 s to ~2.5 s over one session.

What is *structural*, and reproduced, is everything the sizing rests on: the
15-connection ceiling, the 1171 ms creation cost, the three-way oversubscription,
the acquisitions per request (deterministic to two decimal places, before and after). None of those are timing artifacts.

## 8. Metrics

`/actuator/metrics` and `/actuator/prometheus`, **SYSTEM_ADMIN only** —
`/actuator/**` was `permitAll()`, and `http.server.requests` carries one URI
template per route, which is the complete API map that `SWAGGER_ENABLED=false`
exists to avoid publishing. Health and info stay public for probes.

| Metric | What to watch |
|---|---|
| `hikaricp.connections.pending` | Any sustained non-zero value. It is the leading indicator — it moves well before anyone reports slowness |
| `hikaricp.connections.active` | Pinned at max means the pool is the bottleneck; pinned at 15 means the *pooler* is |
| `hikaricp.connections.acquire` | Was 4–10 ms unloaded, 230–1400 ms saturated |
| `hikaricp.connections.usage` | How long a connection is held. ~300 ms healthy here; 2000 ms meant the database was in trouble |
| `hikaricp.connections.timeout` | Requests shed at `connection-timeout`. Non-zero is load shedding actually happening |

Connections are named: `ApplicationName=skillbridge-backend` makes them
attributable in `pg_stat_activity`. Without it every socket reads as the
driver's default `PostgreSQL JDBC Driver` — which is also what the AI service
and the ETL pipeline are called, so on a shared ceiling you cannot tell whose
connections are holding it.

## 9. Server-side prepared statements are safe only in session mode

`prepareThreshold: 5` with a 256-query cache saves a parse/plan round trip on
repeated queries, which at 150 ms RTT is the cost that matters.

This works because port **5432** is Supavisor's *session* mode: one client
connection owns one server connection, so a prepared statement stays where it
was made. Port **6543** is transaction mode, where a connection is handed to a
different client between statements and named prepared statements break.

Changing that port without removing `prepareThreshold`,
`preparedStatementCacheQueries` and `preparedStatementCacheSizeMiB` is a
one-character change that fails at runtime rather than at startup. Verified on
5432: zero `prepared statement ... does not exist` errors across ~2000 requests.

## 10. No `@Transactional` method makes a network call

`StudentService.updateStudentProfile`, `addSkill` and `updateSkillProficiency`
were all `@Transactional` and all published to RabbitMQ before returning — a
connection held for the broker round trip. A comment at one of them claimed the
publish ran after commit; it did not.

`AIEventPublisher` now registers an `afterCommit` synchronization and dispatches
onto `aiEventExecutor`, which fixes three things at once: the connection is not
held for the publish, a rolled-back transaction no longer emits an event for a
change that did not happen, and the AI service's own read can no longer race the
commit.

Two guards, each confirmed to fail before being trusted:

- `ConnectionHoldingRulesTest` walks the call graph from every `@Transactional`
  method. It walks rather than checking direct calls because the real defect was
  two hops deep — `StudentService` never mentions `RabbitTemplate`. Removing the
  `AIEventPublisher` boundary makes it report the exact five-hop path.
- `AIEventPublisherDeferralTest` proves the deferral is real, which is what
  makes that boundary honest. All five assertions go red when the deferral is
  removed.
