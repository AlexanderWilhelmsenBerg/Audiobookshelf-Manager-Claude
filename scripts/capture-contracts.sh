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
    # A media-progress element carries two of its own timestamps. They are the wall clock at capture
    # time, so leaving them literal would report drift on every single run — the false-positive the
    # `lastScan` note above already warns about, and the fastest way to teach a reader to ignore a
    # red check.
    "startedAt", "finishedAt",
    # engine.io's session id. Not secret in the credential sense — it is useless without the
    # connection — but it changes on every handshake, so leaving it in would report drift on every run.
    "sid", "libraryItemId", "episodeId",
}
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I)

# The same session id in an engine.io text frame, where it is not a JSON key this scrubber can reach.
TEXT_SID = re.compile(r'("sid"\s*:\s*")[^"]+(")')

# A last-resort scrub for bodies that never became JSON.
#
# It exists because of the socket frames. Those arrive as engine.io text — `42["event",{…}]` — and the
# structured scrubber above cannot reach inside a string. If a frame carries a user object, it carries a
# token, and these files are committed and scanned. Belt and braces: [socket_frames] parses the frames
# properly, and this catches whatever it could not.
TEXT_SECRET = re.compile(
    r'("(?:{})"\s*:\s*")[^"]*(")'.format("|".join(sorted(SECRET_KEYS))),
    re.I,
)
JWT = re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]*")


def scrub_text(raw):
    text = TEXT_SECRET.sub(r"\1<redacted-secret>\2", raw)
    text = JWT.sub("<redacted-secret>", text)
    return TEXT_SID.sub(r"\1<volatile>\2", UUID.sub("<volatile>", text))


def socket_frames(raw):
    """engine.io frames, parsed so the JSON inside them can be scrubbed like any other body.

    A polling response is one or more frames joined by the record separator, each a numeric type
    followed by an optional JSON payload: `0{"sid":…}` is the handshake, `40` a namespace connect,
    `42["event",{…}]` an event. Returning them structured is what makes an event payload readable as a
    contract rather than as a wall of text — and what lets the scrubber reach a token inside one.

    Returns None when nothing looks like a frame, so the caller can fall back to plain text.
    """
    parsed = []
    for chunk in raw.strip().split("\x1e"):
        if not chunk:
            continue
        match = re.match(r"^(\d+)(.*)$", chunk, re.S)
        if not match:
            return None
        prefix, payload = match.group(1), match.group(2).strip()
        frame = {"type": prefix}
        if payload:
            try:
                frame["payload"] = scrub(json.loads(payload))
            except json.JSONDecodeError:
                frame["payloadText"] = scrub_text(payload)[:2000]
        parsed.append(frame)
    return parsed or None

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
    frames = socket_frames(raw)
    if frames is not None:
        envelope["bodyKind"] = "socket-frames"
        envelope["frames"] = frames
    else:
        envelope["bodyKind"] = "text"
        envelope["bodyText"] = scrub_text(raw)[:2000]

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

# --- Endpoints learned from AudioBooth (ADR-0008) ---------------------------------------------------
#
# AudioBooth is an MPL-2.0 iOS client observed to work against real servers. Reading it told us these
# paths exist; it did not tell us what they return, and ADR-0008 is explicit that it must not be read
# as a schema. That is what these captures are for — PRODUCT_SPEC 22.5 still governs, and nothing is
# mapped from any of them until the fixture below is committed.
#
# `q=the` rather than an empty query: an empty search may be answered with an empty envelope on some
# versions, and an envelope with no results in it does not show the element shapes the mapper needs.
capture library-search GET "/api/libraries/$LIBRARY_ID/search?q=the" -H "$AUTH_HEADER"

# PRODUCT_SPEC 3.2 makes collections conditional on consistent server support. Capturing the shape is
# how "consistent" stops being an assumption: an empty array from a server with no collections is
# itself a useful observation, and it is recorded rather than treated as a failure.
capture library-collections GET "/api/libraries/$LIBRARY_ID/collections" -H "$AUTH_HEADER"

# The server's own home shelves. ShelfPlayer derives its shelves from Room instead, deliberately — see
# ADR-0008 — so this is captured for the parts a client cannot compute rather than to be adopted
# wholesale. Held, not used.
capture library-personalized GET "/api/libraries/$LIBRARY_ID/personalized" -H "$AUTH_HEADER"

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

