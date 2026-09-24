"""Build a compact PR verification summary from Gradle/Android reports."""

from __future__ import annotations

import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(".")
DIAG = Path("ci-diagnostics")
QUICK_LOG = DIAG / "gradle-quick.log"
GRADLE_LOG = DIAG / "gradle-verify.log"
SUMMARY = DIAG / "summary.md"
ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*m")


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
    if outcome == "partial":
        return "⚠️"
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
        for raw_line in read_text(path).splitlines():
            line = ANSI_ESCAPE.sub("", raw_line).strip()
            # Plain ktlint reports append per-rule summary lines after the real
            # path:line:column findings. Count/report only concrete locations.
            if re.search(r"\.kt:\d+:\d+:", line):
                findings.append(line)
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

        root = tree.getroot()
        located = False
        for file_node in root.iter("file"):
            file_name = file_node.attrib.get("name", "unknown")
            for error in file_node.findall("error"):
                located = True
                line = error.attrib.get("line", "?")
                column = error.attrib.get("column", "?")
                source = error.attrib.get("source", "detekt")
                message = error.attrib.get("message", "")
                findings.append(
                    f"{file_name}:{line}:{column}: {source}: {message}".strip()
                )

        # Defensive fallback for a non-Checkstyle Detekt XML shape.
        if not located:
            for error in root.iter("error"):
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
    gradle_log = "\n".join(
        text for text in (read_text(QUICK_LOG), read_text(GRADLE_LOG)) if text
    )

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

    depth = os.environ.get("DEPTH", "standard")
    setup = os.environ.get("SETUP_OUTCOME", "")
    quick = os.environ.get("QUICK_OUTCOME", "")
    quick_exit = os.environ.get("QUICK_EXIT", "")
    verify = os.environ.get("VERIFY_OUTCOME", "")
    verify_exit = os.environ.get("VERIFY_EXIT", "")
    schema = os.environ.get("SCHEMA_OUTCOME", "")
    dependencies = os.environ.get("DEPENDENCY_OUTCOME", "")
    target_label = os.environ.get("TARGET_LABEL") or "branch verification"
    workflow_source = os.environ.get("WORKFLOW_SOURCE") or "unknown"

    compile_failed = bool(compile_errors or "compile" in failed_task_text)

    test_state = state_for_reports(
        failures=bool(failed_tests or test_failures or test_errors),
        reports=test_reports,
        verify=verify,
    )
    if compile_failed and test_state == "reported":
        test_state = "partial"
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
    if quick != "success":
        failed_labels.append("Quick KtLint")
    if ktlint_findings or "ktlint" in failed_task_text:
        failed_labels.append("ktlint")

    if depth != "quick":
        if failed_tests or test_failures or test_errors or re.search(r":test\w*", failed_task_text):
            failed_labels.append("tests")
        if detekt_findings or "detekt" in failed_task_text:
            failed_labels.append("detekt")
        if lint_errors or "lint" in failed_task_text:
            failed_labels.append("Android Lint")
        if compile_failed:
            failed_labels.append("compile")
        if verify != "success" and not any(
            label in failed_labels for label in ("tests", "detekt", "Android Lint", "compile", "ktlint")
        ):
            failed_labels.append("verifyDebug")
        if schema != "success":
            failed_labels.append("Room schema")
        if dependencies not in {"success", "", "skipped"}:
            failed_labels.append("dependencies")
    failed_labels = unique(failed_labels)

    test_details = f"{test_total} tests; {test_failures + test_errors} failed"
    if compile_failed and test_reports:
        test_details += "; partial — compile failure can block downstream test tasks"

    if depth == "quick":
        verify_row = ("verifyDebug", "skipped", "not required for Quick")
        test_row = ("Unit tests", "skipped", "not required for Quick")
        detekt_row = ("detekt", "skipped", "not required for Quick")
        lint_row = ("Android Lint", "skipped", "not required for Quick")
        schema_row = ("Room schema", "skipped", "generated/current check belongs to Standard")
        dependency_row = ("Dependency resolution", "skipped", "not required for Quick")
    else:
        verify_row = ("verifyDebug", verify, "exit " + (verify_exit or "n/a"))
        test_row = ("Unit tests", test_state, test_details)
        detekt_row = ("detekt", detekt_state, f"{len(detekt_findings)} finding(s)")
        lint_row = (
            "Android Lint",
            lint_state,
            f"{lint_errors} error(s), {lint_warnings} warning(s), {lint_hints} hint(s)",
        )
        schema_row = ("Room schema", schema, "working tree matches committed schemas")
        dependency_row = (
            "Dependency resolution",
            dependencies if dependencies else "skipped",
            "debugRuntimeClasspath" if dependencies == "success" else "not required unless dependency inputs changed or depth is Intensive",
        )

    rows = [
        ("Android environment", setup, "scripts/codex/setup.sh"),
        ("Quick KtLint", quick, "exit " + (quick_exit or "n/a")),
        verify_row,
        test_row,
        ("ktlint", ktlint_state, f"{len(ktlint_findings)} finding(s)"),
        detekt_row,
        lint_row,
        schema_row,
        dependency_row,
    ]

    lines = [
        f"## CI · {target_label} · consolidated diagnostics · wf:{workflow_source}",
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

    if compile_failed:
        lines.extend(
            [
                "",
                "### Coverage completeness",
                "- Compilation failed, so Gradle could not execute every downstream task that depends on compiled sources.",
                "- Test totals above are partial evidence, not proof that the complete unit-test suite ran.",
                "- Independent ktlint, Detekt, Android Lint, schema, and dependency results are still reported when produced.",
            ]
        )

    if failed_labels:
        lines.extend(
            [
                "",
                "### Failure diagnostics",
                "- ci-diagnostics: summary plus full verifyDebug/dependency logs.",
                "- quality-reports: packaged ktlint, Detekt, Android Lint and test reports when Quick or deep verification fails.",
                "- room-schemas: uploaded only when the Room schema check fails.",
                "- dependency-report: uploaded only when dependency resolution fails.",
            ]
        )
    else:
        lines.extend(
            [
                "",
                "All required checks passed. No diagnostic artifacts are uploaded for successful PR verification.",
            ]
        )

    summary = "\n".join(lines) + "\n"
    SUMMARY.write_text(summary, encoding="utf-8")
    print(summary)

    if failed_labels:
        description = "Failed: " + ", ".join(failed_labels)
    else:
        description = f"BookWave {depth} verification passed"
    write_status_description(description)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
