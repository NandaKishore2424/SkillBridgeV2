#!/usr/bin/env bash
# What skillbridge-frontend/vercel.json declares.
#
#   deploy/vercel.test.sh
#
# Vercel cannot be run locally in a way that would prove these end to end, so
# this checks the configuration rather than the deployment. It is the same
# bargain as deploy/aws/cost-guardrails.test.sh: the structure is checkable
# without an account, the behaviour is not, and the first real deploy is the
# first real test of the behaviour.
#
# Two of these checks stand in for defects this project has already had.
#
#   * The SPA fallback. Without it, reloading on /admin/students/42 returns 404
#     from the host rather than the page the application has. That bug was
#     fixed on the backend in Phase 1 and again in the nginx config that Vercel
#     has now replaced -- three layers, same mistake available in each.
#
#   * The /api rewrite. Without it the browser calls the EC2 host directly, the
#     refresh cookie becomes cross-site, and the session dies the moment the
#     15-minute access token expires. See skillbridge-frontend/VERCEL.md.

#
# `--deploy` adds one more check: that the backend hostname has actually been
# filled in. That is deliberately NOT part of the default run. CI checks that
# the file is STRUCTURALLY right, which it is while still carrying the
# placeholder; whether it is CONFIGURED is a property of a deployment, and
# failing CI for it would leave the repository permanently red for a reason
# nobody can fix by editing code.

set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="$HERE/../skillbridge-frontend/vercel.json"
MODE=structure
[[ "${1:-}" == "--deploy" ]] && MODE=deploy

MODE="$MODE" python3 - "$CONFIG" <<'PY'
import json, os, sys, pathlib

path = pathlib.Path(sys.argv[1])
if not path.exists():
    print(f"FAIL: {path} does not exist", file=sys.stderr)
    raise SystemExit(1)

try:
    doc = json.loads(path.read_text())
except json.JSONDecodeError as e:
    print(f"FAIL: {path} is not valid JSON: {e}", file=sys.stderr)
    raise SystemExit(1)

failures = []

def check(condition, description):
    if not condition:
        failures.append(description)

rewrites = doc.get("rewrites", [])
sources = [r.get("source", "") for r in rewrites]

api = next((r for r in rewrites if r.get("source", "").startswith("/api")), None)
check(api is not None,
      "no /api rewrite: the browser would call the backend directly, the refresh "
      "cookie would be cross-site, and the session would die at the first token "
      "expiry")

if api:
    dest = api.get("destination", "")
    check(dest.startswith("https://"),
          f"the /api rewrite goes to {dest!r}, which is not https -- a page served "
          "over https cannot proxy to plaintext, and the traffic crosses the internet")
    check("/api/" in dest,
          "the /api rewrite drops the /api prefix; the controllers are mapped on "
          "/api/v1 and expect to see it")
    if os.environ.get("MODE") == "deploy":
        check("REPLACE-ME" not in dest,
              "the /api rewrite still points at the REPLACE-ME placeholder. Set the "
              "backend hostname in vercel.json, or every API call 404s")

fallback = next((r for r in rewrites if r.get("destination") == "/index.html"), None)
check(fallback is not None,
      "no SPA fallback: reloading any deep route returns 404 instead of the page")

if api and fallback:
    check(sources.index(api["source"]) < sources.index(fallback["source"]),
          "the catch-all fallback is listed BEFORE the /api rewrite. Vercel "
          "evaluates rewrites in order, so every API call would be answered with "
          "index.html -- an HTML body where the client expects JSON")

# Vite fingerprints everything under /assets, so those URLs are immutable.
# index.html names them, so a cached index.html pins a browser to the previous
# deploy's bundles, which are gone.
headers = {h.get("source"): h for h in doc.get("headers", [])}
assets = headers.get("/assets/(.*)", {})
asset_cache = " ".join(h.get("value", "") for h in assets.get("headers", []))
check("immutable" in asset_cache, "/assets is not cached immutably")

index = headers.get("/index.html", {})
index_cache = " ".join(h.get("value", "") for h in index.get("headers", []))
check("no-cache" in index_cache,
      "index.html is cacheable, which pins browsers to the previous deploy's bundles")

if failures:
    print(f"{len(failures)} problem(s) in vercel.json:")
    for f in failures:
        print(f"  - {f}")
    raise SystemExit(1)

note = "" if os.environ.get("MODE") == "deploy" else \
    "\n     (structure only. Run with --deploy to also require the hostname to be set.)"
print("OK: vercel.json proxies the API, falls back to the SPA, and caches sanely." + note)
PY
