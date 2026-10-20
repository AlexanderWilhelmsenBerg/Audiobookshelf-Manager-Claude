"""Report-only complexity trend contract: baseline comparison, scope, and advisory thresholds."""

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path


TOOL = Path(__file__).resolve().parents[1] / "report_complexity_trends.py"
spec = importlib.util.spec_from_file_location("bookwave_complexity", TOOL)
assert spec and spec.loader
complexity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(complexity)


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root).decode().strip()


class ComplexityTrendTest(unittest.TestCase):
    def test_comments_and_string_literals_do_not_count_as_decisions(self):
        sample = (
            'val label = "if when || var"\n'
            'val longLabel = """while\n'
            'MutableStateFlow"""\n'
            '// for var\n'
            '/* if when catch */\n'
            'if (ready && allowed) { var current = 1 }\n'
        )
        observed = complexity.metrics(sample)
        self.assertEqual(2, observed["branch_tokens"])
        self.assertEqual(1, observed["mutable_tokens"])
        self.assertEqual(3, observed["nonblank_code_lines"])

    def test_production_scope_excludes_tests_and_generated_build(self):
        self.assertTrue(complexity.production("playback/src/main/kotlin/Player.kt"))
        self.assertTrue(complexity.production("app/src/release/kotlin/Service.kt"))
        self.assertFalse(complexity.production("app/src/test/kotlin/ServiceTest.kt"))
        self.assertFalse(complexity.production("app/src/androidTest/kotlin/DeviceTest.kt"))
        self.assertFalse(complexity.production("app/build/generated/src/main/kotlin/Generated.kt"))

    def test_large_existing_growth_is_advisory(self):
        before = dict(nonblank_code_lines=300, branch_tokens=20, mutable_tokens=4)
        after = dict(nonblank_code_lines=332, branch_tokens=21, mutable_tokens=4)
        self.assertIn("large file grew", complexity.growth_reason(before, after))

    def test_small_edit_is_not_advisory(self):
        before = dict(nonblank_code_lines=310, branch_tokens=30, mutable_tokens=2)
        after = dict(nonblank_code_lines=317, branch_tokens=31, mutable_tokens=2)
        self.assertIsNone(complexity.growth_reason(before, after))

    def test_branch_growth_is_advisory_for_existing_file(self):
        before = dict(nonblank_code_lines=190, branch_tokens=8, mutable_tokens=2)
        after = dict(nonblank_code_lines=194, branch_tokens=13, mutable_tokens=2)
        self.assertIn("branch tokens", complexity.growth_reason(before, after))

    def test_new_large_file_is_advisory_but_not_a_failure(self):
        before = dict(nonblank_code_lines=0, branch_tokens=0, mutable_tokens=0)
        after = dict(nonblank_code_lines=305, branch_tokens=0, mutable_tokens=0)
        self.assertIn("new production", complexity.growth_reason(before, after))

    def test_actual_git_baseline_and_deleted_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            git(root, "init", "-q")
            git(root, "config", "user.name", "BookWave Test")
            git(root, "config", "user.email", "test@example.invalid")
            path = root / "playback/src/main/kotlin/PlaybackService.kt"
            path.parent.mkdir(parents=True)
            path.write_text("fun handle() { if (true) return }\n", encoding="utf-8")
            git(root, "add", ".")
            git(root, "commit", "-qm", "baseline")
            base = git(root, "rev-parse", "HEAD")

            path.write_text("fun handle() { if (true && false) return }\nvar count = 1\n",
                            encoding="utf-8")
            git(root, "add", ".")
            git(root, "commit", "-qm", "change")
            report = complexity.analyze(root, base)
            self.assertEqual(1, report["production_files"])
            self.assertEqual(1, len(report["changed"]))
            self.assertEqual(1, report["changed"][0]["delta_lines"])
            self.assertEqual(1, report["changed"][0]["delta_branch_tokens"])
            self.assertEqual(1, report["changed"][0]["delta_mutable_tokens"])
            self.assertEqual(0, report["advisory_count"])
            self.assertIn("advisory", complexity.markdown(report).lower())

            path.unlink()
            git(root, "add", "-A")
            git(root, "commit", "-qm", "delete")
            deleted = complexity.analyze(root, git(root, "rev-parse", "HEAD~1"))
            self.assertEqual(-2, deleted["changed"][0]["delta_lines"])
            self.assertEqual(0, deleted["production_files"])

    def test_report_has_no_blocking_growth_exit_condition(self):
        self.assertNotIn("raise SystemExit(report['advisory_count'])", TOOL.read_text())


if __name__ == "__main__":
    unittest.main()
