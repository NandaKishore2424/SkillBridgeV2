#!/usr/bin/env bash
#
# Enforces the fast tier's runtime budget and its zero-skip rule.
#
#   cd skillbridge-backend && mvn clean test
#   ./scripts/check-test-budget.sh
#
# Two checks, and the second is the one that matters:
#
#   1. The fast tier must finish inside FAST_TIER_BUDGET_SECONDS (default 30).
#      A suite that takes 40 minutes gets skipped, then disabled. Measured at
#      4.9s over 108 tests on 2026-09-13, so there is room -- the budget exists
#      to notice the day someone puts a Spring context in the fast tier, which
#      costs seconds, not milliseconds.
#
#   2. The fast tier must skip NOTHING. Nothing in it is conditional, so a skip
#      is either a mistake or a test quietly gating itself -- the defect that let
#      CI report "Tests run: 169, Skipped: 64, BUILD SUCCESS" on every push for
#      the life of the workflow. SuiteTieringTest catches the annotation; this
#      catches the outcome, which is the thing actually worth asserting.
#
# TWO TRAPS IN THE REPORTS THEMSELVES, both met while writing this:
#
#   * Surefire does not clear reports for classes it did not run this time. A
#     target/ left from an earlier configuration still holds their files --
#     including, when this was written, one reading "Skipped: 1" from the tier
#     that no longer runs here, five days stale. Hence the freshness check.
#
#   * The `tests` attribute on <testsuite> is WRONG for classes using @Nested:
#     it reports 0 while the console reports the real number. Four classes here
#     do (JwtService, CorrelationIdFilter, RateLimitingFilter,
#     EnrollmentStatusTransition), and trusting that attribute totalled 65 where
#     Maven said 108 -- a 40% undercount, silently. The <testcase> elements are
#     all present and correct, so they are what gets counted.

set -euo pipefail

BUDGET="${FAST_TIER_BUDGET_SECONDS:-30}"
REPORTS="${1:-skillbridge-backend/target/surefire-reports}"

if [[ ! -d "$REPORTS" ]]; then
    echo "no surefire reports at $REPORTS -- run 'mvn clean test' first" >&2
    exit 2
fi

FAST_TIER_BUDGET_SECONDS="$BUDGET" REPORTS="$REPORTS" python3 - <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET

reports = os.environ["REPORTS"]
budget = float(os.environ["FAST_TIER_BUDGET_SECONDS"])
files = sorted(glob.glob(os.path.join(reports, "TEST-*.xml")))

if not files:
    print(f"no surefire XML reports in {reports} -- run 'mvn clean test' first", file=sys.stderr)
    sys.exit(2)

mtimes = [os.path.getmtime(f) for f in files]
spread = max(mtimes) - min(mtimes)
if spread > 600:
    print(f"FAIL: {reports} holds reports written {spread:.0f}s apart.", file=sys.stderr)
    print("      Surefire leaves reports for classes it did not run this time, so this", file=sys.stderr)
    print("      directory mixes runs and any total from it is fiction.", file=sys.stderr)
    print("      Run 'mvn clean test' and try again.", file=sys.stderr)
    sys.exit(1)

total_time = 0.0
total_tests = 0
skippers = []
rows = []

for f in files:
    root = ET.parse(f).getroot()
    name = root.get("name", os.path.basename(f))
    # <testcase> elements, NOT the `tests` attribute -- see the header.
    cases = root.findall(".//testcase")
    skipped = root.findall(".//testcase/skipped")
    elapsed = float(root.get("time", 0))
    total_tests += len(cases)
    total_time += elapsed
    rows.append((elapsed, len(cases), name))
    if skipped:
        skippers.append((name, len(skipped)))

print(f"fast tier: {total_tests} tests in {len(files)} classes, {total_time:.3f}s (budget {budget:g}s)")

slow = sorted((r for r in rows if r[0] > 1.0), reverse=True)
if slow:
    print("slowest classes over 1s:")
    for elapsed, n, name in slow:
        print(f"  {elapsed:6.2f}s  {n:3d} tests  {name}")

status = 0

if skippers:
    n = sum(c for _, c in skippers)
    print(f"\nFAIL: the fast tier skipped {n} test(s). It must skip none.", file=sys.stderr)
    for name, c in skippers:
        print(f"  {name} ({c} skipped)", file=sys.stderr)
    print("      A skipped test reports as a passing test. If it needs a database,", file=sys.stderr)
    print("      tag it @IntegrationTest so 'mvn verify' owns it.", file=sys.stderr)
    status = 1

if total_time > budget:
    print(f"\nFAIL: fast tier took {total_time:.3f}s against a {budget:g}s budget.", file=sys.stderr)
    print("      Move the slow classes to the integration tier, or make them faster.", file=sys.stderr)
    status = 1

if status == 0:
    print("OK: inside budget, nothing skipped.")
sys.exit(status)
PY
