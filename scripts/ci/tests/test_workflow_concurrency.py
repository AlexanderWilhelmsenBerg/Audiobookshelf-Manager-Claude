"""Regression guard for the merge/PR-close concurrency collision (PR #280)."""

import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[3] / ".github/workflows/pull-request.yml"


class WorkflowConcurrencyTest(unittest.TestCase):
    def test_main_push_and_pr_closed_are_distinct_groups(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        group = next(line.strip() for line in text.splitlines() if line.strip().startswith("group:"))
        # GitHub assigns refs/heads/main to both events after a PR merge.
        self.assertIn("${{ github.workflow }}-${{ github.event_name }}-", group)
        # Closed PRs must be independent from other PRs closed against main.
        self.assertIn("format('pr-{0}', github.event.pull_request.number)", group)
        # Explicit runs must not accidentally supersede an automatic run.
        self.assertIn("format('manual-{0}', github.run_id)", group)


if __name__ == "__main__":
    unittest.main()