# --- Cover art -------------------------------------------------------------------------------------
#
# PRODUCT_SPEC LIB-001 ("initial sync stores … covers") and LIB-004. No build has ever rendered one,
# and the reason is here: the item response carries `media.coverPath`, which is the path on the
# *server's* filesystem — `/audiobooks/Some Book/cover.jpg`. It is not a URL, and PRODUCT_SPEC 3.4
# rules out reaching a server's filesystem directly, so it cannot become one.
#
# The endpoint that serves the image has never been captured. This records its response *shape* — the
# status, the content type, and whether it is reachable without a credential — which is the whole of
# what an image pipeline needs to know. The bytes are deliberately not committed: a JPEG in a contract
# fixture proves nothing a content type does not, and PRODUCT_SPEC 14.5 keeps private media out of the
# repository.
#
# The unauthenticated probe decides how the image loader is built. If the endpoint refuses an anonymous
# request then covers have to go through the app's authenticated client rather than a bare URL handed
# to an image library, and PRODUCT_SPEC 22.5 wants that answered by observation, not by assumption.
log "recording the cover endpoint's shape (headers only, never the image)"
COVER_META="$(curl -sS -o /dev/null "$BASE_URL/api/items/$ITEM_ID/cover" -H "$AUTH_HEADER" \
  -w '%{http_code} %{content_type}')"
COVER_ANON_STATUS="$(curl -sS -o /dev/null "$BASE_URL/api/items/$ITEM_ID/cover" -w '%{http_code}')"

# A 404 here means the seeded book has no cover, not that the path is wrong — that is exactly what the
# first capture recorded, and committing it would let LIB-004 be written against a shape nobody has
# seen. `scripts/seed-contract-media.sh` places a `cover.jpg` beside the audio so this answers 200.
if [ "${COVER_META%% *}" != "200" ]; then
  echo "::error::the cover endpoint answered ${COVER_META%% *}, so its shape was not captured." >&2
  echo "::error::A 404 means the scanned book has no cover art. Check that the media directory" >&2
  echo "::error::contains a cover file beside the audio and that the library has been rescanned." >&2
  exit 1
fi

python3 -c '
import json, sys
out_path, meta, anon_status = sys.argv[1], sys.argv[2], sys.argv[3]
status, _, content_type = meta.partition(" ")
envelope = {
    "status": int(status or 0),
    "contentType": (content_type or "").split(";")[0] or None,
    "unauthenticatedStatus": int(anon_status or 0),
    "bodyKind": "binary-not-recorded",
    "note": "GET /api/items/{id}/cover. Bytes deliberately not committed; the shape is the contract.",
}
with open(out_path, "w") as handle:
    json.dump(envelope, handle, indent=2, sort_keys=True)
    handle.write("\n")
' "$OUT_DIR/item-cover.json" "$COVER_META" "$COVER_ANON_STATUS"

# --- Media progress -------------------------------------------------------------------------------
#
# PRODUCT_SPEC LIB-001 / SYNC-002 and acceptance case TC-10: progress played on another device does not
# reach the app until a manual refresh. The cheap fix is to read `user.mediaProgress` — which the app
# already receives on every cold start — instead of re-syncing the whole library.
#
# It could not be written, because the committed `authorize.json` has `mediaProgress: []`. The capture
# never played anything, so the *element* shape has never been observed, and PRODUCT_SPEC 22.5 forbids
# mapping a shape no fixture covers. Recording a position first is the whole point of this section: it
# turns an empty array into evidence.
log "recording a listening position so the progress shape can be observed"
curl -sS -X PATCH "$BASE_URL/api/me/progress/$ITEM_ID" \
  -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
  -d '{"currentTime":42.5,"isFinished":false}' >/dev/null || true

# `GET /api/me` returns the same `user` object that `POST /api/authorize` nests under `user`, and the
# capture confirmed the two are field-for-field identical. One fixture for the object and one for a
# single element is the whole contract; a third recording `authorize` again with progress filled in
# would add no evidence and would have to be kept in step with both of the others.
capture me GET /api/me -H "$AUTH_HEADER"
capture media-progress GET "/api/me/progress/$ITEM_ID" -H "$AUTH_HEADER"

