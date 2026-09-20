#!/usr/bin/env bash
# Switch the demo host on before an interview and off afterwards.
#
#   deploy/instance.sh start      # start it, wait, print the URL
#   deploy/instance.sh stop       # stop it (this is the one that saves money)
#   deploy/instance.sh status     # what state it is in, and what that costs
#   deploy/instance.sh ssh        # shell on it
#   deploy/instance.sh logs       # tail the compose logs over SSH
#
# Add --dry-run to any of them to print the AWS calls without making them.
#
# CONFIGURATION comes from deploy/instance.env (gitignored), or the environment:
#
#   SKILLBRIDGE_INSTANCE_ID=i-0123456789abcdef0
#   SKILLBRIDGE_REGION=ap-south-1
#   SKILLBRIDGE_HOST=13-234-56-78.nip.io
#   SKILLBRIDGE_SSH_KEY=~/.ssh/skillbridge.pem
#   SKILLBRIDGE_SSH_USER=ec2-user
#
# WHY STOPPING MATTERS MORE THAN ANYTHING ELSE HERE
#
# A stopped instance is not billed for compute. Its EBS volume and its Elastic
# IP are billed regardless, and those are the standing cost -- a few dollars a
# month against a credit that expires 2027-01-27. Compute is what would burn
# it, and only if the instance is left running. Leaving it on for a month
# costs more than a year of interviews. See docs/DEPLOYMENT.md for the numbers.
#
# The AWS CLI is called through $AWS_CLI so the tests can substitute a stub and
# check what this script would run. See instance.test.sh.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS_CLI="${AWS_CLI:-aws}"
DRY_RUN=false

[[ -f "$HERE/instance.env" ]] && . "$HERE/instance.env"

REGION="${SKILLBRIDGE_REGION:-ap-south-1}"
SSH_USER="${SKILLBRIDGE_SSH_USER:-ec2-user}"

usage() { sed -n '2,27p' "${BASH_SOURCE[0]}" | sed 's/^#\{1,\} \{0,1\}//'; }

COMMAND=""
for arg in "$@"; do
    case "$arg" in
        --dry-run)              DRY_RUN=true ;;
        -h|--help)              usage; exit 0 ;;
        start|stop|status|ssh|logs) COMMAND="$arg" ;;
        *) echo "unknown argument: $arg" >&2; usage >&2; exit 2 ;;
    esac
done
[[ -n "$COMMAND" ]] || { usage >&2; exit 2; }

require() {
    local name="$1"
    [[ -n "${!name:-}" ]] || {
        echo "$name is not set. Put it in deploy/instance.env or the environment." >&2
        echo "See the header of this script." >&2
        exit 2
    }
}

aws_ec2() {
    if [[ "$DRY_RUN" == true ]]; then
        # stderr, not stdout. Several callers send the real command's output to
        # /dev/null because they only care that it succeeded, and a dry run
        # that wrote to stdout would be discarded along with it -- printing
        # nothing and looking like it had decided to do nothing.
        echo "would run: $AWS_CLI ec2 $* --region $REGION" >&2
        return 0
    fi
    "$AWS_CLI" ec2 "$@" --region "$REGION"
}

instance_state() {
    aws_ec2 describe-instances --instance-ids "$SKILLBRIDGE_INSTANCE_ID" \
        --query 'Reservations[0].Instances[0].State.Name' --output text
}

# ---------------------------------------------------------------------------

case "$COMMAND" in

start)
    require SKILLBRIDGE_INSTANCE_ID
    aws_ec2 start-instances --instance-ids "$SKILLBRIDGE_INSTANCE_ID" >/dev/null
    echo "starting $SKILLBRIDGE_INSTANCE_ID in $REGION"

    if [[ "$DRY_RUN" != true ]]; then
        # `wait instance-running` returns when EC2 says the instance is
        # running, which is well before sshd is listening and much before the
        # containers are up. The HTTP check below is the one that means
        # anything, so this is only here to fail fast on an instance that
        # cannot start at all.
        aws_ec2 wait instance-running --instance-ids "$SKILLBRIDGE_INSTANCE_ID"
        echo "instance running; waiting for the application"

        if [[ -n "${SKILLBRIDGE_HOST:-}" ]]; then
            # compose restarts the containers on boot (restart: unless-stopped),
            # and the backend waits on PostgreSQL's healthcheck, so a cold start
            # is a minute or two rather than seconds. Poll rather than guess.
            for _ in $(seq 60); do
                if curl -fsS --max-time 5 "https://$SKILLBRIDGE_HOST/" >/dev/null 2>&1; then
                    echo
                    echo "  https://$SKILLBRIDGE_HOST"
                    echo
                    exit 0
                fi
                printf '.'
                sleep 5
            done
            echo
            echo "The instance is running but https://$SKILLBRIDGE_HOST did not answer" >&2
            echo "within five minutes. Check the containers:" >&2
            echo "  $0 logs" >&2
            exit 1
        fi
    fi
    ;;

stop)
    require SKILLBRIDGE_INSTANCE_ID
    aws_ec2 stop-instances --instance-ids "$SKILLBRIDGE_INSTANCE_ID" >/dev/null
    echo "stopping $SKILLBRIDGE_INSTANCE_ID -- compute billing ends once it is 'stopped'"
    [[ "$DRY_RUN" == true ]] || aws_ec2 wait instance-stopped --instance-ids "$SKILLBRIDGE_INSTANCE_ID"
    echo "stopped"
    ;;

status)
    require SKILLBRIDGE_INSTANCE_ID
    state="$(instance_state)"
    echo "instance: $SKILLBRIDGE_INSTANCE_ID ($REGION)"
    echo "state:    $state"
    case "$state" in
        running) echo "          billing for compute. Stop it when the interview ends." ;;
        stopped) echo "          not billing for compute; the volume and the Elastic IP still are." ;;
    esac
    [[ -n "${SKILLBRIDGE_HOST:-}" ]] && echo "url:      https://$SKILLBRIDGE_HOST"
    ;;

ssh|logs)
    require SKILLBRIDGE_HOST
    require SKILLBRIDGE_SSH_KEY
    remote=""
    [[ "$COMMAND" == logs ]] && remote="cd /opt/skillbridge/deploy && docker compose -f docker-compose.prod.yml logs -f --tail=100"
    if [[ "$DRY_RUN" == true ]]; then
        echo "would run: ssh -i $SKILLBRIDGE_SSH_KEY $SSH_USER@$SKILLBRIDGE_HOST${remote:+ $remote}"
        exit 0
    fi
    # shellcheck disable=SC2086
    exec ssh -i "${SKILLBRIDGE_SSH_KEY/#\~/$HOME}" "$SSH_USER@$SKILLBRIDGE_HOST" ${remote:+"$remote"}
    ;;

esac
