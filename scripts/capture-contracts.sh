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
    # A play session carries the calendar day it was opened on. The 2026-08-13 re-run reported drift on
    # `item-play.json` and `multi-item-play.json` for these two fields and nothing else, which is the
    # false positive this whole set exists to prevent — and it hid the real result, which was that five
    # days changed nothing in the contract. Neither field is read by any mapper.
    "date", "dayOfWeek",
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

# Arrays whose order the server does not actually fix, sorted so a tie cannot flip between captures.
#
# `library-personalized`'s shelves are the case that forced this. "Recently added" sorts by `addedAt`, and
# both books in the contract library are added by the same scan in the same second — so the tie is broken
# arbitrarily, and the drift check reported the two books swapping places as though the contract had
# changed. It cost a red check on every run of this branch and hid the two genuinely new fixtures beneath
# it, which is the same false-positive trap `VOLATILE_KEYS` exists to close.
#
# Sorted by title rather than by id, because the ids are `<volatile>` by the time this runs. The order is
# not part of any contract this app relies on: the shelf's *contents* are, and `LibraryRepository` sorts
# for display itself.
UNORDERED_ARRAY_KEYS = {"entities"}


def sort_key(item):
    """A stable, human-meaningful key for an entity, whatever kind of entity it is."""
    if not isinstance(item, dict):
        return ("", "")
    media = item.get("media") or {}
    metadata = media.get("metadata") or {}
    return (str(metadata.get("title") or item.get("name") or ""), str(item.get("relPath") or ""))


def scrub(node, key=None):
    if isinstance(node, dict):
        return {k: scrub(v, k) for k, v in node.items()}
    if isinstance(node, list):
        scrubbed = [scrub(v, key) for v in node]
        return sorted(scrubbed, key=sort_key) if key in UNORDERED_ARRAY_KEYS else scrubbed
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
#
# Selected **by title**, not as `results[0]`. Taking the first result was fine while the library held
# one book and broke the moment it held two: the seeded multi-file book sorted ahead of this one, so
# `ITEM_ID` became a book with no cover, the cover step's hard failure fired, and the capture aborted
# with two thirds of the fixtures unwritten — including every playback shape.
#
# Naming the book is also what keeps `library-item.json` stable. Every Phase 1 contract test reads that
# fixture and asserts on The Salt Harbour; a selection that silently followed the server's sort order
# would swap the subject of those tests without changing a line of them.
ITEM_ID="$(curl -sS "$BASE_URL/api/libraries/$LIBRARY_ID/items" -H "$AUTH_HEADER" |
  python3 -c '
import json, sys
results = json.load(sys.stdin).get("results") or []
def title(item):
    return (((item.get("media") or {}).get("metadata")) or {}).get("title") or ""
match = next((i for i in results if title(i) == "The Salt Harbour"), None)
print((match or {}).get("id") or "")')"

# An id is one line with no spaces in it. Checked rather than assumed, because the failure this guards
# against has now happened twice and neither time did it look like a failure.
#
# The first was a selection that silently picked the wrong book. The second was a missing closing quote
# in this very assignment, which left the shell consuming the following lines into `$ITEM_ID` — a value
# that is very much non-empty, so `[ -n ]` was satisfied and the capture went on to request a URL built
# out of thirty lines of shell script.
#
# `bash -n` passes both. Syntax checking cannot see a quoting mistake that still parses, so the check
# has to be on the value.
case "$ITEM_ID" in
  "")
    echo "::error::no item titled 'The Salt Harbour' in the library, so the shape LIB-001 depends on" >&2
    echo "::error::was not captured. Check that scripts/seed-contract-media.sh ran and the scan finished." >&2
    exit 1
    ;;
  *[[:space:]]*)
    echo "::error::ITEM_ID is not an id — it contains whitespace, which means the assignment above" >&2
    echo "::error::captured more than the id. Check the quoting of the command substitution." >&2
    exit 1
    ;;
esac

capture library-item GET "/api/items/$ITEM_ID?expanded=1&include=progress" -H "$AUTH_HEADER"

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

