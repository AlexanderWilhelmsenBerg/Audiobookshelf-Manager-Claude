#!/usr/bin/env python3
"""Keep Build APK's workflow-dispatch PR picker aligned with open same-repository PRs.

GitHub renders workflow_dispatch choice options directly from static YAML. This script owns the generated
choice list and the small amount of parsing needed to turn a human-readable option such as
"#86 — feature/appearance-inline-expand" back into a PR number.

It deliberately ignores fork PRs: Build APK has access to signing secrets and refuses cross-repository
source for the same reason.
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

APK_WORKFLOW = Path(".github/workflows/apk.yml")
FALLBACK = "Use selected branch/ref"
BEGIN = "# BEGIN GENERATED OPEN PR OPTIONS"
END = "# END GENERATED OPEN PR OPTIONS"


def replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE | re.DOTALL)
    if count != 1:
        raise SystemExit(f"Could not update {label}; expected exactly one match, found {count}.")
    return updated


def option_lines(prs: list[dict[str, object]], repository: str) -> str:
    same_repo: list[tuple[int, str]] = []
    for pr in prs:
        head = pr.get("head")
        if not isinstance(head, dict):
            continue
        repo = head.get("repo")
        if not isinstance(repo, dict) or repo.get("full_name") != repository:
            continue
        number = pr.get("number")
        branch = head.get("ref")
        if isinstance(number, int) and isinstance(branch, str) and branch:
            same_repo.append((number, branch))

    # Newest PR first, with the non-PR escape hatch always at the bottom.
    choices = [f"#{number} — {branch}" for number, branch in sorted(same_repo, reverse=True)]
    choices.append(FALLBACK)
    return "\n".join(f"          - {json.dumps(choice, ensure_ascii=False)}" for choice in choices)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: sync_apk_pr_options.py <open-prs.json>")

    repository = os.environ.get("GITHUB_REPOSITORY", "")
    if not repository:
        raise SystemExit("GITHUB_REPOSITORY is required")

    prs = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if not isinstance(prs, list):
        raise SystemExit("open PR payload must be a JSON array")

    text = APK_WORKFLOW.read_text(encoding="utf-8")
    generated = option_lines(prs, repository)

    # Keep the whole value YAML-quoted. A generated choice starts with '#', and an unquoted '#'
    # preceded by whitespace is a YAML comment marker even when it visually sits inside an Actions
    # expression. Quoting the scalar keeps the expression intact.
    text = replace_once(
        text,
        r"^run-name:[^\n]*$",
        "run-name: \"${{ inputs.pull_request != 'Use selected branch/ref' && format('Build {0} APK · {1}', inputs.variant, inputs.pull_request) || format('Build {0} APK · {1}', inputs.variant, github.ref_name) }}\"",
        "workflow run name",
    )

    picker = (
        "      pull_request:\n"
        "        description: 'Open pull request to build'\n"
        "        type: choice\n"
        "        required: true\n"
        f"        default: '{FALLBACK}'\n"
        "        options:\n"
        f"          {BEGIN}\n"
        f"{generated}\n"
        f"          {END}\n"
    )
    text = replace_once(
        text,
        r"^      (?:pr_number|pull_request):\n.*?(?=^      variant:)",
        picker,
        "pull request picker",
    )

    text = replace_once(
        text,
        r"^  group: [^\n]*inputs\.(?:pr_number|pull_request)[^\n]*$",
        "  group: ${{ github.workflow }}-${{ inputs.pull_request == 'Use selected branch/ref' && github.ref || inputs.pull_request }}-${{ inputs.variant }}",
        "APK concurrency key",
    )

    text = replace_once(
        text,
        r"^          REQUESTED_PR: \$\{\{ inputs\.(?:pr_number|pull_request) \}\}$",
        "          REQUESTED_PR: ${{ inputs.pull_request }}",
        "selected PR environment",
    )

    source_preamble = r'''          if [ "${REQUESTED_PR:-Use selected branch/ref}" != "Use selected branch/ref" ]; then
            NUMBER_PART="${REQUESTED_PR%% *}"
            REQUESTED_PR_NUMBER="${NUMBER_PART#\#}"
            if ! [[ "$REQUESTED_PR_NUMBER" =~ ^[1-9][0-9]*$ ]]; then
              echo "::error::Invalid pull request selection: $REQUESTED_PR"
              exit 1
            fi

'''
    text = replace_once(
        text,
        r"^          if [^\n]*REQUESTED_PR[^\n]*; then\n.*?(?=^            IFS=\$'\\t' read -r NUMBER BRANCH SHA CROSS_REPO)",
        source_preamble,
        "selected PR parser",
    )
    text = text.replace('gh pr view "$REQUESTED_PR" --repo "$GITHUB_REPOSITORY"', 'gh pr view "$REQUESTED_PR_NUMBER" --repo "$GITHUB_REPOSITORY"')

    how_to = """# ## How to get one
#
# GitHub → Actions → **Build APK** → *Run workflow*. Leave **Use workflow from** on `main`, choose an open
# pull request from the generated **Pull request** dropdown, pick debug or release, and run it. Each option
# shows both identities used during testing, for example `#86 — feature/appearance-inline-expand`.
#
# The workflow extracts only the PR number from that label and resolves the PR again through GitHub before
# checkout. The displayed branch is therefore context for the tester, not trusted input. The exact current
# head SHA is what gets compiled. Choose **Use selected branch/ref** only for a deliberate non-PR build.
#
# The companion `sync-apk-pr-options.yml` workflow regenerates this static choice list whenever PRs are
# opened, closed or reopened. Fork PRs are intentionally omitted because this workflow can access signing
# secrets and refuses cross-repository source.
#
# When the build finishes, the APK is on the run's summary page under **Artifacts**. Phones can download it
# directly from that page.
#
"""
    text = replace_once(
        text,
        r"^# ## How to get one\n#\n.*?(?=^# ## Why `workflow_dispatch` only)",
        how_to,
        "Build APK usage documentation",
    )

    APK_WORKFLOW.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
