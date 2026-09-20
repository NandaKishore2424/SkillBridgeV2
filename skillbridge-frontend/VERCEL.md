# The frontend on Vercel

`vercel.json` next to this file is the whole configuration. **Set the backend
hostname in it before the first deploy** — it ships with `REPLACE-ME.dedyn.io`.

## Why the API is proxied and not called directly

`vercel.json` rewrites `/api/*` to the EC2 backend, so the browser only ever
talks to the Vercel origin. That is not tidiness. It is what keeps the
application working.

The session is an access token in memory plus a **refresh token in an HttpOnly
cookie** — `skillbridge_refresh_token`, `SameSite=Lax`, path `/api/v1/auth`
(`AuthController`). `Lax` means the browser sends it only to the site that set
it. If the SPA on `something.vercel.app` called an API on
`skillbridge.dedyn.io` directly, that cookie would be cross-site and the
browser would not send it — so:

- the session would die the moment the 15-minute access token expired,
- a page refresh would drop the user to the sign-in screen,
- and in a demo that looks exactly like a broken application.

The alternatives are worse. `SameSite=None` makes it a third-party cookie,
which browsers are actively restricting and Safari already blocks by default.
A shared parent domain (`app.example.com` and `api.example.com`) works, but
needs a real domain.

The rewrite makes the cookie first-party again, and removes CORS from the
picture completely: no preflights, no `Access-Control-Allow-Credentials`, no
origin list to keep in step with the deploy.

It is the same property `deploy/Caddyfile` provides when the whole stack runs
on one host — the same decision, expressed twice, because the browser rule it
answers is the same either way.

> **Integronix does it the other way** — its frontend calls the API host
> directly with CORS, because its session is a Supabase bearer token and there
> is no cookie to lose. Worth knowing why the two projects differ if you are
> asked: the constraint is the cookie, not the hosting.

## Deploying

1. Import the repository into Vercel and set the root directory to
   `skillbridge-frontend`.
2. Framework preset: **Vite**. `vercel.json` states it anyway.
3. **No environment variables are needed.** `VITE_API_BASE_URL` defaults to
   `/api/v1` in `src/api/client.ts`, which is what the rewrite expects. Setting
   it to an absolute URL would bypass the proxy and reintroduce the cookie
   problem above.
4. Deploy.

## Checking it worked

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://<your-app>.vercel.app/
curl -s -o /dev/null -w '%{http_code}\n' https://<your-app>.vercel.app/admin/students/1
curl -s https://<your-app>.vercel.app/api/v1/actuator/health
```

The first two must both be **200** — the second is the SPA fallback, and a 404
there means a deep link breaks on reload. The third proves the rewrite reaches
the backend; if the EC2 host is stopped it will fail, which is the expected
answer when the host is off.

Then sign in, wait for the access token to expire, and use the app again. If
you are still signed in, the cookie survived the proxy and the whole design
works. That is the one test worth doing by hand after any change here.
