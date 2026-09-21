#!/usr/bin/env bash
# Put the local database into the demo state, and wait until the skill-gap
# reports it asks for have actually been produced.
#
#   scripts/db/seed-demo.sh              # ask before replacing data
#   scripts/db/seed-demo.sh --yes        # do not ask
#   scripts/db/seed-demo.sh --no-wait    # seed only; do not wait for reports
#
#   # The deployed database, from the EC2 host (app.env is root-only):
#   sudo scripts/db/seed-demo.sh --yes --app-env /opt/skillbridge/app.env
#
# WHICH DATABASE
#
# By default, the postgres service of the development compose file. With
# --app-env FILE it reads AI_DATABASE_URL from that file -- the one key, the
# file is not sourced -- and with SKILLBRIDGE_DATABASE_URL set it uses that
# libpq URL. With a URL, psql runs through scripts/db/pg.sh in a throwaway
# container of the image the schema is tested against, because the host has
# neither a postgres service to exec into (the database is Supabase) nor, on a
# fresh AMI, a psql of its own.
#
# This is a reset. It replaces every row of application data with the dataset in
# demo-seed.sql -- two colleges, sixteen students, their skills, batches,
# syllabus, progress, feedback and placements -- and leaves the 1,500 job
# descriptions, their embeddings and the roles table alone. Running it twice
# gives the same database both times.
#
# THE PASSWORD
#
# Every demo account shares one, read from SKILLBRIDGE_DEMO_PASSWORD in the
# gitignored root .env. Nothing in git has ever held it. If it is not set, the
# script generates one and appends it to that file -- it is never printed, so it
# cannot be read off a recording, a screenshot or a terminal transcript. Open
# .env to see it.
#
# WAITING FOR THE REPORTS
#
# The seed does not compute the reports. It writes one PROFILE_UPDATED event per
# student to the outbox, exactly as the application does when a student edits
# their skills; the backend's relay publishes them and the AI service analyses
# each student and stores the result. So this script needs the backend, the AI
# service and RabbitMQ running, and what it waits for is the real pipeline
# finishing -- which is also the only honest way to know it works.

set -euo pipefail

cd "$(dirname "$0")/../.."

ASSUME_YES=false
WAIT_FOR_REPORTS=true
APP_ENV=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --yes|-y)   ASSUME_YES=true ;;
        --no-wait)  WAIT_FOR_REPORTS=false ;;
        --app-env)  if [[ $# -lt 2 || "$2" == -* ]]; then
                        echo "--app-env needs a file, e.g. --app-env /opt/skillbridge/app.env" >&2
                        exit 2
                    fi
                    APP_ENV="$2"; shift ;;
        -h|--help)  sed -n '2,/^$/p' "$0" | sed 's/^#\{1,\} \{0,1\}//'; exit 0 ;;
        *)          echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

# Which database to seed. A URL (or --app-env) means a remote database, reached
# through scripts/db/pg.sh -- which says why a container, and how it keeps the
# password off every process's argv.
DATABASE_URL_FOR_SEED="${SKILLBRIDGE_DATABASE_URL:-}"
if [[ -n "$APP_ENV" ]]; then
    if [[ ! -r "$APP_ENV" ]]; then
        echo "Cannot read $APP_ENV. On the host it is root-only: run this with sudo." >&2
        exit 2
    fi
    DATABASE_URL_FOR_SEED=$(sed -n 's/^AI_DATABASE_URL=//p' "$APP_ENV" | tail -1)
    if [[ -z "$DATABASE_URL_FOR_SEED" ]]; then
        echo "$APP_ENV has no AI_DATABASE_URL, so there is no database to seed." >&2
        exit 2
    fi
fi

# The demo password reaches psql through the environment and \getenv, never as
# `-v demo_password=...` on a command line that `ps` would show.
export SKILLBRIDGE_DEMO_PASSWORD="${SKILLBRIDGE_DEMO_PASSWORD:-}"
if [[ -n "$DATABASE_URL_FOR_SEED" ]]; then
    export SKILLBRIDGE_DATABASE_URL="$DATABASE_URL_FOR_SEED"
    export PG_PASSTHROUGH_ENV=SKILLBRIDGE_DEMO_PASSWORD
    psql_local() { scripts/db/pg.sh psql -v ON_ERROR_STOP=1 "$@"; }
else
    psql_local() { docker compose exec -T -e SKILLBRIDGE_DEMO_PASSWORD postgres \
                       psql -U skillbridge -d skillbridge -v ON_ERROR_STOP=1 "$@"; }
fi
# </dev/null: `docker run -i` forwards stdin, and a query must not eat input
# meant for the confirmation prompt further down.
ask()        { psql_local -tAc "$1" </dev/null; }

