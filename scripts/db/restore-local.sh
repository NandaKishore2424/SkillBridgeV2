#!/usr/bin/env bash
# Restore the 2026-09-19 backup of the retired Supabase database into the local
# Docker PostgreSQL (docker-compose.yml), and prove the result is identical to
# what was live.
#
#   scripts/db/restore-local.sh            # refuses if the database already has tables
#   scripts/db/restore-local.sh --force    # drops the public schema first
#
# BACKUP_DIR defaults to ~/skillbridge-backup. It must hold:
#   skillbridge-2026-09-19.sql   plain pg_dump of schema public (the owner's dump.sh)
#   SHA256SUMS                   written when the dump was verified
#   content-check.sql            per-table content md5 query, run on live at dump time
#   live-content.txt             that query's result on live
#
# Abort-first: every check that can fail runs before anything is dropped.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-$HOME/skillbridge-backup}"
DUMP="$BACKUP_DIR/skillbridge-2026-09-19.sql"
FORCE=false
[[ "${1:-}" == "--force" ]] && FORCE=true

cd "$(dirname "$0")/../.."
psql_local() { docker compose exec -T -e PGTZ=UTC postgres psql -U skillbridge -d skillbridge -v ON_ERROR_STOP=1 "$@"; }

echo "1/6 Checking the backup files"
for f in "$DUMP" "$BACKUP_DIR/SHA256SUMS" "$BACKUP_DIR/content-check.sql" "$BACKUP_DIR/live-content.txt"; do
  [[ -f "$f" ]] || { echo "Missing $f" >&2; exit 1; }
done
(cd "$BACKUP_DIR" && grep 'skillbridge-2026-09-19.sql' SHA256SUMS | sha256sum -c --quiet -) \
  || { echo "Checksum mismatch: $DUMP is not the file that was verified" >&2; exit 1; }

echo "2/6 Checking the local database is up and empty"
docker compose up -d --wait postgres >/dev/null
tables=$(psql_local -tAc "select count(*) from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r'")
if [[ "$tables" != "0" && "$FORCE" != true ]]; then
  echo "The local database already has $tables tables. Re-run with --force to replace them." >&2
  exit 1
fi

echo "3/6 Recreating schema public with the extensions the dump expects"
psql_local -q <<'SQL'
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
CREATE EXTENSION vector SCHEMA public;
CREATE EXTENSION pg_trgm SCHEMA public;
SQL

echo "4/6 Restoring (the dump's own CREATE SCHEMA public is skipped: it already exists)"
sed '0,/^CREATE SCHEMA public;$/s//-- CREATE SCHEMA public; (created by restore-local.sh)/' "$DUMP" \
  | psql_local -q >/dev/null

echo "5/6 Comparing every table's contents with what was live"
psql_local -tA < "$BACKUP_DIR/content-check.sql" > /tmp/skillbridge-restored-content.txt
if diff -q "$BACKUP_DIR/live-content.txt" /tmp/skillbridge-restored-content.txt >/dev/null; then
  echo "    identical: $(wc -l < /tmp/skillbridge-restored-content.txt) tables"
else
  diff "$BACKUP_DIR/live-content.txt" /tmp/skillbridge-restored-content.txt >&2 || true
  echo "Restored contents differ from live. Stopping." >&2
  exit 1
fi

echo "6/6 Retiring the legacy Flyway history (V1-V15 of files deleted on 2026-09-05)"
# It describes migrations that no longer exist, and Flyway would read it as its
# own history. Its 12 rows remain in the backup.
psql_local -q -c "DROP TABLE public.flyway_schema_history"

echo "Done. Start the backend: Flyway will baseline this database at V2."
