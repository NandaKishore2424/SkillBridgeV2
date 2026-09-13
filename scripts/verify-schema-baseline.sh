#!/usr/bin/env bash
#
# Proves that db/schema/baseline.sql still reproduces a reference database.
#
#   ./scripts/verify-schema-baseline.sh "postgresql://user:pass@host:5432/postgres"
#
# It applies the baseline to a throwaway PostgreSQL 17 container, fingerprints
# both that container and the reference database with the identical query in
# scripts/schema-fingerprint.sql, and diffs the two. Exit 0 means the file in
# git is the schema that is actually deployed.
#
# WHY THIS EXISTS
#
# Flyway was removed on 2026-09-06, so nothing replays and nothing checks. The
# schema lives in the live database and a copy lives in this repository, and
# there is no mechanism that keeps them honest except this script. Run it after
# any schema change, and re-capture the baseline when it disagrees.
#
# It is READ-ONLY against the reference database. It creates nothing there,
# drops nothing there, and runs a single catalogue SELECT.
#
# psql runs inside the container, so no local PostgreSQL client is needed --
# only Docker.

set -euo pipefail

REF_URL="${1:-${DATABASE_URL:-}}"
if [[ -z "$REF_URL" ]]; then
    echo "usage: $0 <reference-database-url>" >&2
    echo "       (or set DATABASE_URL)" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="$REPO_ROOT/db/schema/baseline.sql"
FINGERPRINT="$REPO_ROOT/scripts/schema-fingerprint.sql"
IMAGE="${SCHEMA_VERIFY_IMAGE:-public.ecr.aws/supabase/postgres:17.6.1.014}"
CONTAINER="skillbridge-schema-verify-$$"
WORK="$(mktemp -d)"

cleanup() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

[[ -f "$BASELINE" ]]    || { echo "missing $BASELINE" >&2; exit 2; }
[[ -f "$FINGERPRINT" ]] || { echo "missing $FINGERPRINT" >&2; exit 2; }

echo "==> starting throwaway PostgreSQL ($IMAGE)"
docker run -d --name "$CONTAINER" \
    -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=verify \
    "$IMAGE" >/dev/null

# Readiness is not `pg_isready`, and assuming it was cost a confusing failure.
#
# The official postgres entrypoint starts a TEMPORARY server on a unix socket to
# run initdb and the init scripts, shuts it down, and only then starts the real
# one. `pg_isready` answers yes during that temporary phase, so a loop that
# stops at the first yes connects to a server that is about to stop, and the
# next command dies with:
#
#     FATAL:  the database system is shutting down
#
# which reads like the container crashed. Requiring several consecutive
# successes spaced a second apart steps over the shutdown window: the temporary
# server does not stay up that long.
ready=0
for _ in $(seq 1 90); do
    if docker exec "$CONTAINER" psql -U postgres -tAc 'SELECT 1' >/dev/null 2>&1; then
        ready=$(( ready + 1 ))
        (( ready >= 3 )) && break
    else
        ready=0
    fi
    sleep 1
done
if (( ready < 3 )); then
    echo "container never became ready" >&2
    docker logs --tail 20 "$CONTAINER" >&2 || true
    exit 1
fi

echo "==> applying db/schema/baseline.sql"
docker exec "$CONTAINER" psql -U postgres -q -c "CREATE DATABASE baseline;" >/dev/null
docker cp "$BASELINE"    "$CONTAINER:/tmp/baseline.sql"    >/dev/null
docker cp "$FINGERPRINT" "$CONTAINER:/tmp/fingerprint.sql" >/dev/null

# ON_ERROR_STOP matters: without it psql reports success having skipped every
# statement after the first failure, and the diff below would then be a diff
# against a half-built schema rather than a build failure.
if ! docker exec "$CONTAINER" psql -U postgres -d baseline -q \
        -v ON_ERROR_STOP=1 -f /tmp/baseline.sql > "$WORK/apply.log" 2>&1; then
    echo "FAIL: baseline.sql did not apply cleanly" >&2
    tail -20 "$WORK/apply.log" >&2
    exit 1
fi

echo "==> fingerprinting both databases"
docker exec "$CONTAINER" psql -U postgres -d baseline -tA \
    -f /tmp/fingerprint.sql > "$WORK/from-baseline.txt"

# The reference connection is made from inside the container, so the only
# requirement on the host is Docker. The URL is passed via the environment
# rather than on the command line so it does not appear in the host process
# list or in `docker inspect`.
docker exec -e REF_URL="$REF_URL" "$CONTAINER" \
    sh -c 'psql "$REF_URL" -tA -f /tmp/fingerprint.sql' > "$WORK/from-reference.txt"

for f in from-baseline from-reference; do
    if [[ ! -s "$WORK/$f.txt" ]]; then
        echo "FAIL: $f fingerprint is empty -- could not read that database" >&2
        exit 1
    fi
done

echo "==> comparing"
if diff -u "$WORK/from-reference.txt" "$WORK/from-baseline.txt" > "$WORK/diff.txt"; then
    echo
    echo "OK: baseline.sql reproduces the reference schema exactly."
    echo "    $(wc -l < "$WORK/from-baseline.txt") catalogue objects compared."
    exit 0
fi

echo
echo "DRIFT: baseline.sql and the reference database disagree." >&2
echo "  '-' lines are in the reference and missing from baseline.sql" >&2
echo "  '+' lines are in baseline.sql and missing from the reference" >&2
echo >&2
sed -n '4,80p' "$WORK/diff.txt" >&2
echo >&2
echo "Re-capture the baseline, or apply the missing change to the reference." >&2
exit 1
