#!/usr/bin/env python3
"""Prints the test summary table for test/run-all.sh and exits non-zero if any suite failed."""
import csv
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
reports = os.path.join(root, "test", "reports")


def junit(pattern):
    passed = failed = skipped = 0
    for path in glob.glob(os.path.join(root, pattern)):
        suite = ET.parse(path).getroot()
        tests = int(suite.get("tests", 0))
        bad = int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        skip = int(suite.get("skipped", 0))
        passed += tests - bad - skip
        failed += bad
        skipped += skip
    return (passed, failed, skipped) if passed + failed + skipped else None


def vitest(name):
    path = os.path.join(reports, f"{name}.json")
    if not os.path.exists(path):
        return None
    data = json.load(open(path))
    return (
        data.get("numPassedTests", 0),
        data.get("numFailedTests", 0),
        data.get("numPendingTests", 0) + data.get("numTodoTests", 0),
    )


def playwright():
    path = os.path.join(reports, "e2e.json")
    if not os.path.exists(path):
        return None
    stats = json.load(open(path)).get("stats", {})
    return (stats.get("expected", 0) + stats.get("flaky", 0), stats.get("unexpected", 0), stats.get("skipped", 0))


def database():
    path = os.path.join(reports, "database.summary")
    if not os.path.exists(path):
        return None
    p, f, s = (int(x) for x in open(path).read().split())
    return (p, f, s)


MAVEN_NOISE = (
    "To see the full stack trace",
    "Re-run Maven",
    "For more information",
    "After correcting the problems",
    "mvn <args> -rf",
    "-> [Help",
    "Failed to execute goal",
)


def error_reason(name):
    """Why a suite stopped without (all) test results: the first build errors in its log."""
    log = os.path.join(reports, f"{name}.log")
    if not os.path.exists(log):
        return ["test stack did not start (see test/reports/stack-up.log)"]
    lines = [l.rstrip() for l in open(log, errors="replace")]
    reasons = []
    for line in lines:
        if not line.startswith("[ERROR]"):
            continue
        text = line[len("[ERROR]"):].strip()
        if not text or any(text.startswith(n) for n in MAVEN_NOISE) or text.startswith("Tests run:"):
            continue
        if text not in reasons:
            reasons.append(text)
        if len(reasons) == 3:
            break
    if not reasons:
        reasons = [l.strip() for l in lines if l.strip()][-3:]
    return reasons


def jacoco_line_pct(csv_path):
    if not os.path.exists(csv_path):
        return None
    missed = covered = 0
    with open(csv_path) as fh:
        for row in csv.DictReader(fh):
            missed += int(row["LINE_MISSED"])
            covered += int(row["LINE_COVERED"])
    return 100.0 * covered / (missed + covered) if missed + covered else None


counters = {
    "frontend": lambda: vitest("frontend"),
    "backend": lambda: junit("apps/api/*/target/surefire-reports/TEST-*.xml"),
    "integration": lambda: junit("apps/api/api-bootstrap/target/failsafe-reports/TEST-*.xml"),
    "database": database,
    "api": lambda: vitest("api"),
    "e2e": playwright,
}
labels = {
    "frontend": "Frontend (Vitest + RTL + MSW)",
    "backend": "Backend unit (JUnit)",
    "integration": "Backend integration (Testcontainers)",
    "database": "Database (SQL assertions)",
    "api": "API black-box (HTTP)",
    "e2e": "End-to-end (Playwright)",
}

statuses = []
for line in open(os.path.join(reports, "status")):
    name, code, seconds = line.split()
    statuses.append((name, int(code), int(seconds)))

print()
print("=" * 86)
print(f"{'Suite':<40}{'Result':<9}{'Passed':>8}{'Failed':>8}{'Skipped':>9}{'Time':>9}")
print("-" * 86)
totals = [0, 0, 0]
any_failed = False
errors = []
for name, code, seconds in statuses:
    counts = counters[name]()
    if counts is not None and counts[1] > 0:
        result = "FAIL"
    elif code != 0:
        result = "ERROR"
        errors.append(name)
    else:
        result = "PASS"
    any_failed |= result != "PASS"
    if counts is None:
        cells = f"{'-':>8}{'-':>8}{'-':>9}"
    else:
        cells = f"{counts[0]:>8}{counts[1]:>8}{counts[2]:>9}"
        totals = [t + c for t, c in zip(totals, counts)]
    print(f"{labels[name]:<40}{result:<9}{cells}{seconds:>8}s")
print("-" * 86)
print(f"{'Total':<40}{'FAIL' if any_failed else 'PASS':<9}{totals[0]:>8}{totals[1]:>8}{totals[2]:>9}")
print("=" * 86)
if errors:
    print("ERROR = the suite stopped before its tests finished (build, compile or setup problem),")
    print("        so its counts are missing ('-') or cover only the tests that ran before it stopped.")
    for name in errors:
        print(f"  {labels[name]} (test/reports/{name}.log):")
        for reason in error_reason(name):
            print(f"    {reason[:200]}")
    print()

names = {s[0] for s in statuses}
coverage = []
if "frontend" in names:
    summary = os.path.join(root, "apps/web/coverage/coverage-summary.json")
    if os.path.exists(summary):
        pct = json.load(open(summary))["total"]["lines"]["pct"]
        coverage.append(f"  Frontend  {pct:5.1f}% lines   apps/web/coverage/index.html")
if names & {"backend", "integration"}:
    agg = "apps/api/api-bootstrap/target/site/jacoco-aggregate"
    pct = jacoco_line_pct(os.path.join(root, agg, "jacoco.csv"))
    if pct is not None and "integration" in names:
        coverage.append(f"  Backend   {pct:5.1f}% lines   {agg}/index.html (unit + integration)")
    else:
        for module in sorted(glob.glob(os.path.join(root, "apps/api/*/target/site/jacoco/jacoco.csv"))):
            mod_pct = jacoco_line_pct(module)
            rel = os.path.relpath(os.path.dirname(module), root)
            if mod_pct:
                coverage.append(f"  Backend   {mod_pct:5.1f}% lines   {rel}/index.html (unit only)")
if coverage:
    print("Coverage:")
    print("\n".join(coverage))
print(f"Logs and reports: test/reports/")
print()
print("RESULT: " + ("FAILED" if any_failed else "ALL SUITES PASSED"))
sys.exit(1 if any_failed else 0)
