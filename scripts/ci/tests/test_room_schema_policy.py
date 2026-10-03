"""Run the workflow's schema check against real Git history, including stacked PRs."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[3] / ".github/workflows/pull-request.yml"


def schema_script():
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    start = lines.index("      - name: Committed Room schemas are immutable")
    run = next(i for i in range(start, len(lines)) if lines[i] == "        run: |")
    body = []
    for line in lines[run + 1:]:
        if line and not line.startswith("          "):
            break
        body.append(line[10:])
    return "\n".join(body) + "\n"


class RoomSchemaPolicyTest(unittest.TestCase):
    def check_schema(self, change):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "ci@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.name", "CI"], cwd=root, check=True)
            schema = root / "core/database/schemas/fixture/1.json"
            schema.parent.mkdir(parents=True)
            schema.write_text('{"version": 1}\n', encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "released schema"], cwd=root, check=True)
            base = "refs/remotes/bookwave-base/main"
            subprocess.run(["git", "update-ref", base, "HEAD"], cwd=root, check=True)

            if change == "modify":
                schema.write_text('{"version": 1, "changed": true}\n', encoding="utf-8")
            elif change == "delete":
                schema.unlink()
            elif change == "add":
                (schema.parent / "2.json").write_text('{"version": 2}\n', encoding="utf-8")
            else:
                (root / "source.kt").write_text("// source change\n", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "candidate"], cwd=root, check=True)
            script = root / "schema-check.sh"
            script.write_text(schema_script(), encoding="utf-8")
            return subprocess.run(
                ["bash", script.as_posix()],
                cwd=root,
                env={
                    **os.environ,
                    "GITHUB_BASE_REF": "feature/parent",
                    "COMPARISON_BASE": base,
                },
                text=True,
                capture_output=True,
            )

    def test_stacked_pr_uses_the_fetched_canonical_comparison(self):
        result = self.check_schema("source")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("No committed Room schema was modified.", result.stdout)

    def test_modifying_a_released_schema_is_rejected(self):
        result = self.check_schema("modify")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("A committed Room schema was modified or deleted.", result.stdout)

    def test_deleting_a_released_schema_is_rejected(self):
        result = self.check_schema("delete")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("A committed Room schema was modified or deleted.", result.stdout)

    def test_a_new_schema_version_is_allowed(self):
        result = self.check_schema("add")
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