# ---------------------------------------------------------------------------
# 1. The database has to be up, and migrated
# ---------------------------------------------------------------------------

echo "1/6 Checking the database"
if [[ -n "$DATABASE_URL_FOR_SEED" ]]; then
    # Said here, in words, rather than left to psql's connection error halfway
    # through: a wrong URL is the likeliest failure and should not look like a
    # broken seed. The URL itself is not printed; it holds the password.
    if ! ask "SELECT 1" >/dev/null; then
        echo "    Could not connect to the database named by AI_DATABASE_URL /" >&2
        echo "    SKILLBRIDGE_DATABASE_URL (the psql error is above). Nothing changed." >&2
        exit 1
    fi
    echo "    $(ask "SELECT current_database() || ' on ' || coalesce(inet_server_addr()::text, 'local socket')")"
else
    docker compose up -d --wait postgres >/dev/null
fi
missing=$(ask "SELECT count(*) FROM (VALUES ('colleges'),('students'),('student_skills'),('outbox_events'))
               AS t(name) WHERE to_regclass('public.' || name) IS NULL")
if [[ "$missing" != "0" ]]; then
    echo "    The schema is not there. Start the backend once so Flyway can build it:" >&2
    if [[ -n "$DATABASE_URL_FOR_SEED" ]]; then
        echo "      sudo skillbridge-deploy" >&2
    else
        echo "      cd skillbridge-backend && ./mvnw spring-boot:run" >&2
    fi
    exit 1
fi

corpus=$(ask "SELECT count(*) FROM industry_job_descriptions WHERE embedding IS NOT NULL")
echo "    $corpus job descriptions with embeddings (left untouched)"
if [[ "$corpus" == "0" ]]; then
    echo "    Warning: the corpus is empty, so every report will match nothing." >&2
    if [[ -n "$DATABASE_URL_FOR_SEED" ]]; then
        echo "    Load it first: docs/DEPLOYMENT.md, \"The corpus\"." >&2
    else
        echo "    Restore it with scripts/db/restore-local.sh." >&2
    fi
fi

# ---------------------------------------------------------------------------
# 2. Say what is about to be replaced
# ---------------------------------------------------------------------------

existing=$(ask "SELECT count(*) FROM colleges")
if [[ "$existing" != "0" && "$ASSUME_YES" != true ]]; then
    echo
    echo "    About to delete and rebuild:"
    psql_local -c "SELECT c.code, c.name,
                          (SELECT count(*) FROM students s WHERE s.college_id = c.id) AS students,
                          (SELECT count(*) FROM batches b  WHERE b.college_id = c.id) AS batches
                     FROM colleges c ORDER BY c.code"
    echo "    The job corpus, the embeddings and the roles table are not touched."
    echo
    read -r -p "    Continue? [y/N] " reply
    [[ "$reply" =~ ^[Yy]$ ]] || { echo "    Nothing changed."; exit 1; }
fi

# ---------------------------------------------------------------------------
# 3. The shared demo password
# ---------------------------------------------------------------------------

echo "2/6 Resolving the demo password"
DEMO_PASSWORD="${SKILLBRIDGE_DEMO_PASSWORD:-}"
if [[ -z "$DEMO_PASSWORD" && -f .env ]]; then
    # Read the one key, without sourcing the file: .env also holds the database
    # and broker credentials, and this script has no business with those.
    DEMO_PASSWORD=$(sed -n 's/^SKILLBRIDGE_DEMO_PASSWORD=//p' .env | head -1)
fi

generated=false
if [[ -z "$DEMO_PASSWORD" ]]; then
    # Long enough to satisfy the application's own policy, and made here rather
    # than chosen, so no password this script creates has ever been in git.
    DEMO_PASSWORD="Demo-$(openssl rand -base64 12 | tr -d '/+=' | head -c 12)-26"
    generated=true

    # Written, never printed. The last set of demo passwords reached a public
    # repository by being convenient to read off a screen; a password that only
    # ever exists in a gitignored file cannot be copied out of a recording, a
    # terminal transcript or a screenshot.
    printf '\n# Shared by every account scripts/db/seed-demo.sh creates.\nSKILLBRIDGE_DEMO_PASSWORD=%s\n' \
        "$DEMO_PASSWORD" >> .env
fi

# ---------------------------------------------------------------------------
# 4. Seed
# ---------------------------------------------------------------------------

echo "3/6 Borrowing pgcrypto to hash the password"
had_pgcrypto=$(ask "SELECT count(*) FROM pg_extension WHERE extname = 'pgcrypto'")
psql_local -q -c "CREATE EXTENSION IF NOT EXISTS pgcrypto"

give_back_pgcrypto() {
    # Only if this script installed it. Dropping an extension somebody else
    # relies on would be a rude way to end.
    if [[ "$had_pgcrypto" == "0" ]]; then
        psql_local -q -c "DROP EXTENSION IF EXISTS pgcrypto" || true
    fi
}
trap give_back_pgcrypto EXIT

echo "4/6 Seeding"
# \getenv rather than -v demo_password=..., so the password is never an argument.
export SKILLBRIDGE_DEMO_PASSWORD="$DEMO_PASSWORD"
{ echo '\getenv demo_password SKILLBRIDGE_DEMO_PASSWORD'; cat scripts/db/demo-seed.sql; } \
    | psql_local -q -f -

give_back_pgcrypto
trap - EXIT

students=$(ask "SELECT count(*) FROM students")
withskills=$(ask "SELECT count(DISTINCT student_id) FROM student_skills")
echo "    $(ask 'SELECT count(*) FROM colleges') colleges, $students students ($withskills with skills),"
echo "    $(ask 'SELECT count(*) FROM batches') batches, $(ask 'SELECT count(*) FROM syllabus_topics') syllabus topics,"
echo "    $(ask 'SELECT count(*) FROM topic_progress') progress rows, $(ask 'SELECT count(*) FROM placements') placements"

# ---------------------------------------------------------------------------
# 5. Wait for the pipeline
# ---------------------------------------------------------------------------

if [[ "$WAIT_FOR_REPORTS" != true ]]; then
    echo "5/6 Not waiting for the analyses (--no-wait). $(ask "SELECT count(*) FROM outbox_events WHERE status = 'PENDING'") events are queued."
else
    echo "5/6 Waiting for the analyses (backend relay -> RabbitMQ -> AI service)"
    deadline=$((SECONDS + 180))
    reports=0
    while (( SECONDS < deadline )); do
        reports=$(ask "SELECT count(*) FROM skill_gap_reports")
        pending=$(ask "SELECT count(*) FROM outbox_events WHERE status = 'PENDING'")
        dead=$(ask "SELECT count(*) FROM outbox_events WHERE status = 'DEAD'")
        printf '\r    %s/%s reports, %s events still queued, %s dead   ' \
               "$reports" "$students" "$pending" "$dead"
        [[ "$reports" == "$students" ]] && break
        if [[ "$dead" != "0" ]]; then
            printf '\n'
            echo "    Events were dead-lettered. The relay could not publish them:" >&2
            psql_local -c "SELECT aggregate_id, attempts, left(last_error, 120) AS last_error
                             FROM outbox_events WHERE status = 'DEAD' LIMIT 5" >&2
            exit 1
        fi
        sleep 2
    done
    printf '\n'

    if [[ "$reports" != "$students" ]]; then
        echo "    Timed out with $reports of $students reports." >&2
        echo "    Check that the backend, the AI service and RabbitMQ are all running:" >&2
        if [[ -n "$DATABASE_URL_FOR_SEED" ]]; then
            echo "      docker compose -f /opt/skillbridge/compose.yml ps" >&2
        else
            echo "      backend     curl -fs localhost:8080/actuator/health" >&2
            echo "      AI service  curl -fs localhost:8000/health" >&2
            echo "      RabbitMQ    docker compose ps rabbitmq" >&2
        fi
        echo "    Re-run with --no-wait to seed without waiting." >&2
        exit 1
    fi
    psql_local -c "SELECT status, count(*) FROM skill_gap_reports GROUP BY status ORDER BY status"
fi

# ---------------------------------------------------------------------------
# 6. What to type at the demo
# ---------------------------------------------------------------------------

echo "6/6 Ready"
SIGN_IN_AT=http://localhost:5173
[[ -n "$DATABASE_URL_FOR_SEED" ]] && SIGN_IN_AT="the Vercel URL"
cat <<LOGINS

  Sign in at ${SIGN_IN_AT}

    System admin    admin@skillbridge.test
    College admin   priya@hillview.test        (Hillview)
                    sanjay@northgate.test      (Northgate)
    Trainer         arun@hillview.test         (Data Analytics 2026)
    Student         aditi@hillview.test        (close to the market)
                    karthik@hillview.test      (backend student, widest gap)
                    tarun@hillview.test        (no skills: a SKIPPED report)

LOGINS

if [[ "$generated" == true ]]; then
    echo "  A password was generated and appended to .env as"
    echo "  SKILLBRIDGE_DEMO_PASSWORD. Open that file to read it; it is not"
    echo "  printed here, and every later run will reuse it."
else
    echo "  Password: SKILLBRIDGE_DEMO_PASSWORD, from the gitignored root .env."
fi
echo
