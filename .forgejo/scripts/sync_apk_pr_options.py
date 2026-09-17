#!/usr/bin/env python3
"""Regenerate Forgejo Build APK workflow-dispatch choices from open same-repository PRs."""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

APK_WORKFLOW = Path(".forgejo/workflows/apk.yml")
FALLBACK = "Use selected branch/ref"
BEGIN = "# BEGIN GENERATED OPEN PR OPTIONS"
END = "# END GENERATED OPEN PR OPTIONS"
BOT_BRANCH = "mcp/apk-pr-picker"


def option_lines(prs: list[dict[str, object]], repository: str) -> str:
    same_repo: list[tuple[int, str]] = []
    for pr in prs:
        head = pr.get("head")
        if not isinstance(head, dict):
            continue
        repo = head.get("repo")
        if not isinstance(repo, dict) or repo.get("full_name") != repository:
            continue
        number = pr.get("number", pr.get("index"))
        branch = head.get("ref")
        if (
            isinstance(number, int)
            and isinstance(branch, str)
            and branch
            and branch != BOT_BRANCH
        ):
            same_repo.append((number, branch))

    choices = [f"#{number} — {branch}" for number, branch in sorted(same_repo, reverse=True)]
    choices.append(FALLBACK)
    return "\n".join(f"          - {json.dumps(choice, ensure_ascii=False)}" for choice in choices)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: sync_apk_pr_options.py <open-prs.json>")

    repository = os.environ.get("FORGEJO_REPOSITORY") or os.environ.get("GITHUB_REPOSITORY", "")
    if not repository:
        raise SystemExit("FORGEJO_REPOSITORY is required")

    prs = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if not isinstance(prs, list):
        raise SystemExit("open PR payload must be a JSON array")

    text = APK_WORKFLOW.read_text(encoding="utf-8")
    generated = option_lines(prs, repository)
    pattern = re.compile(
        rf"^(?P<indent>\s*){re.escape(BEGIN)}\n.*?^(?P=indent){re.escape(END)}$",
        re.MULTILINE | re.DOTALL,
    )
    replacement = f"          {BEGIN}\n{generated}\n          {END}"
    updated, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"Could not update generated PR choices; expected one marker block, found {count}.")

    APK_WORKFLOW.write_text(updated, encoding="utf-8")


if __name__ == "__main__":
    main()
