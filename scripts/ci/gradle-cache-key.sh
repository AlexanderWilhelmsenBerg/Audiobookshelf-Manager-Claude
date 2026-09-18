#!/usr/bin/env bash
set -euo pipefail

cd "${1:-.}"

files=()
while IFS= read -r -d '' file; do
  case "$file" in
    *.gradle.kts|build-logic/*|gradle/*|gradle.properties|gradle.lockfile|*/gradle.lockfile)
      files+=("$file")
      ;;
  esac
done < <(git ls-files -z)

if [ "${#files[@]}" -eq 0 ]; then
  echo "No Gradle configuration inputs found." >&2
  exit 1
fi

{
  for file in "${files[@]}"; do
    sha256sum "$file"
  done
} | sha256sum | awk '{print $1}'
