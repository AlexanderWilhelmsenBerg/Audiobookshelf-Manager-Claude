#!/usr/bin/env bash
set -euo pipefail

# Cached Codex environments only need to re-check the pinned toolchain and refresh Gradle metadata.
# Keep the expensive verifyDebug pre-warm for first setup; the agent will run the real gate after it
# has made its changes.

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

export BOOKWAVE_CODEX_PREWARM=light
exec bash scripts/codex/setup.sh
