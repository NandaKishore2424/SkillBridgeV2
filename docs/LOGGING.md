# Logging

What a log line has to carry, what it must never carry, and which level to use.

**Status: Phase 10 Task 1 is built.** Correlation ids, the MDC fields, the two
log shapes and the redaction backstop are all in place. Tasks 2 onward — metrics
beyond what the actuator already exposes, tracing, dashboards, alerting — are
not started.

---

## 1. Two shapes, chosen by profile

| Profile | Shape | Why |
|---|---|---|
| `local`, `test` | `12:04:31.882 INFO  [9f8c-4b21] c.s.progress.ProgressService - Graded 12 students` | A person is reading it, in a terminal, right now |
| everything else | one JSON object per line | Nothing reads it with its eyes; a log aggregator queries fields |

`logback-spring.xml` holds both. The correlation id appears in each — bracketed
after the level locally, as the `traceId` field elsewhere.

> **Grepping a formatted line for a user id is how you end up unable to answer
> "what else did that request do".** That is the whole argument for JSON off the
> local profile: `traceId` is a field you can filter on, not a substring you hope
> is unique.

The JSON appender is wrapped in an async one with `discardingThreshold` at zero —
never drop, even when the queue is full. The queue is full precisely when
something is going wrong, which is precisely when the line matters most.

---

## 2. What every line carries

`CorrelationIdFilter` populates the MDC for the life of a request:

| Field | Source | Notes |
|---|---|---|
| `traceId` | inbound `X-Request-Id`, else generated | Echoed back on the response |
| `httpMethod`, `path` | the request | |
| `userId`, `collegeId` | the authenticated principal | Added by `UserContextLogFilter`; absent on anonymous requests |

Two details in there are load-bearing.

**The inbound header is validated, not trusted.** `X-Request-Id` is
client-controlled and ends up in a log file, so a value containing a newline lets
an attacker append entire fabricated entries — a convincing "authentication
successful" among them. That is log injection, and the guard is printable ASCII
only, 128 characters maximum. A rejected header means a generated id, not a
rejected request.

**The MDC is cleared in a `finally`.** Tomcat pools its threads. An MDC left
populated is inherited by whatever request lands on that thread next, which then
logs under a previous user's identity — worse than logging no identity at all,
because it looks authoritative. `CorrelationIdFilterTest` asserts the clearing
happens when the request fails as well as when it succeeds.

### Why the filters sit where they do

`CorrelationIdFilter` is a servlet filter at `HIGHEST_PRECEDENCE`, outside
Spring Security's chain, so it wraps authentication: a request rejected at the
door still produces a correlated line, and that is the request you most want to
find.

`UserContextLogFilter` is inside the security chain, after
`TokenAuthenticationFilter`, because ahead of that filter there is no principal
to read and it would add nothing at all — silently.

> Spring Boot auto-registers every `Filter` bean into the servlet chain, so a
> filter that is *also* added to the security chain is registered twice and only
> avoids running twice because `OncePerRequestFilter` suppresses the second
> invocation. `FilterRegistrationConfig` disables auto-registration for the
> security-chain filters, so each exists in one chain in the position
> `SecurityConfig` states. Without it, a single `@Order` annotation on any of
> them would move it ahead of authentication and nothing would fail — the rate
> limiter would simply go back to having no principal to key on.

---

## 3. Levels

| Level | Use for | Example |
|---|---|---|
| `ERROR` | Something is broken and a human needs to act | Rate limiter unavailable; an event dead-lettered |
| `WARN` | Unexpected but handled | Rate limit exceeded; a token refused |
| `INFO` | Significant business events | Batch created; bulk upload completed; tokens revoked for a user |
| `DEBUG` | Developer detail, off in production | Cache miss |
| `TRACE` | Firehose | SQL parameter binding |

---

## 4. Rules

**Never log a credential, a token, a password hash, or a full request body.**
`CredentialRedactingConverter` is wired into both log shapes and catches the
usual shapes — `password=`, `"token": "…"`, `Bearer eyJ…`, a bare JWT — but it is
a backstop and cannot match a secret logged under a name nobody thought of. The
discipline is the control; the filter is the net.

**Never log an expected failure at `ERROR`.** "User not found" is a 404. An
`ERROR` log that is noisy is an `ERROR` log nobody reads, and the one real error
scrolls past.

**Log identifiers, not objects.** `log.info("Graded student {}", studentId)`,
not the DTO. Objects bloat the line and leak fields nobody intended to publish —
which is how a password hash reaches a log file without anyone deciding to put
it there.

**Never log the rate limiter's key.** For a login it contains the email of
whoever is being attacked. `RateLimitingFilter` logs the tier and the path and
deliberately omits the key.

**One event, one line.** Multi-line entries break every aggregator's parsing,
which is why stack traces are a structured field rather than loose text.

---

## 5. What is not done

Phase 10 Tasks 2 to 6: metrics beyond the actuator's defaults, distributed
tracing, dashboards, SLOs and alerting, runbooks. Several of those need
somewhere to ship logs and metrics to, which is a deployment question, not
yet answered (`docs/DEPLOYMENT.md`).
