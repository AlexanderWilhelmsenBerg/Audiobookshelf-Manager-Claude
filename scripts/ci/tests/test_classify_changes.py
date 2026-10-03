import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "classify-changes.sh"


class ClassifyChangesTest(unittest.TestCase):
    def classify(self, changed_path: str, main_push=False) -> str:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.email", "ci@example.invalid"], cwd=root, check=True)
            subprocess.run(["git", "config", "user.name", "CI"], cwd=root, check=True)
            seed = root / "seed.txt"
            seed.write_text("seed\n")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "base"], cwd=root, check=True)
            base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()

            target = root / changed_path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("change\n")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "change"], cwd=root, check=True)

            comparison = ["HEAD", "HEAD", base] if main_push else [base, "HEAD"]
            return subprocess.check_output(
                ["bash", str(SCRIPT), *comparison],
                cwd=root,
                text=True,
                env={**os.environ, "GITHUB_OUTPUT": ""},
            )

    def test_workflow_is_ci_only(self):
        out = self.classify(".github/workflows/pull-request.yml")
        self.assertIn("build_changed=false", out)
        self.assertIn("ci_infra_changed=true", out)
        self.assertIn("dependency_inputs_changed=false", out)

    def test_ci_image_is_ci_only(self):
        out = self.classify("ci/bookwave-ci/Dockerfile")
        self.assertIn("build_changed=false", out)
        self.assertIn("ci_infra_changed=true", out)
        self.assertIn("dependency_inputs_changed=false", out)

    def test_build_file_requires_rerun_and_dependency_report(self):
        out = self.classify("playback/build.gradle.kts")
        self.assertIn("build_changed=true", out)
        self.assertIn("dependency_inputs_changed=true", out)

    def test_source_change_is_incremental(self):
        out = self.classify("playback/src/main/kotlin/example.kt")
        self.assertIn("build_changed=false", out)
        self.assertIn("ci_infra_changed=false", out)
        self.assertIn("dependency_inputs_changed=false", out)

    def test_main_push_detects_classpath_change_since_previous_main(self):
        out = self.classify("gradle/libs.versions.toml", main_push=True)
        self.assertIn("build_changed=true", out)
        self.assertIn("dependency_inputs_changed=true", out)

    def test_main_source_push_remains_incremental(self):
        out = self.classify("app/src/main/kotlin/example.kt", main_push=True)
        self.assertIn("app/src/main/kotlin/example.kt", out)
        self.assertIn("build_changed=false", out)


if __name__ == "__main__":
    unittest.main()
