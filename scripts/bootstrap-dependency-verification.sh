#!/usr/bin/env bash
# PRODUCT_SPEC 16.1 / 15 — generate `gradle/verification-metadata.xml`.
#
# Gradle can only record a checksum for an artifact it has actually resolved, so this must run in an
# environment that can reach every configured repository (Maven Central *and* Google's Maven, which
# hosts AGP, AndroidX, Room, Media3 and the Compose libraries).
#
# After the first successful run, review the generated file and then flip
# `org.gradle.dependency.verification` in gradle.properties from `lenient` to `strict`, so that a
# checksum mismatch fails the build instead of only being reported. See docs/adr/0006.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "Resolving every configuration and recording SHA-256 checksums..."
./gradlew --write-verification-metadata sha256 \
  resolveAndLockAll --write-locks \
  --refresh-dependencies "$@"

cat <<'EOF'

Generated gradle/verification-metadata.xml.

Next steps:
  1. Review the file. Every entry is an artifact this build will accept.
  2. Set org.gradle.dependency.verification=strict in gradle.properties.
  3. Run ./gradlew verifyDebug to confirm the build still resolves.
  4. Commit both files together with the version-catalog change that caused them.
EOF
