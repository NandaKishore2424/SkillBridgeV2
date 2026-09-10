# API versioning and deprecation

Every endpoint lives under `/api/v1`. There is no `v2` and there should not be
one until something in the list below genuinely forces it.

## What can change inside `v1`

**Additive changes ship without notice.** A new endpoint, or a new optional
field on a response, cannot break a client that was not asking for it.

Adding a field to a *request* is additive only if it is optional and its absence
keeps the old behaviour. A new required field is a breaking change wearing a
disguise.

## What needs a new version

- Removing or renaming a response field
- Changing a field's type, including "it used to be nullable"
- Tightening validation on a request — yesterday's accepted payload must not
  start returning 400
- Changing the meaning of an existing value

**A field is never repurposed.** Add a new one and deprecate the old, even when
the new meaning seems close enough. A client that reads the old field will not
notice the meaning moved underneath it, and nothing in the response tells it.

Widening an *authorization* guard is additive. Narrowing one is a breaking
change for whoever had access — but ship it immediately anyway when the guard
was wrong, and treat the deprecation window as a courtesy, not a reason to leave
a hole open. `GET /api/v1/students/{id}` was narrowed on 2026-09-06 for exactly
this reason: it allowed `STUDENT`, and the tenant check passes for anyone in the
same college, so every student could read every classmate's name, email and roll
number by walking ids.

## Deprecating an endpoint

Mark it. Do not rely on a changelog nobody reads:

```java
@DeprecatedEndpoint(
        since = "2026-09-06",
        sunset = "2026-12-31",
        replacement = "/api/v1/admin/students/{id}",
        reason = "Duplicate of the admin endpoint.")
```

`DeprecationHeaderAdvice` turns that into response headers on every call:

```
Deprecation: true
Sunset: Thu, 31 Dec 2026 00:00:00 GMT
Link: </api/v1/admin/students/{id}>; rel="successor-version"
```

So a client finds out from traffic it is already making, rather than from an
outage on the sunset date.

**The window is 90 days minimum**, counted from `since`, and the clock starts
when the headers ship — not when someone decides internally. Announcing a
deprecation and removing the endpoint the same month is not a deprecation.

### Before removing

1. The sunset date has passed.
2. `scripts/check-api-drift.sh` shows the endpoint as an ORPHAN — nothing in
   this repository's frontend calls it. That check covers this repository only,
   so it says nothing about a client you do not control.
3. Access logs show no traffic. This is the one that actually matters, and it is
   the one that cannot be automated from here.

## Currently deprecated

| Endpoint | Since | Sunset | Replacement |
|---|---|---|---|
| `GET /api/v1/students/{id}` | 2026-09-06 | 2026-12-31 | `GET /api/v1/admin/students/{id}` |
| `GET /api/v1/trainers/{id}` | 2026-09-06 | 2026-12-31 | `GET /api/v1/admin/trainers/{id}` |
| `POST /api/v1/syllabus/topics/{id}/toggle-completion` | 2026-09-10 | 2026-12-31 | `PUT /api/v1/trainer/topics/{id}/progress/bulk` |

The first two are exact duplicates of the admin endpoint, and both shipped with
a role guard that allowed `STUDENT`. The guards were tightened on the day they
were deprecated rather than at sunset.

### The toggle, and what has to go with it

`toggle-completion` is not a duplicate — it is a **second source of truth**. It
flips one boolean on `syllabus_topics` meaning "done for the whole batch", while
`topic_progress` records the same thing per student. The two disagree the moment
either is used and nothing reconciles them; the boolean also records neither who
decided nor for whom.

Marking a topic done for everyone is still supported, and is now honest about
what it writes: the trainer's Grade screen selects every student and submits one
bulk grade, which leaves an attributed record each.

**At sunset, three things go, not one:**

1. the endpoint and `SyllabusService.toggleTopicCompletion`;
2. the `syllabus_topics.is_completed` column, plus the `completedTopicsCount`
   and `completedTopicsCount`-derived percentages computed from it — those are
   the batch-wide numbers the syllabus tree still shows, and they are the same
   stale source wearing a different hat;
3. `syllabusApi.toggleTopicCompletion` in the frontend, which is already
   uncalled and kept only so the deprecation is visible at the call site.

Removing (1) without (2) leaves the column with no writer and the tree still
reading it — a progress bar that can only ever say 0%.

## The contract itself

`/v3/api-docs` serves the OpenAPI document — 90 paths, 117 operations. Swagger
UI is behind `SWAGGER_ENABLED` because it is also a complete map of the attack
surface.

`scripts/check-api-drift.sh` compares what the frontend calls against what the
backend exposes and fails CI on a phantom. It is what closed the twenty that
existed before Phase 02, and it is what stops the twenty-first.