# --- The audio file endpoint: range support and validators (DL-001, DL-002, SYNC-001) ------------
#
# Phase 3 cannot be designed without these three answers, and PRODUCT_SPEC 22.4 will not let them be
# assumed:
#
#  1. **Does the server honour a `Range` request?** DL-001 criterion 5 makes resume conditional on it and
#     SYNC-001 gates it behind a `RangeDownload` capability. A 206 with a `Content-Range` means a download
#     interrupted at 90% continues; a 200 means it starts that file again.
#  2. **Does it send a validator — `ETag` or `Last-Modified`?** DL-002 criterion 2 says persist and validate
#     one when the server provides it, and the owner's "repair" action is specified as comparing a checksum
#     against the server's copy. Without a validator, verification is limited to length and readability, and
#     repair can only offer a re-download rather than a comparison.
#  3. **Does it require authentication?** It decides whether a download can be handed to a plain URL fetcher
#     or has to go through the app's authenticated client — the same question the cover capture asks.
#
# Headers only. The bytes are somebody's audio and are never committed; `Content-Length` is recorded because
# DL-002 criterion 1 validates against it, and its *presence* is the contract rather than its value.
log "recording the audio file endpoint's range and validator behaviour (headers only, never the audio)"

FILE_PATH="$(curl -sS "$BASE_URL/api/items/$ITEM_ID?expanded=1" -H "$AUTH_HEADER" |
  python3 -c 'import json,sys; d=json.load(sys.stdin); t=(d.get("media") or {}).get("tracks") or []; print(t[0].get("contentUrl","") if t else "")')"

if [ -z "$FILE_PATH" ]; then
  echo "::error::the expanded item reported no tracks, so the file endpoint could not be probed." >&2
  echo "::error::Check that scripts/seed-contract-media.sh placed audio and that the library rescanned." >&2
  exit 1
fi

# Full request: the validators and whether ranges are advertised at all.
FILE_FULL="$(curl -sS -o /dev/null "$BASE_URL$FILE_PATH" -H "$AUTH_HEADER" \
  -w '%{http_code}|%{content_type}|%{size_download}')"
FILE_HEADERS="$(curl -sS -o /dev/null -D - "$BASE_URL$FILE_PATH" -H "$AUTH_HEADER" | tr -d '\r')"

# Range request: the answer DL-001's resume depends on. 206 with `Content-Range` is support; 200 is not.
FILE_RANGE="$(curl -sS -o /dev/null -r 0-1023 "$BASE_URL$FILE_PATH" -H "$AUTH_HEADER" \
  -w '%{http_code}|%{size_download}')"
FILE_RANGE_HEADERS="$(curl -sS -o /dev/null -D - -r 0-1023 "$BASE_URL$FILE_PATH" -H "$AUTH_HEADER" | tr -d '\r')"

FILE_ANON_STATUS="$(curl -sS -o /dev/null "$BASE_URL$FILE_PATH" -w '%{http_code}')"

python3 -c '
import json, sys

out_path, full, full_headers, ranged, range_headers, anon = sys.argv[1:7]

def header(blob, name):
    """The last value for a header, case-insensitively. `curl -D` prints one block per response."""
    found = None
    for line in blob.splitlines():
        key, _, value = line.partition(":")
        if key.strip().lower() == name:
            found = value.strip()
    return found

full_status, full_type, full_size = full.split("|")
range_status, range_size = ranged.split("|")

accept_ranges = header(full_headers, "accept-ranges")
content_range = header(range_headers, "content-range")
supports_range = range_status == "206" and content_range is not None

