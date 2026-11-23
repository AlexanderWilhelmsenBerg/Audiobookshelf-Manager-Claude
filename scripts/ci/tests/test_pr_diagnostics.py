import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "pr-diagnostics.py"


class PrDiagnosticsTest(unittest.TestCase):
    def test_identifies_gradle_failures_by_task_name(self):
        cases = (
            ("> Task :app:ktlintCheck FAILED", "ktlint"),
            ("Execution failed for task ':app:runKtlintCheckOverMainSourceSet'.", "ktlint"),
            ("> Task :app:lintDebug FAILED", "Android Lint"),
            ("> Task :lint-tools:testDebugUnitTest FAILED", "tests"),
            ("> Task :app:lintAnalyzeDebug FAILED\n> Task :app:ktlintCheck FAILED", "ktlint, Android Lint"),
            ("> Task :app:detektDebug FAILED", "detekt"),
            ("> Task :app:compileDebugKotlin FAILED", "compile"),
            ("> Task :app:verifyDebug FAILED", "verifyDebug"),
        )
        for log, expected in cases:
            with self.subTest(log=log):
                self.assertEqual(self.status_for(log), f"status_description=Failed: {expected}")

    def status_for(self, log):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            diagnostics = root / "ci-diagnostics"
            diagnostics.mkdir()
            (diagnostics / "gradle-verify.log").write_text(log + "\n", encoding="utf-8")
            output = root / "output.txt"
            subprocess.run(
                [sys.executable, str(SCRIPT)],
                cwd=root,
                check=True,
                capture_output=True,
                env={
                    **os.environ,
                    "DEPTH": "standard",
                    "SETUP_OUTCOME": "success",
                    "QUICK_OUTCOME": "success",
                    "VERIFY_OUTCOME": "failure",
                    "SCHEMA_OUTCOME": "success",
                    "DEPENDENCY_OUTCOME": "skipped",
                    "GITHUB_OUTPUT": str(output),
                    "PYTHONUTF8": "1",
                },
            )
            return output.read_text(encoding="utf-8").strip()
