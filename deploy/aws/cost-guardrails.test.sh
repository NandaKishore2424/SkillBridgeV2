#!/usr/bin/env bash
# What cost-guardrails.yaml declares, checked without an AWS account.
#
#   deploy/aws/cost-guardrails.test.sh
#
# There is no AWS CLI on the development machine, so `cloudformation validate-
# template` is not available and neither is a deploy. What can be checked here
# is the structure and the intent: that the auto-stop exists and is enabled,
# that the role it runs under can do one thing to one instance, and that the
# budget notifies somebody before the money is gone rather than after.
#
# It does NOT prove CloudFormation accepts the template. The first `aws
# cloudformation deploy` is still the first real test of that, and
# docs/DEPLOYMENT.md says so where it tells you to run it.

set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

python3 - "$HERE/cost-guardrails.yaml" <<'PY'
import sys, json

try:
    import yaml
except ModuleNotFoundError:
    print("PyYAML is not installed; skipping", file=sys.stderr)
    raise SystemExit(0)

# Plain yaml.safe_load. The template uses long-form intrinsics (Ref:, Fn::Sub:)
# precisely so that it can be read here -- !Ref and !Sub are CloudFormation-only
# YAML tags and would raise before a single check ran.
doc = yaml.safe_load(open(sys.argv[1]))

failures = []

def check(condition, description):
    if not condition:
        failures.append(description)

resources = doc.get("Resources", {})
params = doc.get("Parameters", {})

# --- the auto-stop, which is the part with money attached --------------------

schedule = resources.get("AutoStopSchedule", {}).get("Properties", {})
check(schedule.get("State") == "ENABLED",
      "the nightly stop is not ENABLED -- a disabled guardrail is a comment")
check("stopInstances" in str(schedule.get("Target", {}).get("Arn", "")),
      "the schedule's target is not the EC2 stopInstances action")
check("StartInstances" not in json.dumps(resources) and "startInstances" not in json.dumps(resources),
      "something in here STARTS an instance on a schedule, which is the "
      "opposite of the point")

role = resources.get("AutoStopRole", {}).get("Properties", {})
statements = [s for p in role.get("Policies", [])
                for s in p.get("PolicyDocument", {}).get("Statement", [])]
check(len(statements) == 1, "the stop role has more than one policy statement")
check(statements and statements[0].get("Action") == "ec2:StopInstances",
      "the stop role can do something other than stop an instance")
def as_text(node):
    """The string behind a scalar or an Fn::Sub, so an ARN can be inspected."""
    if isinstance(node, dict):
        return str(node.get("Fn::Sub", node.get("Fn::Join", node)))
    return str(node)

resource_arn = as_text(statements[0].get("Resource", "")) if statements else ""
check(resource_arn.endswith("${InstanceId}"),
      "the stop role is not scoped to the one instance -- it should not be able "
      "to stop anything else in the account")
check("*" not in resource_arn,
      "the stop role's resource is a wildcard")

# --- the budget ---------------------------------------------------------------

budget = resources.get("MonthlyBudget", {}).get("Properties", {})
notifications = budget.get("NotificationsWithSubscribers", [])
kinds = {(n["Notification"]["NotificationType"], n["Notification"]["Threshold"])
         for n in notifications}
check(("FORECASTED", 100) in kinds,
      "no FORECASTED notification -- an ACTUAL-only budget tells you after the "
      "spend has happened, which on a monthly budget can be three weeks late")
check(any(t < 100 for _, t in kinds),
      "nothing warns below 100% of the budget")
check(all(n["Subscribers"][0]["SubscriptionType"] == "EMAIL" for n in notifications),
      "a notification goes somewhere other than email")

check(budget.get("Budget", {}).get("TimeUnit") == "MONTHLY",
      "the budget is not monthly")

# Credits are negative cost. A budget that counts them nets to zero on a
# credit-funded account and stays silent until the credit is gone -- the exact
# failure it exists to catch. Both flags must be present and false: absent
# means AWS's default, which includes them.
cost_types = budget.get("Budget", {}).get("CostTypes", {})
check(cost_types.get("IncludeCredit") is False,
      "the budget counts credits, so on a credit-funded account it sees $0 and never alerts")
check(cost_types.get("IncludeRefund") is False,
      "the budget counts refunds, which net real spend down the same way credits do")

# Optional, because one budget covers the whole account -- but optional must
# not mean the auto-stop goes with it.
check(resources.get("MonthlyBudget", {}).get("Condition") == "WantBudget",
      "the budget is not conditional on CreateBudget")
check(params.get("CreateBudget", {}).get("Default") == "false",
      "CreateBudget should default to false: this account already has a budget")
check("Condition" not in resources.get("AutoStopSchedule", {})
      and "Condition" not in resources.get("AutoStopRole", {}),
      "the auto-stop is conditional -- it must exist whatever is chosen for the budget")

default_limit = params.get("MonthlyBudgetUsd", {}).get("Default")
check(isinstance(default_limit, (int, float)) and default_limit <= 25,
      f"the default budget is {default_limit}; on a $100 credit a ceiling that "
      "high fires too late to matter")

# --- the schedule's own shape -------------------------------------------------

cron = params.get("AutoStopCron", {}).get("Default", "")
check(cron.startswith("cron(") and cron.endswith(")"),
      f"the default schedule {cron!r} is not a cron() expression")

check(params.get("InstanceId", {}).get("AllowedPattern", "").startswith("^i-"),
      "InstanceId is not constrained to look like an instance id, so a typo "
      "becomes a stack that deploys and never stops anything")

# ------------------------------------------------------------------------------

if failures:
    print(f"{len(failures)} problem(s):")
    for f in failures:
        print(f"  - {f}")
    raise SystemExit(1)

print("OK: the guardrails declare what they claim to.")
PY
