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

# Raw bodies are kept outside the output directory: they still hold live tokens, and only the
# redacted envelopes may reach a committed path.
RAW_DIR="$(mktemp -d)"
trap 'rm -rf "$RAW_DIR"' EXIT

log() { printf '  %s\n' "$*" >&2; }

# Writes one fixture as a self-describing envelope: status, content type, and the body.
#
# The status code is part of the contract — `NetworkErrorMapper` turns it into an `AppError`, so a
# fixture that records only the body would leave half the behaviour unverified.
#
# The body is stored parsed when it is JSON and as text when it is not. Not every endpoint answers
# with JSON: `POST /init` returns an empty body, which is exactly the case that broke the first
# version of this script. An endpoint that returns nothing is a fact about the contract, not an
# error to crash on.
#
# Values the server generates that are secret or non-deterministic are replaced. Ids and timestamps
# are stabilised too, otherwise every capture would differ from the last and the drift check would
# produce noise instead of signal.
record() {
  python3 - "$1" "$2" "$3" "$4" <<'PY'
import json, re, sys

SECRET_KEYS = {
    "token", "accessToken", "refreshToken", "refresh_token", "access_token",
    "password", "pash", "authToken", "apiKey", "cookie", "set-cookie",
}
# Values that are real but vary per capture; kept structurally, not literally.
#
# `lastScan` is here because the drift check compares two captures byte for byte, and a scan timestamp
# differs by definition between them. Leaving it in made the committed fixtures fail against a fresh
# capture of the *same* server version — a false drift report, which is worse than no report because it
# trains a reader to ignore the check.
VOLATILE_KEYS = {
    "id", "userId", "libraryId", "folderId", "oldUserId", "seriesId", "authorId",
    "createdAt", "updatedAt", "lastSeen", "lastUpdate", "lastScan", "addedAt", "birthtimeMs",
    "mtimeMs", "ctimeMs", "inode", "size", "ino",
}
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I)

# `contentUrl` embeds the audio file's inode: `/api/items/<id>/file/1892359`. The inode is already
# scrubbed where it appears as its own `ino` field, but a value spliced into a path needs its own rule —
# the shape of the URL is the contract, the number in it is not.
FILE_ID_PATH = re.compile(r"(/file/)\d+")

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
        return FILE_ID_PATH.sub(r"\1<volatile>", UUID.sub("<volatile>", node))
    return node

body_path, out_path, status, content_type = sys.argv[1:5]
raw = open(body_path, "rb").read().decode("utf-8", "replace")

envelope = {"status": int(status), "contentType": content_type.split(";")[0] or None}
try:
    envelope["body"] = scrub(json.loads(raw)) if raw.strip() else None
    envelope["bodyKind"] = "json" if raw.strip() else "empty"
except json.JSONDecodeError:
    # Recorded verbatim rather than discarded: "this endpoint does not answer JSON" is part of the
    # contract, and a reader needs to see what it does answer.
    envelope["bodyKind"] = "text"
    envelope["bodyText"] = UUID.sub("<volatile>", raw.strip())[:2000]

with open(out_path, "w") as handle:
    json.dump(envelope, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY
}

# Deliberately not `--fail`: a 4xx is a contract too. `AppError` mapping depends on knowing what an
# unauthorized or rate-limited response actually looks like, and aborting here would throw that away.
capture() {
  local name="$1" method="$2" path="$3"
  shift 3
  local raw="$RAW_DIR/$name.raw"
  local meta
  log "$method $path -> $name.json"
  meta="$(curl -sS -X "$method" "$BASE_URL$path" \
    -H 'Content-Type: application/json' "$@" \
    -o "$raw" -w '%{http_code} %{content_type}')"
  record "$raw" "$OUT_DIR/$name.json" "${meta%% *}" "${meta#* }"
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
capture login POST /login \
  -H 'x-return-tokens: true' \
  -d "{\"username\":\"$ROOT_USER\",\"password\":\"$ROOT_PASS\"}"

# Read the tokens from the raw body, which `capture` left behind unredacted. The committed fixture
# has them replaced; this is the only place the live values are used.
read_token() {
  python3 - "$RAW_DIR/login.raw" "$1" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1]))
except (json.JSONDecodeError, OSError):
    sys.exit(0)
