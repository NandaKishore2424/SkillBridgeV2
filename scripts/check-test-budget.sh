#!/usr/bin/env bash
#
# Enforces the fast tier's runtime budget and its zero-skip rule.
#
#   cd skillbridge-backend && mvn clean test
#   ./scripts/check-test-budget.sh
#
# Two checks, and the second is the one that matters:
#
#   1. The fast tier must finish inside FAST_TIER_BUDGET_SECONDS (default 60).
#      A suite that takes 40 minutes gets skipped, then disabled.
#
#      THE BUDGET IS CALIBRATED ON CI, NOT ON A LAPTOP. It was 30s, from 4.9s
#      over 108 tests measured on the development machine on 2026-09-13, and it
#      was never tested against the hardware that enforces it -- this job had
#      not run in CI at anything like the current size. On 2026-09-20 the same
#      276 tests took:
#
#          13.9s  development machine
#          32.1s  ubuntu-latest, run 35518760368
#          22.2s  ubuntu-latest, run 35519089800
#
#      -- so the first CI run of a green suite failed on the budget alone, with
#      276 passes and no skips. A budget that only the fast machine can meet
#      tells you about the machine.
#
#      Note the last two: the same suite, the same commit's test code, 45%
#      apart. A shared runner's speed varies that much by itself, so the budget
#      has to clear the slow end of that spread and not the average, or it will
#      fail a green suite now and then and teach everyone to re-run CI until it
#      is green -- which is how a real failure gets re-run away.
#
#      60s is roughly twice the slower CI measurement. Re-measure on CI before
#      changing it, and record the numbers here.
#
#      What it is really watching for is a class that costs seconds rather than
#      milliseconds. Note that the tier legitimately contains some already: the
#      @WebMvcTest slices build a Spring context (5.9s for the slowest on CI),
#      and the ArchUnit rules scan the bytecode of the whole project (5.5s).
#      Those are the two shapes to look at first when this number grows.
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

BUDGET="${FAST_TIER_BUDGET_SECONDS:-60}"
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
    print(f"      ({total_tests} tests, {1000 * total_time / max(total_tests, 1):.0f}ms each on average.)",
          file=sys.stderr)
    print("      A CI runner is about 2.3x slower than the development machine, so", file=sys.stderr)
    print("      passing locally does not mean passing here. See the header.", file=sys.stderr)
    status = 1

if status == 0:
    print("OK: inside budget, nothing skipped.")
sys.exit(status)
PY
