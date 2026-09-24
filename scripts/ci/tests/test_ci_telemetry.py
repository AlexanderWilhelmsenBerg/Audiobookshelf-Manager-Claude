import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "ci-telemetry.py"


class CiTelemetryTest(unittest.TestCase):
    def test_parses_gradle_task_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            log = root / "gradle.log"
            log.write_text("1121 actionable tasks: 506 executed, 157 from cache, 458 up-to-date\n")
            env = {
                **os.environ,
                "BOOKWAVE_CI_DEPTH": "standard",
                "BOOKWAVE_CI_PROFILE": "balanced",
                "GRADLE_MAX_WORKERS": "4",
            }
            subprocess.run(["python3", str(SCRIPT), str(log)], cwd=root, check=True, env=env)
            data = json.loads((root / "ci-telemetry/metrics.json").read_text())
            self.assertEqual(data["gradle_tasks"]["actionable"], 1121)
            self.assertEqual(data["gradle_tasks"]["executed"], 506)
            self.assertEqual(data["profile"], "balanced")
            self.assertEqual(data["workers"], 4)


if __name__ == "__main__":
    unittest.main()
