#!/usr/bin/env bash
# What the three images do once they are running.
#
#   docker compose -f deploy/docker-compose.prod.yml build
#   deploy/images.test.sh
#
# A Dockerfile that builds proves only that it builds. These are the properties
# that are easy to lose in an edit and invisible until somebody is looking at
# the deployed thing: who the processes run as, what configuration rode along
# inside them, and whether the AI image can start without reaching the
# internet.
#
# Two images, not three. Vercel builds and serves the SPA, so there is no
# frontend image any more -- the SPA fallback that used to be checked here now
# lives in skillbridge-frontend/vercel.json, and vercel.test.sh checks it.
#
# Each check starts the image on its own with no dependencies, so this needs no
# database, no broker and no configuration. It is not a test of the system --
# that is what the rest of the suite is for.

set -uo pipefail

cd "$(dirname "$0")/.."

PASS=0
FAIL=0
CLEANUP=()

cleanup() {
    for c in "${CLEANUP[@]:-}"; do
        [[ -n "$c" ]] && docker rm -f "$c" >/dev/null 2>&1
    done
}
trap cleanup EXIT

ok()   { PASS=$((PASS + 1)); printf '  ok    %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; [[ -n "${2:-}" ]] && sed 's/^/          /' <<<"$2"; }

have_image() {
    docker image inspect "$1" >/dev/null 2>&1 && return 0
    echo "Image $1 is not built. Run:" >&2
    echo "  docker compose -f deploy/docker-compose.prod.yml build" >&2
    exit 2
}

BACKEND_IMAGE="${BACKEND_IMAGE:-skillbridge-backend:local}"
AI_IMAGE="${AI_IMAGE:-skillbridge-ai:local}"

# ----------------------------------------------------------------- backend

echo "backend ($BACKEND_IMAGE)"
have_image "$BACKEND_IMAGE"

# Not started: without a database it would fail its healthcheck and exit, and
# nothing here needs it running.
user="$(docker run --rm --entrypoint id "$BACKEND_IMAGE" -un 2>&1)"
[[ "$user" != "root" ]] \
    && ok "runs as $user, not root" \
    || bad "runs as root" "$user"

# No profile configuration beyond application.yaml itself.
#
# application-local.yaml is the one with credentials in it -- gitignored, and
# an image carrying it would put them wherever the image goes. application-test
# .yaml is the one with teeth for a different reason: it lives in
# src/main/resources, so Maven packages it, and a mistyped
# SPRING_PROFILES_ACTIVE could activate a configuration written for a
# throwaway database.
#
# The .example templates hold only placeholders, checked on 2026-09-20. They
# are excluded anyway: "no profile files in the image" is a rule that can be
# read off a listing, and "no profile files except the harmless ones" is not.
leaked="$(docker run --rm --entrypoint sh "$BACKEND_IMAGE" -c \
    'ls /app/BOOT-INF/classes/ 2>/dev/null | grep -E "^application-" || true' 2>&1)"
[[ -z "$leaked" ]] \
    && ok "carries application.yaml and no other profile" \
    || bad "carries profile files it should not" "$leaked"

# ---------------------------------------------------------------------- ai

echo "ai ($AI_IMAGE)"
have_image "$AI_IMAGE"

user="$(docker run --rm --entrypoint id "$AI_IMAGE" -un 2>&1)"
[[ "$user" != "root" ]] \
    && ok "runs as $user, not root" \
    || bad "runs as root" "$user"

# The weights are baked in so a cold start does not depend on HuggingFace being
# reachable. --network none is the only honest way to check that: with a
# network, a missing model is silently downloaded and the test passes for the
# wrong reason.
# encode(), not get_sentence_embedding_dimension(): the latter is deprecated in
# sentence-transformers 6 and prints a FutureWarning, and more to the point it
# only reads a config value. Encoding actually runs the model, which is the
# thing that has to work with no network.
loaded="$(docker run --rm --network none --entrypoint python "$AI_IMAGE" -c "
from sentence_transformers import SentenceTransformer
m = SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')
print(m.encode(['does this model actually run']).shape[-1])
" 2>&1 | tail -1)"
[[ "$loaded" == "384" ]] \
    && ok "loads AND runs MiniLM with no network, at 384 dimensions" \
    || bad "loads AND runs MiniLM with no network, at 384 dimensions" "$loaded"

# The CUDA wheel is over a gigabyte and the instance has no GPU. This is the
# check that the CPU index in the Dockerfile is still doing its job.
cuda="$(docker run --rm --entrypoint sh "$AI_IMAGE" -c \
    'ls /home/app/.local/lib/python3.12/site-packages/nvidia 2>/dev/null | head -3' 2>&1)"
[[ -z "$cuda" ]] \
    && ok "carries no CUDA runtime" \
    || bad "carries the CUDA runtime, which this deployment cannot use" "$cuda"

# ---------------------------------------------------------------------------

echo
if [[ $FAIL -eq 0 ]]; then
    echo "OK: $PASS checks passed."
else
    echo "$FAIL failed, $PASS passed."
fi
exit $((FAIL > 0))
