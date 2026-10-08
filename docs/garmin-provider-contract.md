# BookWave Audio Provider protocol v1

Provider app `0a5435b5995c4c10826cc11606e31350` is separate from Companion
`6f5b4fa4a2db4d42a9d41ee67df34d11`. Existing Companion Phase 2 bytes remain unchanged.
Communications runs in the provider context. Watch `ready`/`sleeping` announcements contain `v:1`,
`t`, current launch nonce `n`, and bound phone profile `p` (null before pairing). Android sends one
initial hello probe; a closed/timed-out provider is not continuously polled. User intent or ready wakes it.

Requests/replies carry integer `v:1`, type `t` (24 chars), UUID request `r`, captured profile `p`
and launch nonce `n` (64 chars each). Hello requests omit nonce; replies establish it. Unknown fields
are tolerated. Missing/invalid required fields, wrong watch/profile/nonce/request, locked/reauthenticated
profile and profile-generation changes fail closed. Incompatible majors are not interpreted.

| Request | Result and semantics |
| --- | --- |
| hello | configured/paired/pairing booleans and advertised allowlisted capabilities; no media metadata |
| pair + six-character code | explicit Accept pairing / Cancel Menu2 before first binding; fresh empty providers may pair before Sidecar login; same-profile retry replaces the pending code; expires after 120 seconds; CONFIRM_ON_WATCH is not completed pairing |
| cancel_pair | cancels only a pending request for the captured profile; does not unbind accepted pairing |
| setup + url/user/password | advertised capability; bound, unlocked/authorized profile+launch nonce only; transient HTTPS Sidecar health/login; final result reports success only after opaque session storage |
| authorize / redact | matching bound profile only; redact clears every private feed value and blocks inventory/events/download/sync until authorized |
| download + b | server book ID, 128 chars; actual Sidecar files validation/native queue acceptance required; duplicate existing jobs are idempotent |
| inventory + offset | one row per bounded sequential page; offset, rows, more, source observation at, successful native synced timestamp and completed/failed sync request IDs |
| events | up to five oldest unacknowledged events, more/gap and observation at |
| ack_events + ids | exact contiguous prefix only; Android sends after durable Room import, never after failed writes |
| sync | durable native request ID; queued reply does not mean successful native progress/download sync |

Inventory rows contain b/title (512 chars)/state (queued, partial, downloaded, failed), done/total,
from (source seconds at first cached part), optional pos/at. Resume downloads can contain only the
remaining audio; total is the requested suffix's part count. A complete suffix does not imply the full
book is cached. All wire progress is integral source seconds; epoch timestamps are source seconds,
converted once to Android milliseconds. Unknown duration is 0; no percentage is fabricated.
Events carry id, captured p, b, pos, dur, original at, k (checkpoint/pause/stop/complete/part_boundary)
and finished. Native chunks are audio parts, not canonical ABS chapters. Actual resume progress saves
about every 15 seconds; journal checkpoints are bounded to a minute plus important lifecycle/part events.
Paged local events survive restart; ACK advances the cursor, duplicate import is idempotent, allocation
failure retains a gap warning rather than silently dropping unacknowledged history.

Android Room22 adds only garmin_records, keyed by profile/device/kind/record ID with cascading profile
ownership. Download/sync requests persist before BLE. Pairing and one-time setup credentials are never durable commands. Inventory replaces only after every page validates. History uses
original event time, never phone receipt time. Last synced is actual completed native work; permission/
progress refresh uses the existing account use case and persists retries after network failure. No action
starts either player. Sidecar owns GARMIN → ABS progress uploads and preserves legitimate rewinds.

Provider setup requires HTTPS Sidecar health/login and its opaque UUID session, never a normal ABS
token/JWT fallback. Sidecar does not expose principal identity to this bridge: the user explicitly checks
matching server/account while pairing and entering setup. Android prefills the current account username;
it cannot recover a discarded login password. The user explicitly enters it once, submits over the
separate provider channel, and both sides discard transient password state. Neither Room, saved UI
state, normal DataStore, logs nor watch Storage retain it. The watch checks HTTPS /health exactly 200
text/plain ok before JSON POST /login; only its UUID Sidecar session is saved. Foreign account anchors
reject setup before health/login; stop/privacy changes invalidate asynchronous callbacks. Legacy
providers without setup capability retain watch-entry fallback; phone setup fails visibly. Retained profile/account anchors reject rebinding old cache or
journal to another user/server. Sync old events before uninstalling to switch accounts. Disconnected
phone locks cannot remotely erase watch media; delivered clears remove the published projection.

Sidecar endpoint fixtures are pinned to MIT WatchShelf93ac7507; no watch inventory/session server routes
are invented. Metadata titles in Android require a current authorized catalogue row. Existing WatchShelf
and BookWave provider storage are separate. Hardware/native permissions and chunk integrity remain GD tests.

Claimed native sync IDs remain durable through stop/process loss until their own completed/failed
outcome, and do not consume a newer queued ID. Duplicating a download already covered by matching
normal-speed cached audio returns stored; mismatched duration or partial coverage cannot claim success.
Android retries after a fresh full inventory proves an accepted download absent.

## Setup and pairing recovery — 2026-10-08

The Menu2 has explicit Accept and Cancel options, with Back cancelling too. Hello reports whether a
pair request for this profile is still pending. Android clears stale codes on cancellation, acceptance,
failed delivery, explicit watch cancellation and timeout. Retry always sends a new code; it does not
require reinstalling. Closing the provider invalidates pending codes and setup callbacks. No retained
cache/account anchor is cleared to solve pairing.

Setup URL maximum 512 characters, username 128, password 256; HTTPS base URL only, preserving subpaths
and rejecting userinfo/query/fragment/whitespace/dot traversal. Android strips surrounding whitespace
and trailing slashes. Credentials are not retried automatically; re-enter the password after a failure.
WatchShelf login/health reuse the captured MIT upstream contract 93ac7507; no ABS endpoint is invented.
Garmin -1002 is UNSUPPORTED_CONTENT_TYPE_IN_RESPONSE, not proof of a bad URL. A wrong destination or
proxy HTML response is a possible cause. Show actionable URL/proxy guidance and keep normal TLS checks.
See [Garmin Communications](https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications.html).
