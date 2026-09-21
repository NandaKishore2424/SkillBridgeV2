#!/usr/bin/env bash
# Dump the database, keep a few locally, copy to S3 if one is configured.
#
#   deploy/backup.sh                 # dump, prune, upload
#   deploy/backup.sh --local-only    # dump and prune, never touch S3
#   deploy/backup.sh --list          # what exists, locally and in S3
#
# Meant to run from cron on the host:
#   15 2 * * *  cd /opt/skillbridge && deploy/backup.sh >> /var/log/skillbridge-backup.log 2>&1
#
# It reads /opt/skillbridge/app.env for the database connection and
# BACKUP_S3_BUCKET / BACKUP_S3_PREFIX / BACKUP_KEEP_LOCAL. With no bucket set
# it keeps local dumps only and says so, rather than failing -- a backup that
# exists on one disk is worth more than one that did not run.
#
# WHY THIS EXISTS WHEN SUPABASE TAKES ITS OWN BACKUPS
#
# The free tier keeps daily backups for a short window, and point-in-time
# recovery is a paid feature. More to the point, those backups live inside the
# same account as the thing being backed up: the failure they do not cover is
# the project being paused, deleted, or lost with the account. This dump is a
# copy somewhere else, which is the only property that makes it a backup.
#
# WHAT IT DUMPS, AND THE ONE THING THAT MATTERS
#
# The whole `public` schema, including industry_job_descriptions and its 1,500
# embeddings. On this host those are restored from ~/skillbridge-backup on the
# development machine, and that archive is the only other copy: the ETL's
# source CSV is gone. So the corpus exists in exactly two places, and this is
# what makes the second one recent rather than frozen at the day it was
# uploaded.
#
# A demo host's application data is reproducible -- scripts/db/seed-demo.sh
# rebuilds all of it in forty seconds. The corpus is not. If you ever trim what
# this dumps, that table is the one to keep.
#
# COMPRESSION
#
# pg_dump | gzip, not pg_dump -Fc. The custom format is better for selective
# restores; plain SQL is better for a backup you may one day have to read,
# grep, or restore with nothing but psql. On a corpus of a few tens of
# megabytes the size difference is not worth the tooling requirement.

set -euo pipefail

cd "$(dirname "$0")/.."
HERE="deploy"

# pg_dump runs through scripts/db/pg.sh: a throwaway container of the image the
# schema is built and tested against, because the database is Supabase and the
# host has no PostgreSQL client installed.

MODE=backup
for arg in "$@"; do
    case "$arg" in
        --local-only) LOCAL_ONLY=true ;;
        --list)       MODE=list ;;
        -h|--help)    sed -n '2,20p' "$0" | sed 's/^#\{1,\} \{0,1\}//'; exit 0 ;;
        *)            echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done
LOCAL_ONLY="${LOCAL_ONLY:-false}"

# app.env on the host, .env when run from a checkout on a laptop.
for candidate in /opt/skillbridge/app.env "$HERE/.env"; do
    [[ -f "$candidate" ]] && { set -a; . "$candidate"; set +a; break; }
done

# libpq form. AI_DATABASE_URL in app.env is already exactly this, so the
# normal case needs no extra configuration; BACKUP_DATABASE_URL overrides it
# for a one-off dump of somewhere else.
BACKUP_DATABASE_URL="${BACKUP_DATABASE_URL:-${AI_DATABASE_URL:-}}"
if [[ -z "$BACKUP_DATABASE_URL" && "$MODE" != list ]]; then
    echo "No database URL. Set AI_DATABASE_URL in $HERE/.env or /opt/skillbridge/app.env," >&2
    echo "or pass BACKUP_DATABASE_URL=postgresql://..." >&2
    exit 2
fi

BUCKET="${BACKUP_S3_BUCKET:-}"
PREFIX="${BACKUP_S3_PREFIX:-skillbridge/postgres}"
KEEP="${BACKUP_KEEP_LOCAL:-7}"
DIR="${BACKUP_DIR:-$HERE/backups}"
AWS_CLI="${AWS_CLI:-aws}"

