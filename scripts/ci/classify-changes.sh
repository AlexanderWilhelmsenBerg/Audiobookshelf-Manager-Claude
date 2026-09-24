#!/usr/bin/env bash
set -euo pipefail

base="${1:-origin/main}"
head="${2:-HEAD}"

if ! git rev-parse --verify "${base}^{commit}" >/dev/null 2>&1; then
  echo "classify-changes: base ref not found: $base" >&2
  exit 2
fi
if ! git rev-parse --verify "${head}^{commit}" >/dev/null 2>&1; then
  echo "classify-changes: head ref not found: $head" >&2
  exit 2
fi

changed_files="$(git diff --name-only "${base}...${head}")"
build_changed=false
ci_infra_changed=false
dependency_inputs_changed=false

while IFS= read -r path; do
  [ -n "$path" ] || continue
  case "$path" in
    .forgejo/workflows/*|.forgejo/ci/*|scripts/ci/*|scripts/codex/*)
      ci_infra_changed=true
      ;;
  esac

  case "$path" in
    build.gradle.kts|settings.gradle.kts|gradle.properties|gradle/wrapper/*|build-logic/*|buildSrc/*|*/build.gradle.kts)
      build_changed=true
      ;;
  esac

  case "$path" in
    build.gradle.kts|settings.gradle.kts|gradle/libs.versions.toml|gradle/verification-metadata.xml|gradle/wrapper/*|build-logic/*|buildSrc/*|*/build.gradle.kts|*/gradle.lockfile|gradle.lockfile)
      dependency_inputs_changed=true
      build_changed=true
      ;;
  esac
done <<< "$changed_files"

printf '%s\n' "$changed_files"
printf 'build_changed=%s\n' "$build_changed"
printf 'ci_infra_changed=%s\n' "$ci_infra_changed"
printf 'dependency_inputs_changed=%s\n' "$dependency_inputs_changed"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "build_changed=$build_changed"
    echo "ci_infra_changed=$ci_infra_changed"
    echo "dependency_inputs_changed=$dependency_inputs_changed"
  } >> "$GITHUB_OUTPUT"
fi
