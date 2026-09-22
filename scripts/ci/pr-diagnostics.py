"""Build a compact PR verification summary from Gradle/Android reports."""

from __future__ import annotations

import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(".")
DIAG = Path("ci-diagnostics")
GRADLE_LOG = DIAG / "gradle-verify.log"
SUMMARY = DIAG / "summary.md"


def unique(items: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def icon(outcome: str) -> str:
    if outcome == "success":
        return "✅"
    if outcome in {"", "skipped", "reported"}:
        return "⚪"
    return "❌"


def safe_int(value: str | None) -> int:
    try:
        return int(float(value or "0"))
    except ValueError:
        return 0


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def collect_test_results() -> tuple[int, int, int, list[str], int]:
    total = failures = errors = 0
    failed_tests: list[str] = []
    parsed_reports = 0
    for path in ROOT.glob("**/build/test-results/**/*.xml"):
        try:
            tree = ET.parse(path)
        except (ET.ParseError, OSError):
            continue
        parsed_reports += 1
        suite = tree.getroot()
        if suite.tag == "testsuite":
            total += safe_int(suite.attrib.get("tests"))
            failures += safe_int(suite.attrib.get("failures"))
            errors += safe_int(suite.attrib.get("errors"))
        for case in suite.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                cls = case.attrib.get("classname", "")
                name = case.attrib.get("name", "unknown test")
                failed_tests.append((cls + "." + name).strip("."))
    return total, failures, errors, unique(failed_tests), parsed_reports


def collect_ktlint() -> tuple[list[str], int]:
    findings: list[str] = []
    reports = 0
    for path in ROOT.glob("**/build/reports/ktlint/**/*.txt"):
        reports += 1
        text = read_text(path).strip()
        if text:
            findings.extend(line.strip() for line in text.splitlines() if line.strip())
    return unique(findings), reports


def collect_lint() -> tuple[int, int, int, list[str], int]:
    errors = warnings = hints = 0
    findings: list[str] = []
    reports = 0
    summary_re = re.compile(
        r"(\d+) errors?, (\d+) warnings?(?:, (\d+) hints?)?",
        re.IGNORECASE,
    )
    for path in ROOT.glob("**/build/reports/lint-results-*.txt"):
        reports += 1
        text = read_text(path)
        match = summary_re.search(text)
        if match:
            errors += safe_int(match.group(1))
            warnings += safe_int(match.group(2))
            hints += safe_int(match.group(3))
        for line in text.splitlines():
            if ": Error:" in line or ": Warning:" in line:
                findings.append(line.strip())
    return errors, warnings, hints, unique(findings), reports


def collect_detekt() -> tuple[list[str], int]:
    findings: list[str] = []
    reports = 0
    for path in ROOT.glob("**/build/reports/detekt/*.xml"):
        reports += 1
        try:
            tree = ET.parse(path)
        except (ET.ParseError, OSError):
            continue
        for error in tree.getroot().iter("error"):
            source = error.attrib.get("source", "detekt")
            message = error.attrib.get("message", "")
            findings.append((source + ": " + message).strip())
    return unique(findings), reports


def state_for_reports(*, failures: bool, reports: int, verify: str) -> str:
    if failures:
        return "failure"
    if reports and verify == "success":
        return "success"
    if reports:
        return "reported"
    return "skipped"


def append_section(lines: list[str], title: str, findings: list[str], limit: int) -> None:
    if not findings:
        return
    lines.extend(["", f"### {title}"])
    for finding in findings[:limit]:
        lines.append("- " + finding)
    if len(findings) > limit:
        lines.append(f"- …and {len(findings) - limit} more")


def write_status_description(description: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT") or os.environ.get("FORGEJO_OUTPUT")
    if not output_path:
        return
    with Path(output_path).open("a", encoding="utf-8") as output:
        output.write("status_description=" + description[:220] + "\n")


def main() -> int:
    DIAG.mkdir(parents=True, exist_ok=True)
    gradle_log = read_text(GRADLE_LOG)

    failed_tasks = unique(re.findall(r"Execution failed for task '([^']+)'", gradle_log))
    failed_task_lines = unique(
        re.findall(r"^> Task (.+?) FAILED$", gradle_log, flags=re.MULTILINE)
    )
    failed_task_text = " ".join(failed_tasks + failed_task_lines).lower()

    compile_errors = unique(
        [
            line.strip()
            for line in gradle_log.splitlines()
            if re.search(r"(^|\s)(e: |error: )", line.strip(), flags=re.IGNORECASE)
        ]
    )[:20]

    test_total, test_failures, test_errors, failed_tests, test_reports = (
        collect_test_results()
    )
    ktlint_findings, ktlint_reports = collect_ktlint()
    lint_errors, lint_warnings, lint_hints, lint_findings, lint_reports = collect_lint()
    detekt_findings, detekt_reports = collect_detekt()

    setup = os.environ.get("SETUP_OUTCOME", "")
    verify = os.environ.get("VERIFY_OUTCOME", "")
    verify_exit = os.environ.get("VERIFY_EXIT", "")
    schema = os.environ.get("SCHEMA_OUTCOME", "")
    dependencies = os.environ.get("DEPENDENCY_OUTCOME", "")
    apk = os.environ.get("APK_OUTCOME", "")
    target_label = os.environ.get("TARGET_LABEL") or "branch verification"

    test_state = state_for_reports(
        failures=bool(failed_tests or test_failures or test_errors),
        reports=test_reports,
        verify=verify,
    )
    ktlint_state = state_for_reports(
        failures=bool(ktlint_findings),
        reports=ktlint_reports,
        verify=verify,
    )
    detekt_state = state_for_reports(
        failures=bool(detekt_findings),
        reports=detekt_reports,
        verify=verify,
    )
    lint_state = state_for_reports(
        failures=bool(lint_errors),
        reports=lint_reports,
        verify=verify,
    )

    failed_labels: list[str] = []
    if setup != "success":
        failed_labels.append("environment setup")
    if ktlint_findings or "ktlint" in failed_task_text:
        failed_labels.append("ktlint")
    if failed_tests or test_failures or test_errors or re.search(r":test\w*", failed_task_text):
        failed_labels.append("tests")
    if detekt_findings or "detekt" in failed_task_text:
        failed_labels.append("detekt")
    if lint_errors or "lint" in failed_task_text:
        failed_labels.append("Android Lint")
    if compile_errors or "compile" in failed_task_text:
        failed_labels.append("compile")
    if verify not in {"success", ""} and not failed_labels:
        failed_labels.append("verifyDebug")
    if schema != "success":
        failed_labels.append("Room schema")
    if dependencies not in {"success", ""}:
        failed_labels.append("dependencies")
    if apk not in {"success", ""}:
        failed_labels.append("debug APK")
    failed_labels = unique(failed_labels)

    rows = [
        ("Android environment", setup, "scripts/codex/setup.sh"),
        ("verifyDebug", verify, "exit " + (verify_exit or "n/a")),
        (
            "Unit tests",
            test_state,
            f"{test_total} tests; {test_failures + test_errors} failed",
        ),
        ("ktlint", ktlint_state, f"{len(ktlint_findings)} finding(s)"),
        ("detekt", detekt_state, f"{len(detekt_findings)} finding(s)"),
        (
            "Android Lint",
            lint_state,
            f"{lint_errors} error(s), {lint_warnings} warning(s), {lint_hints} hint(s)",
        ),
        ("Room schema", schema, "working tree matches committed schemas"),
        ("Dependency resolution", dependencies, "debugRuntimeClasspath"),
        ("Debug APK", apk, "required when verifyDebug succeeds"),
    ]

    lines = [
        f"## CI · {target_label} · consolidated diagnostics",
        "",
        "| Check | Result | Details |",
        "| --- | --- | --- |",
    ]
    for name, outcome, details in rows:
        lines.append(
            f"| {name} | {icon(outcome)} {outcome or 'not run'} | {details} |"
        )

    append_section(
        lines,
        "Failed Gradle tasks",
        unique(failed_tasks + failed_task_lines),
        30,
    )
    append_section(lines, "Failed tests", failed_tests, 30)
    append_section(lines, "ktlint findings", ktlint_findings, 30)
    append_section(lines, "detekt findings", detekt_findings, 20)
    append_section(lines, "Android Lint findings", lint_findings, 20)
    append_section(lines, "Compiler / build errors", compile_errors, 20)

    lines.extend(
        [
            "",
            "### Artifacts",
            "- ci-diagnostics: this summary plus the full verifyDebug and dependency-resolution logs.",
            "- quality-reports: ktlint, detekt, Android Lint, HTML test reports and raw test-result XML.",
            "- room-schemas: exported Room schemas.",
            "- dependency-report: resolved debug runtime dependency tree when available.",
            "- app-debug: debug APK when the build produced one.",
        ]
    )

    summary = "\n".join(lines) + "\n"
    SUMMARY.write_text(summary, encoding="utf-8")
    print(summary)

    if failed_labels:
        description = "Failed: " + ", ".join(failed_labels)
    else:
        description = "BookWave PR verification passed"
    write_status_description(description)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
