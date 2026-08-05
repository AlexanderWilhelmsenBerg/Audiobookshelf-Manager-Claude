#!/usr/bin/env bash
# Captures real Audiobookshelf responses as contract fixtures.
#
# PRODUCT_SPEC 22.4/22.5 — endpoints are never invented, and a response shape is not relied on until
# a fixture exists for it. This script is how that fixture is produced: it drives a real
# Audiobookshelf server and records what it actually answers, so the adapter is written against
# observed behaviour rather than against a reference the project itself describes as out of date
# (PRODUCT_SPEC 23).
#
# Usage: capture-contracts.sh <base-url> <output-dir>
#
# Every token, cookie and id is replaced before anything is written. Nothing that reaches the output
# directory may contain a credential: these files are committed, and the secret scan runs over them.

set -euo pipefail

BASE_URL="${1:?usage: capture-contracts.sh <base-url> <output-dir>}"
OUT_DIR="${2:?usage: capture-contracts.sh <base-url> <output-dir>}"

# Fixed, obviously-fake credentials for a throwaway container. Never reused anywhere else.
ROOT_USER="contractroot"
ROOT_PASS="contract-fixture-password"

mkdir -p "$OUT_DIR"

log() { printf '  %s\n' "$*" >&2; }

# Replaces every value the server generates that is either secret or non-deterministic. Ids are
# stabilised too, otherwise the committed fixture would differ on every capture and the drift check
# below would be noise rather than signal.
redact() {
  python3 - "$1" <<'PY'
import json, re, sys

SECRET_KEYS = {
    "token", "accessToken", "refreshToken", "refresh_token", "access_token",
    "password", "pash", "authToken", "apiKey", "cookie", "set-cookie",
}
# Values that are real but vary per capture; kept structurally, not literally.
VOLATILE_KEYS = {
    "id", "userId", "libraryId", "folderId", "oldUserId", "seriesId", "authorId",
    "createdAt", "updatedAt", "lastSeen", "lastUpdate", "addedAt", "birthtimeMs",
    "mtimeMs", "ctimeMs", "inode", "size", "ino",
}
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I)

def scrub(node, key=None):
    if isinstance(node, dict):
        return {k: scrub(v, k) for k, v in node.items()}
    if isinstance(node, list):
        return [scrub(v, key) for v in node]
    if key in SECRET_KEYS and node not in (None, "", False, True):
        return "<redacted-secret>"
    if key in VOLATILE_KEYS and isinstance(node, str):
        return "<volatile>"
    if key in VOLATILE_KEYS and isinstance(node, (int, float)) and not isinstance(node, bool):
        return 0
    if isinstance(node, str):
        return UUID.sub("<volatile>", node)
    return node

path = sys.argv[1]
with open(path) as handle:
    data = json.load(handle)
with open(path, "w") as handle:
    json.dump(scrub(data), handle, indent=2, sort_keys=True)
    handle.write("\n")
PY
}

# `--fail-with-body` so an unexpected status is a captured, visible failure rather than an empty file.
capture() {
  local name="$1" method="$2" path="$3"
  shift 3
  local target="$OUT_DIR/$name.json"
  log "$method $path -> $name.json"
  curl -sS --fail-with-body -X "$method" "$BASE_URL$path" \
    -H 'Content-Type: application/json' "$@" -o "$target"
  redact "$target"
}

log "waiting for $BASE_URL"
for _ in $(seq 1 60); do
  if curl -sSf --max-time 3 "$BASE_URL/healthcheck" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -sSf --max-time 5 "$BASE_URL/healthcheck" >/dev/null

# `/status` before init: reports isInit=false. This is the response `AUTH-001` uses to decide whether
# a pasted URL is an Audiobookshelf server at all, and `SYNC-001` reads serverVersion from it.
capture status-uninitialized GET /status

# First-run root user creation. Only valid while no root user exists.
capture init POST /init \
  -d "{\"newRoot\":{\"username\":\"$ROOT_USER\",\"password\":\"$ROOT_PASS\"}}"

capture status-initialized GET /status

# `x-return-tokens: true` is what makes the server return the refresh token in the *body* instead of
# setting a cookie. A mobile client has no cookie jar tied to a browser session, so this header —
# not the cookie path — is the one this app depends on.
log "POST /login -> login.json"
LOGIN_RAW="$(mktemp)"
curl -sS --fail-with-body -X POST "$BASE_URL/login" \
  -H 'Content-Type: application/json' \
  -H 'x-return-tokens: true' \
  -d "{\"username\":\"$ROOT_USER\",\"password\":\"$ROOT_PASS\"}" \
  -o "$LOGIN_RAW"

ACCESS_TOKEN="$(python3 -c '
import json,sys
d=json.load(open(sys.argv[1]))
u=d.get("user") or {}
print(d.get("accessToken") or u.get("accessToken") or u.get("token") or "")
' "$LOGIN_RAW")"
REFRESH_TOKEN="$(python3 -c '
import json,sys
d=json.load(open(sys.argv[1]))
print(d.get("refreshToken") or (d.get("user") or {}).get("refreshToken") or "")
' "$LOGIN_RAW")"

cp "$LOGIN_RAW" "$OUT_DIR/login.json"
redact "$OUT_DIR/login.json"
rm -f "$LOGIN_RAW"

if [ -z "$ACCESS_TOKEN" ]; then
  echo "::error::/login returned no access token; the captured shape is in login.json" >&2
  exit 1
fi
AUTH_HEADER="Authorization: Bearer $ACCESS_TOKEN"

# The token-to-user exchange the app performs on every cold start.
capture authorize POST /api/authorize -H "$AUTH_HEADER"

# Refresh is what `AUTH-004` relies on to keep a session alive without re-prompting.
if [ -n "$REFRESH_TOKEN" ]; then
  capture auth-refresh POST /auth/refresh -H "x-refresh-token: $REFRESH_TOKEN"
else
  log "no refresh token returned; skipping /auth/refresh"
fi

capture libraries GET /api/libraries -H "$AUTH_HEADER"

LIBRARY_ID="$(curl -sS "$BASE_URL/api/libraries" -H "$AUTH_HEADER" |
  python3 -c 'import json,sys; libs=json.load(sys.stdin).get("libraries") or []; print(libs[0]["id"] if libs else "")')"

if [ -n "$LIBRARY_ID" ]; then
  capture library-items GET "/api/libraries/$LIBRARY_ID/items" -H "$AUTH_HEADER"
  capture library-series GET "/api/libraries/$LIBRARY_ID/series" -H "$AUTH_HEADER"
else
  # A fresh container has no libraries. Recorded rather than silently skipped, because "the list is
  # empty" is itself a shape the app has to render (PRODUCT_SPEC 21 distinguishes empty from loading).
  log "no libraries on a fresh server; library-items not captured"
fi

capture logout POST /logout -H "$AUTH_HEADER"

log "captured $(find "$OUT_DIR" -name '*.json' | wc -l) fixtures"