envelope = {
    "status": int(full_status or 0),
    "contentType": (full_type or "").split(";")[0] or None,
    "unauthenticatedStatus": int(anon or 0),
    "bodyKind": "binary-not-recorded",
    # Presence, not value: the length differs per seeded file and the drift check compares byte for byte.
    "hasContentLength": header(full_headers, "content-length") is not None,
    "acceptRanges": accept_ranges,
    "range": {
        "requested": "bytes=0-1023",
        "status": int(range_status or 0),
        "hasContentRange": content_range is not None,
        "returnedRequestedLength": range_size == "1024",
        "supported": supports_range,
    },
    # The two validators DL-002 can persist. Recorded as present/absent rather than literally: an ETag is
    # derived from the file and would differ between captures, which is drift-check noise, not signal.
    "validators": {
        "hasETag": header(full_headers, "etag") is not None,
        "hasLastModified": header(full_headers, "last-modified") is not None,
    },
    "note": (
        "GET /api/items/{id}/file/{fileId}. Bytes deliberately not committed; the shape, the range "
        "behaviour and the presence of validators are the contract. See DL-001, DL-002, SYNC-001."
    ),
}
with open(out_path, "w") as handle:
    json.dump(envelope, handle, indent=2, sort_keys=True)
    handle.write("\n")
' "$OUT_DIR/item-file.json" "$FILE_FULL" "$FILE_HEADERS" "$FILE_RANGE" "$FILE_RANGE_HEADERS" "$FILE_ANON_STATUS"

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

# --- Playback sessions (PLAY-001, PLAY-004, PLAY-005) ---------------------------------------------
#
# Phase 2's whole surface, and none of it has ever been captured. `ServerCapability.PlaybackSession`
# and `LocalSessionSync` are both listed "verified against a server: No" in docs/api-compatibility.md,
# which under PRODUCT_SPEC 22.5 means nothing may be written against them yet.
#
# Three things need establishing before a note of audio can be played:
#
#  1. **What a session is.** `POST /api/items/{id}/play` opens one. It is expected to carry the audio
#     tracks with their content URLs, the chapters, and the position to start from — which is what
#     `PLAY-003`'s global timeline is built out of. Whether those URLs are absolute or relative, and
#     whether they carry their own credential, decides how the player is wired to the network stack.
#  2. **How progress goes back.** `POST /api/session/{id}/sync` is the session-scoped route, as opposed
#     to `PATCH /api/me/progress/{id}`, which the app already uses and which is captured above.
#     `PLAY-004` wants a sync roughly every thirty seconds; `PLAY-005` wants a retry to be idempotent.
#     Both need the request *and* the response observed.
#  3. **How a session ends.** `POST /api/session/{id}/close`, and what the server then considers the
#     final position.
#
# The device description is sent because the server records it against the session and may vary its
# answer by it. Every value is deliberately generic — no real device identifier reaches a fixture.
log "opening a playback session so the Phase 2 shapes can be observed"
PLAY_BODY='{"deviceInfo":{"clientName":"ShelfPlayer","clientVersion":"0.0.0","deviceId":"capture","manufacturer":"capture","model":"capture","sdkVersion":36},"supportedMimeTypes":["audio/mpeg","audio/mp4","audio/flac"],"mediaPlayer":"exo-player","forceDirectPlay":false,"forceTranscode":false}'

capture item-play POST "/api/items/$ITEM_ID/play" \
  -H 'Content-Type: application/json' -H "$AUTH_HEADER" -d "$PLAY_BODY"

# The session id drives the two routes below. Read out of the response this capture already made,
# rather than fetched again, so the fixtures describe one session rather than three unrelated ones.
#
# From the **raw** body, not the fixture: `record` redacts volatile fields, and a session id is the
# most volatile thing in the response — it is `<volatile>` by the time it reaches `$OUT_DIR`.
SESSION_ID="$(python3 -c '
import json, sys
try:
    print(json.load(open(sys.argv[1])).get("id") or "")
except Exception:
    print("")
' "$RAW_DIR/item-play.raw" 2>/dev/null || true)"

