# Timeouts

Every bound in the application, and why it is the number it is.

> **A missing timeout is a resource leak with a delay fuse.** It is the least
> interesting item in Phase 07 and the one that prevents the most outages: the
> failure is never "this was slow", it is "twelve connections are held by
> requests that will never finish and the site is down".

That number — twelve — is why this file matters more here than it would
elsewhere. Supabase's pooler allows this project fifteen server connections and
the backend takes twelve of them (`docs/CONNECTION_POOL.md`). There is no slack
to absorb a few stuck operations.

---

## Database

| Setting | Value | Why |
|---|---|---|
| `connection-timeout` | 5 s | Four times the measured 1171 ms creation cost. Not the 3 s the phase suggests: that leaves under two creation times of headroom, so a request arriving during warm-up would fail rather than wait |
| `validation-timeout` | 3 s | Must stay under `connection-timeout` |
| `statement_timeout` | 30 s | A backstop against a query that will never finish, not a performance budget. `docs/PERFORMANCE_BUDGET.md` is the budget |
| `idle_in_transaction_session_timeout` | 60 s | The half that bites harder: an open transaction holds its connection *and* blocks vacuum on every row it touched |
| `max-lifetime` | 15 m | Under the pooler's own idle cut-off, so we retire connections before it does |
| `leak-detection-threshold` | 20 s | Logs only, never fails a request. The slowest single checkout observed under deliberate overload was 4.5 s |

The two Postgres timeouts are set through `connection-init-sql`, so they apply to
every connection as it is opened rather than to whichever code path remembered.

**Why 30 s and not 5.** This instance has been measured at 500–650 ms per
statement while throttled, against 150 ms when healthy. A tight statement timeout
would turn a slow afternoon into an outage, which is the failure mode the timeout
exists to prevent.

**Test profile differs deliberately.** `connection-timeout` is 20 s there.
Production wants to fail fast so a user is not left waiting; a test run wants to
survive a slow link, because the alternative is a red suite that says nothing
about the code. On 2026-09-10 a TCP connect to the pooler ranged from 300 ms to
2.6 s, at which point five seconds is barely one attempt.

---

## HTTP and messaging

| Setting | Value | Why |
|---|---|---|
| `spring.mvc.async.request-timeout` | 30 s | Nothing returns a `Callable` or `DeferredResult` today; this is the ceiling for the first one that does, so it arrives bounded rather than inheriting Tomcat's indefinite default |
| `spring.rabbitmq.connection-timeout` | 5 s | A broker that has gone away must not hold a thread — and the thread waiting is one that has just committed and is trying to publish the event for it |
| `spring.rabbitmq.template.reply-timeout` | 10 s | |

---

## Executors

Not timeouts, but the same question: what bounds this.

| Pool | Size | Queue | Rejection | Shutdown |
|---|---|---|---|---|
| `bulkUploadExecutor` | 2–4 | 50 | caller-runs | drain, 60 s |
| `aiEventExecutor` | 2–4 | 500 | abort | drain, 30 s |

**Both are sized by what the database can absorb, not by a formula.** Little's Law
puts CSV row processing near 58 threads; four is a third of the connection pool,
which is as much as a background job may take from a pool that also serves every
page on the site. Sizing from the formula and ignoring the downstream constraint
is the mistake Phase 07 § 2.1 names, and here the constraint is unusually hard.

**The rejection policies differ on purpose.** Caller-runs on the upload pool is
back-pressure: the submitting thread does the work, so nothing is dropped or
refused. Abort on the AI event pool is correct instead, because caller-runs would
hand the publish back to the committing thread — precisely the thread whose
connection the executor exists to release.

> Phase 07 says a rejected task is swallowed and the upload silently never
> happens. Measured, it is not: submission runs on the caller's thread, so the
> rejection is thrown there, into the controller, which answers 500.
> `ExecutorSaturationTest` holds both behaviours side by side. Loud rather than
> silent, and still the wrong answer — an administrator's upload refused because
> somebody else's was in progress.

---

## Scheduled jobs

`@Scheduled` fires on every instance. `SingleRunGuard` takes a Postgres advisory
lock so exactly one runs it, using `pg_try_advisory_xact_lock`, which returns
false rather than waiting — an instance that waits for the lock runs the job
twice in sequence, which for a nightly reconciliation is the same bug an hour
later.

The lock is transaction-scoped, so there is no release to forget and no path
(exception, timeout, a killed instance) that leaves it held. That is also why it
is an advisory lock rather than a row in a table: a lock row needs a lease, a
clock and an expiry sweeper to survive an instance dying mid-job.

---

## What is not bounded yet

- **`@Transactional(timeout = n)`** on long-running reads. `statement_timeout`
  covers the individual statement; a transaction that runs many is bounded only
  by `idle_in_transaction_session_timeout` if it stalls between them.
- **`CompletableFuture.orTimeout`** — nothing here builds a future chain yet.
- **The AI service and SMTP.** Phase 07 § 6 gives values for both; neither is
  called from this application yet.