python3 - "$OUT_DIR/me.json" <<'PY' || true
import json, sys
progress = (json.load(open(sys.argv[1])).get("body") or {}).get("mediaProgress") or []
if not progress:
    print("::warning::mediaProgress is still empty. The progress write was not accepted, so the "
          "element shape remains unobserved and LIB-001's progress sync stays blocked.", file=sys.stderr)
PY

# --- Websocket ------------------------------------------------------------------------------------
#
# PRODUCT_SPEC LIB-001's last acceptance criterion ("websocket events update Room") and SYNC-002.
# Nothing about the socket has ever been observed: not the handshake, not the authentication frame,
# not one event payload. This section records all three, over engine.io's *polling* transport, which
# is plain HTTP and therefore capturable with curl alone.
#
# Polling rather than a websocket upgrade is deliberate. The frames are identical either way — the
# transport is what differs — and a dependency-free capture is one that still runs in three years.
#
# ### What is a guess here, and why that is allowed
#
# The event name in the authentication frame is not documented and is not in `openapi.json`. Sending
# it is a guess. That is the correct place for one: PRODUCT_SPEC 22.4 forbids an *adapter* built on an
# invented shape, and the way to stop guessing is to ask a real server and record the answer. If the
# guess is wrong, the recorded poll shows an error or silence, and that is itself the finding. What
# must not happen is code written against a shape nobody ever saw.
#
# Non-fatal throughout: a reverse proxy or an image without socket support should leave the rest of
# the capture intact. Each step still writes its fixture, so "nothing came back" is visible rather
# than skipped.
SOCKET_URL="$BASE_URL/socket.io/?EIO=4&transport=polling"

capture socket-handshake GET "/socket.io/?EIO=4&transport=polling"

SOCKET_SID="$(python3 - "$RAW_DIR/socket-handshake.raw" <<'PY'
import json, re, sys
try:
    raw = open(sys.argv[1]).read()
except OSError:
    sys.exit(0)
# engine.io frames are a single-digit type followed by the payload: `0{"sid":...}` is OPEN.
match = re.search(r"\{.*\}", raw)
if match:
    try:
        print(json.loads(match.group(0)).get("sid", ""))
    except json.JSONDecodeError:
        pass
PY
)"

if [ -z "$SOCKET_SID" ]; then
  log "no engine.io session id; skipping the socket frames (socket-handshake.json records why)"
else
  log "socket session established; recording frames"
  # `40` is the socket.io CONNECT frame for the default namespace. Without it the server answers no
  # events at all, so this is the step that turns a transport into a session.
  curl -sS -X POST "$SOCKET_URL&sid=$SOCKET_SID" \
    -H 'Content-Type: text/plain;charset=UTF-8' --data-binary '40' >/dev/null || true

  # Whatever the server volunteers once connected, before this client asserts anything. No guess is
  # involved in this one, which makes it the most trustworthy frame in the set.
  capture socket-connected GET "/socket.io/?EIO=4&transport=polling&sid=$SOCKET_SID"

  # The guess: `42["auth", "<token>"]` is socket.io's EVENT frame carrying an `auth` event. The token
  # travels in the frame rather than in a query string, which is what PRODUCT_SPEC 22.6 requires of
  # any credential this app sends.
  curl -sS -X POST "$SOCKET_URL&sid=$SOCKET_SID" \
    -H 'Content-Type: text/plain;charset=UTF-8' \
    --data-binary "42[\"auth\",\"$ACCESS_TOKEN\"]" >/dev/null || true
  capture socket-auth GET "/socket.io/?EIO=4&transport=polling&sid=$SOCKET_SID"

  # A progress change made over REST, then a poll: if the server broadcasts progress at all, this is
  # the frame that carries it, and it is exactly what TC-10 needs.
  curl -sS -X PATCH "$BASE_URL/api/me/progress/$ITEM_ID" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"currentTime":128.25,"isFinished":false}' >/dev/null || true
  capture socket-event-after-progress GET "/socket.io/?EIO=4&transport=polling&sid=$SOCKET_SID"
fi

capture logout POST /logout -H "$AUTH_HEADER"

log "captured $(find "$OUT_DIR" -name '*.json' | wc -l) fixtures"