if [ -n "$SESSION_ID" ]; then
  # A position **inside** the book. The first capture sent 63.5 seconds into an eight-second fixture,
  # and the server did the reasonable thing: clamped to the end and marked the book finished. That
  # made `me-after-session.json` a recording of the auto-finish path rather than of an ordinary
  # mid-book sync, which is the case PLAY-004 actually needs to see.
  log "syncing and closing the session"
  capture session-sync POST "/api/session/$SESSION_ID/sync" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"currentTime":4.5,"timeListened":3,"duration":8}'

  # Sent twice on purpose. PLAY-005 requires a retried sync to be idempotent, and "the server took it
  # twice without the position moving backwards" is a property only a second request can demonstrate.
  capture session-sync-repeated POST "/api/session/$SESSION_ID/sync" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"currentTime":4.5,"timeListened":3,"duration":8}'

  capture session-close POST "/api/session/$SESSION_ID/close" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"currentTime":4.5,"timeListened":3,"duration":8}'

  # --- Offline sessions (PLAY-005) ---------------------------------------------------------------
  #
  # The routes the outbox actually needs. `/api/session/{id}/sync` requires an id the server issued,
  # which an offline session by definition does not have; `/api/session/local` takes a session whose id
  # the *client* generated and treats an unseen one as new. That is what makes a retry idempotent.
  #
  # The id here is a fixed UUIDv4 rather than a generated one, so the capture is deterministic and the
  # drift check stays meaningful. `record` scrubs it to `<volatile>` on the way out either way.
  LOCAL_SESSION_ID="1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"
  LOCAL_SESSION='{"id":"'"$LOCAL_SESSION_ID"'","libraryItemId":"'"$ITEM_ID"'","episodeId":null,"mediaItemId":"'"$ITEM_ID"'","mediaItemType":"book","displayTitle":"The Salt Harbour","displayAuthor":"Marisol Holt","duration":8,"currentTime":5.5,"timeListening":4,"startedAt":0,"updatedAt":0,"mediaPlayer":"exo-player","deviceInfo":{"clientName":"ShelfPlayer","clientVersion":"0.0.0","deviceId":"capture","manufacturer":"capture","model":"capture","sdkVersion":36}}'

  capture session-local POST /api/session/local \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" -d "$LOCAL_SESSION"

  # The same session again. PLAY-005 requires a retry to be idempotent, and the only way to observe
  # that is to send an id the server has now seen and compare what it says the second time.
  capture session-local-repeated POST /api/session/local \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" -d "$LOCAL_SESSION"

  # The batch drain, which is what an outbox with more than one queued session will use.
  capture session-local-all POST /api/session/local-all \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"sessions":['"$LOCAL_SESSION"']}'

  # What the account looks like after a session, which is where PLAY-004's conflict resolution reads
  # from. Captured separately from the pre-session `me.json` so the two can be diffed.
  capture me-after-session GET /api/me -H "$AUTH_HEADER"
else
  # Loud, not silent. No session id means the play route did not answer as expected, and the right
  # outcome is a visible gap in the fixtures rather than three files full of error bodies.
  echo "::warning::no session id in item-play.json — session sync and close were not captured"
fi

# The two-file book, opened as its own session. `startOffset` on a second track is the whole reason
# this exists — see scripts/seed-contract-media.sh for why one track could not answer it.
MULTI_ID="$(python3 -c '
import json, sys
try:
    for item in (json.load(open(sys.argv[1])) or {}).get("results") or []:
        title = (((item.get("media") or {}).get("metadata")) or {}).get("title") or ""
        if "Tidewatch" in title:
            print(item.get("id") or "")
            break
    else:
        print("")
except Exception:
    print("")
' "$RAW_DIR/library-items.raw" 2>/dev/null || true)"

case "$MULTI_ID" in
  *[[:space:]]*)
    # Same guard as ITEM_ID, same reason. A value with whitespace in it is a quoting mistake, not an id.
    echo "::warning::MULTI_ID contains whitespace — check the quoting; PLAY-003 stays unverified"
    MULTI_ID=""
    ;;
esac

if [ -n "$MULTI_ID" ]; then
  capture multi-item-play POST "/api/items/$MULTI_ID/play" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" -d "$PLAY_BODY"
else
  # Not fatal, and not silent either. Until the seeded two-file book has been scanned, PLAY-003 has no
  # evidence to be built on and the plan says so rather than guessing at the arithmetic.
  echo "::warning::no multi-file book found in library-items — PLAY-003's startOffset stays unverified"
fi

