#!/usr/bin/env python3
"""Cheap BookWave architectural contracts; run in preflight without Gradle or extra dependencies.

This is a dependency/import guard, not a semantic Kotlin analyzer. See module-boundaries.md.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

INCLUDE = re.compile(r'\binclude\s*\(\s*["\'](:[A-Za-z0-9_:]+)["\']\s*\)')
PROJECT = re.compile(
    r'\bprojects\.([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)'
    r'|\bproject\s*\(\s*["\'](:[A-Za-z0-9_:]+)["\']\s*\)'
)
IMPORT = re.compile(r'(?m)^\s*import\s+([A-Za-z_][\w.]*(?:\.\*)?)')

CORE_BASE = {":core:model", ":core:common"}
CORE_IMPL = CORE_BASE | {":core:database", ":core:datastore", ":core:network"}
TESTING = {":core:testing"}


def without_comments(source: str) -> str:
    # Preserve newline counts for GitHub source annotations. Gradle block comments may
    # contain illustrative dependency expressions, but must never create false positives.
    source = re.sub(
        r"/\*.*?\*/",
        lambda match: "\n" * match.group().count("\n"),
        source,
        flags=re.DOTALL,
    )
    return re.sub(r"(?m)^[ \t]*//[^\n]*", "", source)


def allowed(module: str) -> set[str] | None:
    if module == ":app" or module == ":benchmark":
        return None  # Composition root and its benchmark target.
    if module == ":core:model":
        return set()
    if module == ":core:common":
        return {":core:model", ":core:testing"}
    if module.startswith(":core:"):
        return CORE_BASE | TESTING
    if module == ":domain":
        return CORE_BASE | TESTING
    if module.startswith(":data:"):
        return CORE_IMPL | {":domain"} | TESTING
    if module == ":playback":
        return {":domain", ":core:network"} | CORE_BASE | TESTING
    return set()  # A new module must explicitly declare its permitted edges.


def forbidden_player_import(symbol: str) -> bool:
    return (
        symbol.startswith("androidx.media3.exoplayer.")
        or symbol == "androidx.media3.session.MediaSession"
        or symbol.startswith("androidx.media3.session.MediaSession.")
        or symbol == "androidx.media3.session.MediaLibraryService"
        or symbol.startswith("androidx.media3.session.MediaLibraryService.")
    )


def audit(root: Path) -> tuple[list[tuple[str, int, str]], int, int]:
    errors: list[tuple[str, int, str]] = []
    settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    modules = sorted(set(INCLUDE.findall(without_comments(settings))))
    source_files = 0

    if not modules:
        errors.append(("settings.gradle.kts", 1, "No included Gradle modules found"))
        return errors, 0, 0

    for module in modules:
        path = module.lstrip(":").replace(":", "/")
        gradle_path = f"{path}/build.gradle.kts"
        build_file = root / gradle_path
        if not build_file.is_file():
            errors.append((gradle_path, 1, f"{module}: Gradle build file missing"))
            continue
        build = without_comments(build_file.read_text(encoding="utf-8"))
        permitted = allowed(module)
        for match in PROJECT.finditer(build):
            target = ":" + match.group(1).replace(".", ":") if match.group(1) else match.group(2)
            if permitted is not None and target not in permitted:
                errors.append((
                    gradle_path, build.count("\n", 0, match.start()) + 1,
                    f"{module} must not depend on {target}; allowed: {', '.join(sorted(permitted)) or '(none)'}",
                ))

        if module != ":playback":
            for match in re.finditer(r'\blibs\.androidx\.media3\.exoplayer\b', build):
                errors.append((
                    gradle_path, build.count("\n", 0, match.start()) + 1,
                    f"{module} must not declare Media3 ExoPlayer; :playback owns the player",
                ))

        kotlin_root = root / path / "src" / "main"
        if not kotlin_root.exists():
            continue
        for kt in sorted(kotlin_root.rglob("*.kt")):
            source_files += 1
            relative = kt.relative_to(root).as_posix()
            source = without_comments(kt.read_text(encoding="utf-8"))
            for match in IMPORT.finditer(source):
                symbol = match.group(1)
                line = source.count("\n", 0, match.start()) + 1
                if module != ":playback" and forbidden_player_import(symbol):
                    errors.append((
                        relative, line,
                        f"{module} imports {symbol}; Media3 player/session ownership belongs to :playback",
                    ))
                if module == ":app" and "/feature/" in relative and symbol.startswith((
                    "com.example.shelfplayer.core.database.",
                    "com.example.shelfplayer.core.datastore.",
                    "com.example.shelfplayer.core.network.",
                )):
                    errors.append((
                        relative, line,
                        "Compose feature packages must use repository/domain contracts, not database/network/Proto internals",
                    ))

    return errors, len(modules), source_files


def main() -> int:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    findings, modules, files = audit(repo)
    for path, line, message in findings:
        print(f"::error file={path},line={line}::{message}")
    print(f"Architecture boundaries: {modules} Gradle modules, {files} production Kotlin files, {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
