#!/usr/bin/env bash
# What instance.sh would ask AWS to do.
#
#   deploy/instance.test.sh
#
# There is no AWS account reachable from the development machine and no AWS CLI
# installed on it, so the alternative to this is a script nobody has ever run.
# It substitutes a stub for the CLI, runs each command, and checks the calls.
#
# It does NOT prove the calls succeed against AWS -- only that the right ones
# are made, with the right instance and the right region, and that the script
# refuses rather than guessing when it has not been configured. The first real
# `start` is still the first real test of the other half.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/instance.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0

check() {
    local what="$1" expected="$2" actual="$3"
    if [[ "$actual" == *"$expected"* ]]; then
        PASS=$((PASS + 1))
    else
        FAIL=$((FAIL + 1))
        echo "FAIL: $what"
        echo "  expected to contain: $expected"
        echo "  actual:"
        sed 's/^/    /' <<<"$actual"
    fi
}

refuses() {
    local what="$1" expected="$2"; shift 2
    local output status
    output="$("$@" 2>&1)"; status=$?
    if [[ $status -eq 0 ]]; then
        FAIL=$((FAIL + 1))
        echo "FAIL: $what -- exited 0, should have refused"
    elif [[ "$output" != *"$expected"* ]]; then
        FAIL=$((FAIL + 1))
        echo "FAIL: $what -- refused, but the message did not mention '$expected'"
        sed 's/^/    /' <<<"$output"
    else
        PASS=$((PASS + 1))
    fi
}

export SKILLBRIDGE_INSTANCE_ID=i-0test0test0test0
export SKILLBRIDGE_REGION=ap-south-1
export SKILLBRIDGE_HOST=1-2-3-4.nip.io
export SKILLBRIDGE_SSH_KEY="$WORK/key.pem"
export AWS_CLI=aws

# ---------------------------------------------------------------------------

out="$(bash "$SCRIPT" start --dry-run 2>&1)"
check "start asks EC2 to start the instance" \
      "ec2 start-instances --instance-ids i-0test0test0test0" "$out"
check "start names the region explicitly, not whatever the CLI is configured for" \
      "--region ap-south-1" "$out"

out="$(bash "$SCRIPT" stop --dry-run 2>&1)"
check "stop asks EC2 to stop the instance" \
      "ec2 stop-instances --instance-ids i-0test0test0test0" "$out"
check "stop says what stopping is for" "compute billing ends" "$out"

# The one mistake with real money attached: stop must not be quietly a no-op,
# and start must not be what runs when you typed stop.
[[ "$out" != *"start-instances"* ]] \
    && PASS=$((PASS + 1)) \
    || { FAIL=$((FAIL + 1)); echo "FAIL: stop issued start-instances"; }

out="$(bash "$SCRIPT" ssh --dry-run 2>&1)"
check "ssh uses the key and the configured user" \
      "ssh -i $WORK/key.pem ubuntu@1-2-3-4.nip.io" "$out"

out="$(bash "$SCRIPT" logs --dry-run 2>&1)"
check "logs follows the compose logs on the host" \
      "docker compose -f /opt/skillbridge/compose.yml logs -f" "$out"

# A region default that silently differed from where the instance lives would
# produce "instance not found" against an account that plainly has it.
out="$(SKILLBRIDGE_REGION= bash "$SCRIPT" status --dry-run 2>&1)"
check "the region defaults to Mumbai, where the plan puts the host" \
      "--region ap-south-1" "$out"

refuses "an unset instance id is refused, not sent as an empty string" \
        "SKILLBRIDGE_INSTANCE_ID is not set" \
        env -u SKILLBRIDGE_INSTANCE_ID bash "$SCRIPT" start --dry-run

refuses "an unknown subcommand is refused" \
        "unknown argument" \
        bash "$SCRIPT" restart --dry-run

refuses "no subcommand prints the usage and fails" \
        "instance.sh start" \
        bash "$SCRIPT" --dry-run

# ---------------------------------------------------------------------------

echo
if [[ $FAIL -eq 0 ]]; then
    echo "OK: $PASS checks passed."
else
    echo "$FAIL failed, $PASS passed."
fi
exit $((FAIL > 0))