# --- Bookmarks (PRODUCT_SPEC 11.1, section 8 recommended feature 4) ------------------------------
#
# Wave 5's closeout named bookmarks as the one Phase 2 item that needs the *server* before a line of it
# can be written: PRODUCT_SPEC 22.4/22.5 forbid building on a shape no capture has produced, and the
# player has carried a disabled bookmark button since wave 2.
#
# These four requests are the whole contract. Every one is allowed to fail — `capture` records whatever
# comes back, including a 404, and a 404 recorded is itself the answer to "does this server have
# bookmarks". What must not happen is code written against a remembered shape.
#
# The order matters: create, then read the user object back (bookmarks live on `user.bookmarks`), then
# update, then delete. Reading `me` in the middle is what proves where a bookmark is *stored*, which is
# the part a client has to know and the part no endpoint response states.
if [ -n "$ITEM_ID" ]; then
  capture bookmark-create POST "/api/me/item/$ITEM_ID/bookmark" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"time":31,"title":"A line worth keeping"}'

  capture me-with-bookmark GET /api/me -H "$AUTH_HEADER"

  capture bookmark-update PATCH "/api/me/item/$ITEM_ID/bookmark" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"time":31,"title":"A line worth keeping, renamed"}'

  capture bookmark-delete DELETE "/api/me/item/$ITEM_ID/bookmark/31" -H "$AUTH_HEADER"
else
  echo "::warning::no ITEM_ID — the bookmark endpoints stay uncaptured and bookmarks stay unbuildable"
fi

# --- The finished flag, both ways (PLAY-004) -----------------------------------------------------
#
# `PATCH /api/me/progress/{id}` is already captured, and the app now uses it to mark a book finished and
# to un-mark it. What the earlier capture exercised was `isFinished: false` only, so `true` was a value
# the app sent that no fixture had ever seen come back.
#
# The 2026-08-13 run settled `true` and left `false` open, because of a mistake in this script that is
# worth naming so it is not repeated. The un-finish probe sent `currentTime: 42.5` to a book **eight
# seconds long** and threw the response away with `>/dev/null`. What came back from the read afterwards
# was still `isFinished: true`, and the capture cannot say whether the server rejected an out-of-range
# position or re-derived the flag from a clamped progress of 1.
#
# Two changes fix that. The position is now **inside** the duration, so nothing is being clamped; and the
# PATCH responses are captured rather than discarded, so a rejection is visible as a status code instead
# of being invisible behind a later GET.
if [ -n "$ITEM_ID" ]; then
  capture media-progress-set-finished PATCH "/api/me/progress/$ITEM_ID" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"currentTime":8,"isFinished":true}'
  capture media-progress-finished GET "/api/me/progress/$ITEM_ID" -H "$AUTH_HEADER"

  capture media-progress-set-unfinished PATCH "/api/me/progress/$ITEM_ID" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"currentTime":2,"isFinished":false}'
  capture media-progress-unfinished GET "/api/me/progress/$ITEM_ID" -H "$AUTH_HEADER"
fi

# --- Management: what the server will and will not let a client change (EPIC MGR) ----------------
#
# Phase 5's whole problem is that none of these shapes had ever been seen. PRODUCT_SPEC 22.4 forbids
# inventing an endpoint and 22.5 forbids relying on a response before a fixture records it, so nothing in
# EPIC MGR can be built against a guess — these captures are what unblock it.
#
# The order is deliberate and the destructive probe is last. A `PATCH` that renames the item would change
# what every earlier capture recorded if it ran first, and a delete would remove the item the bookmark and
# progress captures depend on. Everything read-only happens before anything is written.
#
# The container's root account holds `update`, `delete` and `upload`, so a `403` here would be a fact about
# the *endpoint* rather than about this account — which is exactly the distinction the permission gating in
# the app has to make.
# MGR-003 — the metadata providers this deployment offers.
#
# The fixture `AbsCapabilityResolver` has been written against and is currently missing: the provider probe
# ships source-derived (`docs/api-compatibility.md`, "What the official project's own source settles") and
# this is the capture that turns it into evidence.
#
# Deliberately captured whole and early. It is read-only, needs no item, has no side effects, and is
# deterministic — it lists what the server is configured with, not what a third party answered — which is
# what makes it the one management-adjacent endpoint that can be an honest capability probe.
capture search-providers GET /api/search/providers -H "$AUTH_HEADER"

