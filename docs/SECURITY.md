# Security: what protects what, and what it does not

Written 2026-09-20, after the pass that produced the commits named below. Every
claim here points at a test or a measurement; where something is unproven, it
says so.

---

## The layers, and the test that holds each one up

| Concern | Mechanism | Held up by |
|---|---|---|
| Who is the caller | JWT access token, HS384 from a 48-byte key, 15-minute TTL; claims are authoritative, so the TTL is the revocation window for a role change | `JwtServiceAlgorithmTest`, `ClaimsBasedAuthenticationTest`, `TokenRevocationTest` |
| Staying signed in | Refresh token in an HttpOnly, SameSite=Lax cookie, rotated per use, with reuse detection over a token family and a 10-second grace for racing tabs | `RefreshTokenLifecycleTest`, `AuthContext.refresh.test.tsx` |
| Access token exposure | In browser memory only, shared between tabs over a BroadcastChannel; never in `localStorage` | `accessToken.test.tsx` (`ce0e658`) |
| Is the caller allowed | Eight named role annotations; every endpoint's roles are listed in `endpoint-roles.txt` and diffed on each build | `EndpointRolesTest`, `RoleAnnotationRulesTest` (`5cfc4a6`) |
| Can a stranger reach it | Every one of the 127 endpoints called with no credentials; 401 required except seven documented public ones | `UnauthenticatedAccessTest` (`95a22e0`) |
| One college's data | `TenantGuard` plus a Hibernate filter, replayed over the whole route table with another college's ids, before and after every call | `CrossTenantAccessTest` |
| Login attacks | Uniform failures (no account enumeration), a 15-character minimum, rate limiting at five attempts a minute on the auth tier | `AuthenticationFailureStatusTest`, `PasswordPolicyTest`, `RateLimitingFilterTest` |
| Invitations | Temporary password mailed once, expires after `app.auth.invitation-ttl` (7 days), checked at login and first login | `InvitationEmailTest` |
| Secrets in logs | The application's own issued tokens and generated passwords are taken from the response and asserted absent from the log of that request | `SecretsStayOutOfLogsTest` |
| Error responses | One `ErrorResponse` shape; a bug is a 500 with a correlation id and no exception text | `ErrorModelTest`, `ErrorResponseRulesTest` |
| Browser hardening | CSP `default-src 'none'`, HSTS a year with subdomains, `X-Content-Type-Options`, referrer policy, permissions policy; CORS from one setting, wildcard refused at startup | `CorsPolicyTest`, `CorsFilterChainTest` |
| The AI service | `X-Service-Token` on its analysis and metrics endpoints, compared in constant time, failing closed when unset; probes stay open | `test_service_auth`, measured live (`95a22e0`) |
| The AI service's database rights | Its own role: reads students, their skills and the job corpus; writes only the dedup and report tables | `AiRolePrivilegesTest` (`a395e0e`) |
| Dependencies | `npm audit` clean (production and dev); Spring Boot on the latest 3.5.x patch; Python pinned exactly | `9261765` |

---

## Row-level security: the decision

**Today.** Three tables have RLS enabled and no policies:
`industry_job_descriptions`, `processed_events`, `dead_letter_events`. The
application owns them and an owner bypasses RLS unless FORCE is set, so for the
application the flag does nothing. `RowLevelSecurityRulesTest` pins that set,
that FORCE is off, and that no policy exists.

**What that flag actually does** is bite any other role: a write fails loudly,
and *a SELECT returns zero rows, silently*. That is not theoretical here. When
the AI service got its own role, its vector search returned nothing and the
first version of the test passed anyway, because it asserted only that the
statement ran. The test now demands rows back, and the role gets explicit
policies.

**Tenant tables have no RLS, deliberately.** Scoping is enforced in the
application: `TenantGuard`, the Hibernate `collegeFilter`, and
`CrossTenantAccessTest`, which replays every by-id and write endpoint in the
route table with another college's ids and fails on anything that is not 404.
That test found a real write leak on its first run.

Adding RLS to the thirty tenant tables would mean a per-request
`SET LOCAL app.college_id` on every connection, policies on every table, and a
role that is not the owner — otherwise the policies are inert, exactly as above.
It is a genuine second gate, and it is the right answer for a system where more
than one application writes the database. Here one application does, the
application-level gate is tested over the whole route table, and the cost is a
connection-scoped variable that must be set on every checkout including the
pool's own validation queries. **Decision: not now. Revisit when a second
writer appears** — the AI service is already one, which is why it got a
least-privilege role instead.

What would change the decision: a second service writing tenant tables, a
requirement to prove isolation to an auditor, or a report tool given direct
database access.

---

## Accepted risks, stated plainly

- **Cross-site scripting remains the way to steal a session.** The access token
  is out of storage, which stops the "read it later" class of theft, but script
  running on the page can still call the API as the user. The CSP is strict for
  API responses; the SPA's own bundle is not covered by it.
- **The demo passwords are in public git history** (`d43b24f`, `2545669`).
  Removing them from the files does not remove them from history. Phase 5's seed
  script gives the demo accounts new ones; the Supabase project they belonged to
  is being deleted.
- **The Resend adapter has never delivered a real message.** No verified domain
  exists. It is tested against a mocked server only, and the demo uses Mailpit.
- **The backend still connects as the owner of every table.** The AI service no
  longer does. Splitting the backend's own rights (read-only reporting role, say)
  is the same idea applied again, not yet done.
- **Invitations carry a temporary password by email.** A one-time set-password
  link is the stronger design; recorded in START-HERE.
- **No WAF, no intrusion detection, no secret manager.** The deploy is a single
  host started for interviews (Phase 7); secrets come from a gitignored `.env`.
- **`/actuator/health` answers an unauthenticated caller**, with a bare status;
  details are `when-authorized`. It is reached over the container's own
  loopback by the Docker healthcheck, and **not from the internet**: Caddy
  returns 404 for the whole of `/actuator/*` (`deploy/Caddyfile`), so `metrics`
  and `prometheus` — which are a map of the system's internals — never leave
  the host. Read them over SSH.
- **Port 443 is open to the world while the demo host is running.** The control
  is that the host is switched off except during an interview, so the exposure
  window is the interview itself, and a nightly EventBridge rule stops it if
  nobody does. That is a real acceptance, not a mitigation; `docs/DEPLOYMENT.md`
  says what to do if it is not good enough for a given situation.
