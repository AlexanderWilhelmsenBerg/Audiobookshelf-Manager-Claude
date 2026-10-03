"""Exercise the workflow's actual cleanup shell without contacting GitHub."""

import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[3] / ".github/workflows/pull-request.yml"


def cleanup_script():
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    start = next(i for i, line in enumerate(lines) if re.match(r"  prune-(?:pr|gradle)-cache:", line))
    run = next(i for i in range(start, len(lines)) if lines[i] == "        run: |")
    body = []
    for line in lines[run + 1:]:
        if line and not line.startswith("          "):
            break
        body.append(line[10:])
    return "\n".join(body) + "\n"


class CacheCleanupTest(unittest.TestCase):
    def prune(self, mode, ref, rows, current_main="current", verified_sha="current"):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "cleanup.sh").write_text(cleanup_script(), encoding="utf-8")
            (root / "entries.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
            (root / "gh").write_text(
                """#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"commits/main"* ]]; then
  printf '%s\\n' "$CURRENT_MAIN"
elif [[ "$*" == *"--method DELETE"* ]]; then
  for arg in "$@"; do
    if [[ "$arg" == repos/*/actions/caches/* ]]; then
      printf '%s\\n' "$arg" >> deleted.txt
    fi
  done
elif [[ "$*" == *"ref=$CACHE_REF"* ]]; then
  cat entries.tsv
else
  echo "Unexpected or unscoped GitHub request" >&2
  exit 9
fi
""",
                encoding="utf-8",
            )
            (root / "gh").chmod(0o755)
            result = subprocess.run(
                ["bash", (root / "cleanup.sh").as_posix()],
                cwd=root,
                env={
                    **os.environ,
                    "PATH": str(root) + os.pathsep + os.environ["PATH"],
                    "GITHUB_REPOSITORY": "test/BookWave",
                    "CACHE_MODE": mode,
                    "CACHE_REF": ref,
                    "VERIFIED_SHA": verified_sha,
                    "CURRENT_MAIN": current_main,
                },
                text=True,
                capture_output=True,
            )
            deleted = root / "deleted.txt"
            ids = [line.rsplit("/", 1)[-1] for line in deleted.read_text().splitlines()] if deleted.exists() else []
            return result, ids

    def test_pr_keeps_newest_generation_of_each_gradle_kind(self):
        result, ids = self.prune("pr", "refs/pull/42/merge", [
            "1\tgradle-build-cache-v2\t2026-10-01",
            "2\tgradle-build-cache-v2\t2026-10-02",
            "3\tgradle-transforms-v2\t2026-10-01",
        ])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["1"], ids)

    def test_main_preserves_other_environments_and_shared_content(self):
        result, ids = self.prune("main", "refs/heads/main", [
            "1\tgradle-home-v2|Linux-X64|verify[env-a]\t2026-10-01",
            "2\tgradle-home-v2|Linux-X64|verify[env-a]\t2026-10-02",
            "3\tgradle-home-v2|Linux-X64|verify[env-b]\t2026-10-01",
            "4\tgradle-home-v2|Linux-X64|release-checks[env-a]\t2026-10-01",
            "5\tgradle-dependencies-v2\t2026-10-01",
            "6\tgradle-dependencies-v2\t2026-10-02",
            "7\tgradle-transforms-v2\t2026-10-01",
            "8\tgradle-transforms-v2\t2026-10-02",
        ])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["1"], ids)

    def test_unrelated_caches_are_preserved(self):
        result, ids = self.prune("pr", "refs/pull/42/merge", [
            "1\tother-tool\t2026-10-01", "2\tother-tool\t2026-10-02",
        ])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], ids)

    def test_superseded_main_run_does_not_prune(self):
        result, ids = self.prune("main", "refs/heads/main", [
            "1\tgradle-home-v2|Linux-X64|verify[env-a]\t2026-10-01",
            "2\tgradle-home-v2|Linux-X64|verify[env-a]\t2026-10-02",
        ], current_main="newer", verified_sha="older")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], ids)

    def test_invalid_scope_fails_without_deleting(self):
        for mode, ref in [("main", "refs/pull/42/merge"), ("pr", "refs/heads/main"), ("fork", "refs/pull/42/merge")]:
            with self.subTest(mode=mode, ref=ref):
                result, ids = self.prune(mode, ref, [
                    "1\tgradle-build-cache-v2\t2026-10-01",
                    "2\tgradle-build-cache-v2\t2026-10-02",
                ])
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], ids)


if __name__ == "__main__":
    unittest.main()
