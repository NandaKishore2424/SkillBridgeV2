#!/usr/bin/env bash
# What scripts/db/pg.sh promises, checked against a real throwaway PostgreSQL:
#
#   - it connects with a password full of characters a URL has to encode;
#   - that password is never in any process's argv while it runs;
#   - with no URL, a malformed URL or an unreachable one, it refuses in words
#     and runs nothing.
#
# Needs docker. Uses port 55439 and removes its container on exit.

set -euo pipefail
cd "$(dirname "$0")/../.."

PORT=55439
NAME="skillbridge-pgtest-$$"
# Every character a hand-built URL gets wrong, plus a canary to grep for.
PASSWORD='canary-7Qx@p:a/ss%w\rd'
ENCODED='canary-7Qx%40p%3Aa%2Fss%25w%5Crd'
URL="postgresql://postgres:${ENCODED}@localhost:${PORT}/postgres"

pass=0; fail=0
ok()  { echo "  ok    $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL  $1" >&2; fail=$((fail + 1)); }

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "Starting a throwaway PostgreSQL on :$PORT"
docker run -d --name "$NAME" -e POSTGRES_PASSWORD="$PASSWORD" -p "$PORT:5432" \
    pgvector/pgvector:pg17 >/dev/null
for _ in $(seq 1 60); do
    docker exec "$NAME" pg_isready -U postgres -h localhost >/dev/null 2>&1 && break
    sleep 0.5
done
sleep 1   # the entrypoint restarts the server once after initdb

echo "Connecting with an encoded password"
got=$(SKILLBRIDGE_DATABASE_URL="$URL" scripts/db/pg.sh psql -tAc 'select 42' </dev/null || true)
[[ "$got" == 42 ]] && ok "connects, and decodes %40 %3A %2F %25 %5C" || bad "connect: got '$got'"

echo "Watching ps while a query runs"
samples=$(mktemp)
SKILLBRIDGE_DATABASE_URL="$URL" scripts/db/pg.sh psql -tAc 'select pg_sleep(3)' </dev/null >/dev/null &
query=$!
seen_psql=false
while kill -0 "$query" 2>/dev/null; do
    ps -eo args > "$samples.now"
    grep -q "localhost:${PORT}/postgres" "$samples.now" && seen_psql=true
    cat "$samples.now" >> "$samples"
    sleep 0.1
done
wait "$query" || true
# The canary, not the whole password: the test's own argv is not in play (it
# holds no password), so any hit is pg.sh's doing.
if grep -q 'canary-7Qx' "$samples"; then
    bad "the password appeared in a process argv"
    grep 'canary-7Qx' "$samples" | head -3 | sed 's/canary-7Qx[^ ]*/<PASSWORD>/g' >&2
else
    ok "the password never appeared in any argv"
fi
$seen_psql && ok "…and the sampling did see the running psql" \
           || bad "the sampling never saw psql running, so the check above proves nothing"
rm -f "$samples" "$samples.now"

echo "Refusals"
out=$(env -u SKILLBRIDGE_DATABASE_URL setsid scripts/db/pg.sh psql -c 'select 1' </dev/null 2>&1) \
    && bad "no URL and no terminal: it ran" \
    || { [[ "$out" == *"No database URL"* ]] && ok "no URL, no terminal: refuses" || bad "no URL: said '$out'"; }

out=$(SKILLBRIDGE_DATABASE_URL="mysql://x@y/z" scripts/db/pg.sh psql -c 'select 1' </dev/null 2>&1) \
    && bad "a non-postgres URL: it ran" \
    || { [[ "$out" == *"not a postgresql:// URL"* ]] && ok "not a postgresql URL: refuses" || bad "bad URL: said '$out'"; }

out=$(SKILLBRIDGE_DATABASE_URL="postgresql://postgres:x@localhost:1/postgres" \
      scripts/db/pg.sh psql -c 'select 1' </dev/null 2>&1) \
    && bad "an unreachable database: it reported success" \
    || ok "unreachable database: fails"

out=$(scripts/db/pg.sh --app-env /nonexistent/app.env psql -c 'select 1' </dev/null 2>&1) \
    && bad "a missing app.env: it ran" \
    || { [[ "$out" == *"Cannot read"* ]] && ok "missing app.env: refuses" || bad "missing app.env: said '$out'"; }

echo
echo "$pass passed, $fail failed"
[[ "$fail" == 0 ]]