# MGR-003 — a candidate search, recorded as a **shape** rather than as a body.
#
# This is the only capture in this file that leaves the server: the request makes Google answer. So its
# body is not committed, because it would be different tomorrow and the drift check compares captures byte
# for byte — a fixture that cannot be reproduced is not a fixture, it is a snapshot of one afternoon.
#
# What the app actually relies on is which keys a candidate carries, and that is stable. The status code
# and the sorted key set of the first result are recorded; the values are discarded, which also keeps a
# third party's book data out of a committed file.
if [ -n "$LIBRARY_ID" ]; then
  log "recording the candidate search's shape (keys only, never the results)"
  SEARCH_RAW="$RAW_DIR/search-books.raw"
  SEARCH_STATUS="$(curl -sS -G "$BASE_URL/api/search/books" -H "$AUTH_HEADER" \
    --data-urlencode 'title=The Salt Harbour' --data-urlencode 'provider=google' \
    -o "$SEARCH_RAW" -w '%{http_code}')"
  SEARCH_KEYS="$(python3 -c '
import json, sys
try:
    results = json.load(open(sys.argv[1]))
except Exception:
    print("[]"); raise SystemExit
first = results[0] if isinstance(results, list) and results else {}
print(json.dumps(sorted(first.keys())))' "$SEARCH_RAW")"
  # An empty key set is **not an answer**, and writing it as one is what made this job permanently red.
  #
  # Google Books answers `429` to GitHub Actions' address ranges on every run, so from CI the provider
  # returns nothing and the "captured shape" is an empty list. The committed fixture came from a real
  # deployment and names an Audible result's eighteen keys, so the two disagreed every time — and a check
  # that always fails is a check nobody reads.
  #
  # So a run that learned nothing writes nothing. The compare step treats a fixture the run could not
  # capture as skipped rather than as drift, which keeps the committed evidence and still fails on a real
  # change. See docs/risks.md R-15.
  if [ "$SEARCH_KEYS" = "[]" ]; then
    log "the metadata provider returned no candidates (status $SEARCH_STATUS) — not overwriting the committed shape"
  else
    printf '{\n  "note": "GET /api/search/books?provider=audible. Keys only: the results come from a third party and change. Captured against audiobooks.dev because Google Books answers 429 to CI addresses, so the CI run records an empty list for the default provider.",\n  "status": %s,\n  "firstResultKeys": %s\n}\n' \
      "$SEARCH_STATUS" "$SEARCH_KEYS" >"$OUT_DIR/search-books-shape.json"
  fi
fi

if [ -n "$ITEM_ID" ]; then
  # MGR-004 — the two scan endpoints. Captured before any edit, because a scan can rewrite metadata from
  # the file's own tags and would then be indistinguishable from what the PATCH below does.
  capture item-scan POST "/api/items/$ITEM_ID/scan" -H "$AUTH_HEADER"

  # MGR-003 — quick match. Sent with no provider so the response records what the server does with a bare
  # request; a candidate search needs a provider this container has no key for, and inventing one would
  # capture an error shape rather than a match shape.
  capture item-match POST "/api/items/$ITEM_ID/match" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" -d '{}'

  # MGR-001 — the edit. One field, and one the seeded fixture does not already use, so the response can be
  # read as "this is what changed" rather than "this is what was already there".
  capture item-update PATCH "/api/items/$ITEM_ID/media" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
    -d '{"metadata":{"subtitle":"A subtitle written by the contract capture"}}'

  capture item-after-update GET "/api/items/$ITEM_ID?expanded=1" -H "$AUTH_HEADER"

  # MGR-002 — removing a cover. Captured rather than uploading one: an upload needs a multipart body this
  # script has no image for, and the removal's response shape is the half the app has to understand before
  # it can claim a cover is gone.
  capture item-cover-remove DELETE "/api/items/$ITEM_ID/cover" -H "$AUTH_HEADER"