user = data.get("user") if isinstance(data.get("user"), dict) else {}
for key in sys.argv[2].split(","):
    for source in (data, user):
        value = source.get(key)
        if isinstance(value, str) and value:
            print(value)
            sys.exit(0)
PY
}

ACCESS_TOKEN="$(read_token accessToken,token)"
REFRESH_TOKEN="$(read_token refreshToken)"

if [ -z "$ACCESS_TOKEN" ]; then
  echo "::error::/login returned no access token. The captured shape is in login.json — read it" >&2
  echo "::error::before changing this script: the token field may simply have been renamed." >&2
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

# A library, so the captured shapes are the populated ones the adapter has to read.
#
# An empty `{"libraries": []}` was what the first capture produced, and it is nearly useless: it proves
# the envelope key and nothing about a library object, a library item, its audio files or its chapters.
# `LIB-001` maps every one of those, and PRODUCT_SPEC 22.5 forbids relying on a shape no fixture covers.
#
# `MEDIA_DIR` must already contain an audiobook. `scripts/seed-contract-media.sh` produces one with
# the container's own ffmpeg; CI runs it before this script.
MEDIA_PATH="${CONTRACT_MEDIA_PATH:-/audiobooks}"

existing_library_id() {
  curl -sS "$BASE_URL/api/libraries" -H "$AUTH_HEADER" |
    python3 -c 'import json,sys; libs=json.load(sys.stdin).get("libraries") or []; print(libs[0]["id"] if libs else "")'
}

LIBRARY_ID="$(existing_library_id)"
if [ -z "$LIBRARY_ID" ]; then
  log "creating a library at $MEDIA_PATH"
  # Not captured: creating a library is not an operation this app performs, and recording a response
  # the adapter never reads would imply coverage it does not have.
  curl -sS -X POST "$BASE_URL/api/libraries" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d "{\"name\":\"Contract Fiction\",\"folders\":[{\"fullPath\":\"$MEDIA_PATH\"}],\"mediaType\":\"book\"}" \
    >/dev/null
  LIBRARY_ID="$(existing_library_id)"
fi

if [ -z "$LIBRARY_ID" ]; then
  echo "::error::the server accepted no library. /api/libraries is in libraries.json — read it before" >&2
  echo "::error::changing this script: the request shape may have changed." >&2
  exit 1
fi

# The scan is asynchronous. Waiting for an item to appear rather than sleeping a fixed interval keeps
# the capture deterministic on a slow runner.
curl -sS -X POST "$BASE_URL/api/libraries/$LIBRARY_ID/scan" -H "$AUTH_HEADER" >/dev/null || true
for _ in $(seq 1 60); do
  ITEM_COUNT="$(curl -sS "$BASE_URL/api/libraries/$LIBRARY_ID/items" -H "$AUTH_HEADER" |
    python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("results") or []))' 2>/dev/null || echo 0)"
  [ "$ITEM_COUNT" != "0" ] && break
  sleep 2
done
log "library $LIBRARY_ID has $ITEM_COUNT item(s)"

capture libraries GET /api/libraries -H "$AUTH_HEADER"
capture library-items GET "/api/libraries/$LIBRARY_ID/items" -H "$AUTH_HEADER"
capture library-series GET "/api/libraries/$LIBRARY_ID/series" -H "$AUTH_HEADER"
capture library-authors GET "/api/libraries/$LIBRARY_ID/authors" -H "$AUTH_HEADER"

# The expanded single item: `LIB-004` and `PLAY-003` need the audio files and chapters, and the list
# endpoint does not include them.
ITEM_ID="$(curl -sS "$BASE_URL/api/libraries/$LIBRARY_ID/items" -H "$AUTH_HEADER" |
  python3 -c 'import json,sys; r=json.load(sys.stdin).get("results") or []; print(r[0]["id"] if r else "")')"

if [ -n "$ITEM_ID" ]; then
  capture library-item GET "/api/items/$ITEM_ID?expanded=1&include=progress" -H "$AUTH_HEADER"
else
  echo "::error::the library has no items, so the item shape LIB-001 depends on was not captured." >&2
  echo "::error::Check that the media directory contains an audio file the scanner accepts." >&2
  exit 1
fi

capture logout POST /logout -H "$AUTH_HEADER"

log "captured $(find "$OUT_DIR" -name '*.json' | wc -l) fixtures"
