#!/usr/bin/env python3
"""Small, dependency-free CI telemetry collector for BookWave."""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

OUT = Path("ci-telemetry/metrics.json")


def _read(path: str) -> str:
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _task_counts(text: str) -> dict[str, int | None]:
    match = re.findall(
        r"(\d+) actionable tasks:\s*(?:(\d+) executed)?(?:,\s*(\d+) from cache)?(?:,\s*(\d+) up-to-date)?",
        text,
    )
    if not match:
        return {"actionable": None, "executed": None, "from_cache": None, "up_to_date": None}
    actionable, executed, cached, up_to_date = match[-1]
    return {
        "actionable": int(actionable),
        "executed": int(executed or 0),
        "from_cache": int(cached or 0),
        "up_to_date": int(up_to_date or 0),
    }


def _tests() -> dict[str, int]:
    tests = failures = errors = 0
    for path in Path(".").glob("**/build/test-results/**/TEST-*.xml"):
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            continue
        tests += int(root.attrib.get("tests", 0))
        failures += int(root.attrib.get("failures", 0))
        errors += int(root.attrib.get("errors", 0))
    return {"tests": tests, "failures": failures, "errors": errors}


def main() -> int:
    log_paths = sys.argv[1:] or ["ci-diagnostics/gradle-verify.log"]
    log = "\n".join(_read(path) for path in log_paths)
    metrics = {
        "schema_version": 1,
        "run_id": os.getenv("FORGEJO_RUN_ID"),
        "commit": os.getenv("BOOKWAVE_CI_SHA") or os.getenv("FORGEJO_SHA"),
        "depth": os.getenv("BOOKWAVE_CI_DEPTH", "unknown"),
        "profile": os.getenv("BOOKWAVE_CI_PROFILE", "unknown"),
        "workers": int(os.getenv("GRADLE_MAX_WORKERS", "0") or 0),
        "cache_hit": os.getenv("BOOKWAVE_CACHE_HIT", ""),
        "cache_matched_key": os.getenv("BOOKWAVE_CACHE_KEY", ""),
        "gradle_tasks": _task_counts(log),
        "tests": _tests(),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(metrics, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(OUT.read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