fi

# MGR-004 — a library scan. After the item captures, because it can rewrite the item.
if [ -n "$LIBRARY_ID" ]; then
  capture library-scan POST "/api/libraries/$LIBRARY_ID/scan" -H "$AUTH_HEADER"
fi

# EPIC USER — the user list, and what a created user looks like coming back.
#
# `USER-001` says tokens and password hashes are never displayed, and this is how the app finds out whether
# the server sends them at all: a field that is never rendered is still a field that reached the device.
capture users-list GET /api/users -H "$AUTH_HEADER"

capture user-create POST /api/users \
  -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
  -d '{"username":"contractlistener","password":"contract-listener-password","type":"user"}'

# --- What a refusal looks like (PRODUCT_SPEC principle 4) ----------------------------------------
#
# Every capture above this point ran as `root`, so every management response so far is the *permitted*
# one. That left the app's second enforcement — the one in the domain layer — built entirely from the
# grants in `me.json`, with no observation of what happens when the server disagrees.
#
# This is the other half. A second account is created, this time **active** so it can sign in, of type
# `user` — whose server-side defaults are download but not update, delete or upload. It then asks for the
# three things it may not have.
#
# The first create above is left alone deliberately: it omits `isActive` and is therefore the fixture that
# records USER-002's finding that a created user cannot sign in. Two creates, two facts.
capture user-create-active POST /api/users \
  -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
  -d '{"username":"contractactive","password":"contract-active-password","type":"user","isActive":true}'

LISTENER_TOKEN="$(curl -sS -X POST "$BASE_URL/login" \
  -H 'Content-Type: application/json' -H 'x-return-tokens: true' \
  -d '{"username":"contractactive","password":"contract-active-password"}' |
  python3 -c 'import json,sys
try:
    user = (json.load(sys.stdin) or {}).get("user") or {}
except Exception:
    user = {}
print(user.get("accessToken") or user.get("token") or "")')"

if [ -n "$LISTENER_TOKEN" ] && [ -n "$ITEM_ID" ]; then
  LISTENER_HEADER="Authorization: Bearer $LISTENER_TOKEN"

  # This account's own view of itself. The permissions here are what the app's gating reads, and the
  # three refusals below are what those permissions are supposed to predict.
  capture me-listener GET /api/me -H "$LISTENER_HEADER"

  # MGR-001 — refused for want of the update grant.
  capture item-update-forbidden PATCH "/api/items/$ITEM_ID/media" \
    -H 'Content-Type: application/json' -H "$LISTENER_HEADER" \
    -d '{"metadata":{"subtitle":"This edit must be refused"}}'

  # MGR-005 — refused for want of the delete grant. Safe to attempt precisely because it is refused; if
  # the server ever stops refusing it, this capture fails loudly by deleting the item the next capture
  # reads, which is the correct way for that surprise to surface.
  capture item-delete-forbidden DELETE "/api/items/$ITEM_ID" -H "$LISTENER_HEADER"

  # MGR-004 — refused on account *type* rather than on a grant. The distinction matters: this account
  # could hold every permission the server has and still be refused here.
  capture item-scan-forbidden POST "/api/items/$ITEM_ID/scan" -H "$LISTENER_HEADER"
else
  log "the non-admin account could not sign in; the refusal shapes were not captured"
fi

# MGR-005 — **the destructive one, last.** Removing the item from the database is what the requirement is
# about, and its response is the only thing that can tell the app whether a removal actually happened.
#
# It runs after everything that needs the item, and its own capture is the last word on that item. Nothing
# below may depend on `ITEM_ID`.
if [ -n "$ITEM_ID" ]; then
  capture item-delete DELETE "/api/items/$ITEM_ID" -H "$AUTH_HEADER"
  capture item-after-delete GET "/api/items/$ITEM_ID" -H "$AUTH_HEADER"
fi

capture logout POST /logout -H "$AUTH_HEADER"

log "captured $(find "$OUT_DIR" -name '*.json' | wc -l) fixtures"