if [[ "$MODE" == list ]]; then
    echo "local ($DIR):"
    ls -lh "$DIR"/*.sql.gz 2>/dev/null | awk '{print "  " $9 "  " $5}' || echo "  none"
    if [[ -n "$BUCKET" ]]; then
        echo "s3 (s3://$BUCKET/$PREFIX/):"
        "$AWS_CLI" s3 ls "s3://$BUCKET/$PREFIX/" | sed 's/^/  /' || echo "  unreadable"
    else
        echo "s3: no BACKUP_S3_BUCKET set"
    fi
    exit 0
fi

mkdir -p "$DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$DIR/skillbridge-$STAMP.sql.gz"

echo "1/4 Dumping"
# --clean --if-exists so the dump can be restored over an existing database
# without a manual drop first -- which is the state you are in when you need it.
# --no-owner and --no-acl because Supabase's role names are not ours to
# recreate, and a restore that fails on GRANT statements is not a restore.
#
# Failing the pipeline on pg_dump's exit status, not gzip's, is why pipefail is
# set: without it a failed dump gzips to a valid empty archive and reports
# success, and you find out at restore time.
#
# The password goes in the environment, not on any command line, so `ps` on the
# host does not show it to every user for the length of the dump; pg.sh says how.
SKILLBRIDGE_DATABASE_URL="$BACKUP_DATABASE_URL" scripts/db/pg.sh \
    pg_dump --schema=public --clean --if-exists --no-owner --no-acl \
    | gzip -9 > "$FILE"

SIZE=$(du -h "$FILE" | cut -f1)
echo "    $FILE ($SIZE)"

echo "2/4 Checking the dump is not empty of the thing that matters"
#
# A dump that ran, produced a plausible-looking file, and contains no corpus is
# the failure this script exists to prevent, and it is otherwise completely
# silent: the file is there, it is a few megabytes, and it restores cleanly
# into a database with no job descriptions in it.
#
# Counting the rows, not grepping for the COPY line. `COPY public.industry_job_
# descriptions (...) FROM stdin;` followed immediately by `\.` is exactly what
# an empty table dumps as -- so the header's presence proves nothing, and that
# is the case worth catching.
#
# awk rather than `grep -q`, and not by preference. Under `set -o pipefail`,
# `gzip -dc file | grep -q pattern` FAILS WHEN THE PATTERN MATCHES: grep exits
# at the first hit, gzip gets SIGPIPE, and the pipeline reports 141. The first
# version of this check was written that way and quarantined a perfectly good
# dump for having the corpus in it. awk reads to the end, so there is no signal
# to mistake for a verdict.
MIN_ROWS="${BACKUP_MIN_CORPUS_ROWS:-1000}"
rows=$(gzip -dc "$FILE" | awk '
    /^COPY public\.industry_job_descriptions /  { inside = 1; next }
    inside && /^\\\.$/                          { inside = 0 }
    inside                                      { n++ }
    END                                         { print n + 0 }')

if [[ "$rows" -lt "$MIN_ROWS" ]]; then
    echo "    The dump holds $rows job descriptions; at least $MIN_ROWS were expected." >&2
    echo "    That table is the corpus and its source CSV is gone, so a dump without" >&2
    echo "    it is not a backup. Keeping the file as .suspect rather than as a" >&2
    echo "    backup that would be trusted." >&2
    mv "$FILE" "$FILE.suspect"
    exit 1
fi
echo "    corpus present: $rows job descriptions"

echo "3/4 Pruning local copies (keeping $KEEP)"
# shellcheck disable=SC2012
ls -1t "$DIR"/skillbridge-*.sql.gz 2>/dev/null | tail -n "+$((KEEP + 1))" | while read -r old; do
    echo "    rm $old"
    rm -f "$old"
done

echo "4/4 Uploading"
if [[ "$LOCAL_ONLY" == true ]]; then
    echo "    skipped (--local-only)"
elif [[ -z "$BUCKET" ]]; then
    echo "    skipped: BACKUP_S3_BUCKET is not set, so this backup exists on this"
    echo "    instance only -- which is the same disk as the database it backs up."
else
    # STANDARD_IA: this is written once and read approximately never, which is
    # exactly what infrequent-access storage is priced for. Lifecycle rules on
    # the bucket handle anything older; this script does not delete in S3, on
    # purpose, so a bug here cannot destroy the remote copies too.
    "$AWS_CLI" s3 cp "$FILE" "s3://$BUCKET/$PREFIX/$(basename "$FILE")" \
        --storage-class STANDARD_IA
    echo "    s3://$BUCKET/$PREFIX/$(basename "$FILE")"
fi

echo "Done."
