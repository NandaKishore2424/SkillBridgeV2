#!/usr/bin/env bash
# Run psql or pg_dump against a database named by URL, from a throwaway
# container, without the password ever being a process argument.
#
#   scripts/db/pg.sh psql -c 'select 1'
#   scripts/db/pg.sh --app-env /opt/skillbridge/app.env pg_dump --schema=public
#   gzip -dc corpus.sql.gz | scripts/db/pg.sh psql -v ON_ERROR_STOP=1
#
# WHICH DATABASE, first match wins
#
#   1. SKILLBRIDGE_DATABASE_URL in the environment
#   2. --app-env FILE: AI_DATABASE_URL from that file. The one key is read; the
#      file is not sourced. On the host it is root-only, so use sudo.
#   3. Otherwise it asks, on the terminal, without echo: the URL never reaches
#      shell history, the screen or a recording.
#
# WHY A CONTAINER
#
# The deployed database is Supabase, so there is no postgres service to exec
# into, and neither a fresh EC2 AMI nor the development laptop has a PostgreSQL
# client installed. pgvector/pgvector:pg17 is the image the schema is built and
# tested against, so the client matches the server's major version.
#
# WHY THE PASSWORD IS SPLIT OUT
#
# A container's processes are processes on the host: `ps` shows their argv to
# every user. Handing psql the whole URL puts the password there for as long as
# the command runs -- the whole of a pg_dump. So the password is taken out of
# the URL and passed as PGPASSWORD, which libpq reads from the environment, and
# `docker run -e NAME` with no value copies NAME from this process rather than
# from its own command line. What remains on argv is the URL without it.
#
# --network host so a URL naming localhost means the same inside the container
# as outside it. Against Supabase it changes nothing; it is what lets this be
# tested against a local throwaway database.

set -euo pipefail

PG_IMAGE="${PG_IMAGE:-pgvector/pgvector:pg17}"

APP_ENV=""
if [[ "${1:-}" == --app-env ]]; then
    [[ $# -ge 2 ]] || { echo "--app-env needs a file" >&2; exit 2; }
    APP_ENV="$2"; shift 2
fi
case "${1:-}" in
    psql|pg_dump|pg_restore) ;;
    *) echo "usage: $0 [--app-env FILE] psql|pg_dump|pg_restore [args...]" >&2; exit 2 ;;
esac

url="${SKILLBRIDGE_DATABASE_URL:-}"
if [[ -z "$url" && -n "$APP_ENV" ]]; then
    if [[ ! -r "$APP_ENV" ]]; then
        echo "Cannot read $APP_ENV. On the host it is root-only: run this with sudo." >&2
        exit 2
    fi
    url=$(sed -n 's/^AI_DATABASE_URL=//p' "$APP_ENV" | tail -1)
    [[ -n "$url" ]] || { echo "$APP_ENV has no AI_DATABASE_URL." >&2; exit 2; }
fi
if [[ -z "$url" ]]; then
    # /dev/tty, not stdin: stdin is usually the dump being piped in.
    if ! { exec 3<>/dev/tty; } 2>/dev/null; then
        echo "No database URL: set SKILLBRIDGE_DATABASE_URL or pass --app-env FILE." >&2
        exit 2
    fi
    printf 'Database URL (postgresql://..., not echoed): ' >&3
    IFS= read -r -s url <&3
    printf '\n' >&3
    exec 3>&-
    [[ -n "$url" ]] || { echo "No URL given. Nothing ran." >&2; exit 2; }
fi

# postgresql://user:password@rest -> postgresql://user@rest, plus the password.
# A password containing @ / : or % must already be percent-encoded in a URL,
# which is what the Supabase dashboard produces; it is decoded here because
# PGPASSWORD takes the password itself, not its URL form.
PGPASSWORD="${PGPASSWORD:-}"
if [[ "$url" =~ ^(postgres(ql)?://)([^:@/]+):([^@/]*)@(.*)$ ]]; then
    encoded="${BASH_REMATCH[4]}"
    url="${BASH_REMATCH[1]}${BASH_REMATCH[3]}@${BASH_REMATCH[5]}"
    encoded="${encoded//\\/\\\\}"          # a literal backslash stays literal
    printf -v PGPASSWORD '%b' "${encoded//%/\\x}"
elif [[ ! "$url" =~ ^postgres(ql)?:// ]]; then
    echo "That is not a postgresql:// URL. Nothing ran." >&2
    exit 2
fi
export PGPASSWORD

# Anything else the caller exported for psql's \getenv, by name only.
passthrough=()
for name in ${PG_PASSTHROUGH_ENV:-}; do passthrough+=(-e "$name"); done

tool="$1"; shift
exec docker run --rm -i --network host -e PGPASSWORD "${passthrough[@]}" \
    "$PG_IMAGE" "$tool" -d "$url" "$@"
