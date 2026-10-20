#!/usr/bin/env python3
"""Advisory Kotlin complexity trend report. No extra dependency or new quality gate.

Measure source size, branch-token pressure, and mutable-declaration pressure.
These are transparent file-level *proxies*, NOT Detekt cognitive/cyclomatic complexity
or proof of state ownership. Existing Detekt remains authoritative for those rules.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WATCHED = (
    "playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt",
    "playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackController.kt",
    "playback/src/main/kotlin/com/example/shelfplayer/playback/SleepTimerController.kt",
    "data/library/src/main/kotlin/com/example/shelfplayer/data/library/DefaultLibraryRepository.kt",
    "app/src/main/kotlin/com/example/shelfplayer/feature/home/HomeScreen.kt",
)
MASK = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')
BRANCH = re.compile(r'\b(?:if|when|for|while|catch)\b|&&|\|\|')
MUTABLE = re.compile(r'\b(?:var|MutableStateFlow|mutableStateOf)\b')


def production(path: str) -> bool:
    return (
        path.endswith(".kt")
        and "/src/" in path
        and any(f"/src/{part}/" in path for part in ("main", "debug", "release", "benchmark"))
        and "/build/" not in path
    )


def metrics(source: str) -> dict[str, int]:
    masked = MASK.sub(lambda match: "\n" * match.group().count("\n") + " ", source)
    return {
        "nonblank_code_lines": sum(bool(line.strip()) for line in masked.splitlines()),
        "branch_tokens": len(BRANCH.findall(masked)),
        "mutable_tokens": len(MUTABLE.findall(masked)),
    }


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", *args], cwd=root, check=True, capture_output=True)
    return result.stdout


def snapshot(root: Path, path: str, revision: str | None) -> dict[str, int]:
    if revision is None:
        file = root / path
        source = file.read_text(encoding="utf-8") if file.is_file() else ""
    else:
        # Optional files are absent at the comparison commit; this is a valid zero baseline.
        proc = subprocess.run(
            ["git", "show", f"{revision}:{path}"], cwd=root, capture_output=True
        )
        if proc.returncode:
            if git(root, "cat-file", "-e", f"{revision}^{{commit}}") is None:
                raise RuntimeError("invalid comparison base")
            source = ""
        else:
            source = proc.stdout.decode("utf-8")
    return metrics(source)


def growth_reason(before: dict[str, int], after: dict[str, int]) -> str | None:
    old = before["nonblank_code_lines"]
    delta = after["nonblank_code_lines"] - old
    branch = after["branch_tokens"] - before["branch_tokens"]
    if old == 0 and after["nonblank_code_lines"] >= 300:
        return "new production file is 300+ code lines"
    if old >= 250 and delta >= 30 and delta >= old * 0.04:
        return "large file grew by at least 30 lines and 4%"
    if old >= 150 and branch >= 5:
        return "existing file gained 5+ branch tokens"
    return None


def analyze(root: Path, base: str) -> dict:
    # Refs must be actual commits, and compare-base must precede the checked-out head.
    git(root, "rev-parse", "--verify", f"{base}^{{commit}}")
    git(root, "merge-base", "--is-ancestor", base, "HEAD")
    head = git(root, "rev-parse", "HEAD").decode().strip()
    files = [
        path.decode("utf-8") for path in git(root, "ls-files", "-z").split(b"\0")
        if path and production(path.decode("utf-8"))
    ]
    changed = [
        path.decode("utf-8") for path in git(root, "diff", "--name-only", "-z", base, head).split(b"\0")
        if path and production(path.decode("utf-8"))
    ]
    current = {path: snapshot(root, path, None) for path in files}
    watched = [
        {
            "path": path,
            "baseline": snapshot(root, path, base),
            "current": current.get(path, metrics("")),
        }
        for path in WATCHED
    ]
    modifications = []
    for path in sorted(set(changed)):
        before = snapshot(root, path, base)
        after = current.get(path, metrics(""))
        modifications.append({
            "path": path,
            "baseline": before,
            "current": after,
            "delta_lines": after["nonblank_code_lines"] - before["nonblank_code_lines"],
            "delta_branch_tokens": after["branch_tokens"] - before["branch_tokens"],
            "delta_mutable_tokens": after["mutable_tokens"] - before["mutable_tokens"],
            "advisory": growth_reason(before, after),
        })
    return {
        "schema": 1,
        "comparison_base": git(root, "rev-parse", base).decode().strip(),
        "head": head,
        "production_files": len(files),
        "largest": sorted(
            ({"path": path, **stat} for path, stat in current.items()),
            key=lambda value: (-value["nonblank_code_lines"], value["path"]),
        )[:12],
        "watched": watched,
        "changed": modifications,
        "advisory_count": sum(bool(entry["advisory"]) for entry in modifications),
    }


def markdown(report: dict) -> str:
    lines = [
        "### BookWave Kotlin complexity trends — advisory",
        "",
        f"Comparison: \`{report['comparison_base'][:12]}\` → \`{report['head'][:12]}\`; "
        f"{report['production_files']} tracked production Kotlin files.",
        "",
        "**Existing complexity is not a failure.** Branch and mutable tokens are inexpensive",
        "heuristics, not Detekt complexity scores or a count of independent state owners.",
        "The existing Detekt checks still enforce real configured rules.",
        "",
        "#### Largest current files (nonblank code lines)",
        "| File | Lines | Branch tokens | Mutable tokens |",
        "| --- | ---: | ---: | ---: |",
    ]
    for item in report["largest"]:
        lines.append(
            f"| \`{item['path']}\` | {item['nonblank_code_lines']} | "
            f"{item['branch_tokens']} | {item['mutable_tokens']} |"
        )
    lines.extend([
        "",
        "#### Architectural watchlist (baseline → head)",
        "| File | Code lines | Branch tokens |",
        "| --- | ---: | ---: |",
    ])
    for item in report["watched"]:
        left, right = item["baseline"], item["current"]
        lines.append(
            f"| \`{item['path']}\` | {left['nonblank_code_lines']} → "
            f"{right['nonblank_code_lines']} | {left['branch_tokens']} → "
            f"{right['branch_tokens']} |"
        )
    lines.extend([
        "",
        f"#### Changed production files: {len(report['changed'])}; "
        f"growth advisories: {report['advisory_count']}",
        "| File | Δ code lines | Δ branches | Δ mutable tokens | Review signal |",
        "| --- | ---: | ---: | ---: | --- |",
    ])
    for item in sorted(report["changed"], key=lambda row: (-row["delta_lines"], row["path"]))[:30]:
        lines.append(
            f"| \`{item['path']}\` | {item['delta_lines']:+d} | "
            f"{item['delta_branch_tokens']:+d} | {item['delta_mutable_tokens']:+d} | "
            f"{item['advisory'] or '—'} |"
        )
    if not report["changed"]:
        lines.append("| No changed production Kotlin files | — | — | — | — |")
    if len(report["changed"]) > 30:
        lines.append("Only the first 30 changed files appear here; the JSON artifact retains all.")
    lines.extend([
        "",
        "Growth advisories require human review; they **never block** a PR in Phase 1B.",
        "Refactoring may reduce file size while increasing coupling: inspect ownership,",
        "dependencies, and behavior before treating a change as an improvement.",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="SHA emitted by classify-changes.sh")
    parser.add_argument("--output", default="ci-architecture")
    args = parser.parse_args()
    report = analyze(ROOT, args.base)
    output = ROOT / args.output
    output.mkdir(parents=True, exist_ok=True)
    (output / "complexity-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (output / "complexity-report.md").write_text(markdown(report), encoding="utf-8")
    print(
        f"Complexity trends: {report['production_files']} files; "
        f"{len(report['changed'])} modified; {report['advisory_count']} advisory signal(s). "
        "Informational only."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
