# Phase 2 — the streaming player, in waves

Written against `PRODUCT_SPEC` PLAY-001 … PLAY-009 and the Phase 2 exit criteria, with Phase 1 merged.

## Where Phase 2 actually stands — 2026-08-07

**Roughly one wave of six.** There is no `:playback` module, no `MediaLibraryService`, no ExoPlayer
dependency and no playback method on the gateway. Against the deliverables PRODUCT_SPEC lists for the
phase:

| Deliverable | State |
| --- | --- |
| MediaLibraryService | Not started |
| Remote playback session | Contract captured; no code |
| ExoPlayer | Not started |
| Global timeline | Not started, and blocked — see wave 2 |
| Progress sync | Phase 1's storage and `/api/me` read exist; session sync not started |
| Notification / lockscreen / headset | Not started |
| Speed / skip | Not started |
| Buffer presets | Not started |
| Audio focus / noisy handling | Not started |

What exists is wave 0: the contracts, and the design decisions the contracts forced. That is the
correct order — Phase 1 showed twice that a fixture changes the implementation — but it should not be
mistaken for progress against the exit criteria, all four of which need a device and a running player.

## Exit criteria, and what each one actually demands

PRODUCT_SPEC names four. None of them is a unit test, and that shapes the whole plan:

| Criterion | What proves it |
| --- | --- |
| Two-hour streaming soak | A device, a real server, and two hours. No fake can produce it. |
| Process/activity recreation | A device plus `adb shell am kill`, or a managed-device test. |
| Media-button resume | A headset or an emulator route equivalent. |
| Progress verified against server | The server's own record read back after a session. |

All four are hardware. What the repository can carry is everything that makes them *pass on the first
try* — and, per Phase 1's lesson, the thing that decides that is fixtures.

## Wave 0 — the capture ✅ **mostly run on 2026-08-07**

Five fixtures committed. What they settled is in `docs/api-compatibility.md`; the three consequences
for the waves below are:

- **Track URLs are relative and carry no credential**, so ExoPlayer's data source must be the app's
  authenticated OkHttp client. PRODUCT_SPEC 14.5 is satisfied by the server's design rather than
  worked around.
- **Sync and close answer `200` with an empty body.** No confirmation to reconcile against, so wave 3's
  outbox is retry-until-200 plus a separate read of `/api/me`.
- **`startOffset` across tracks is still unverified** — the seed library had one single-file book. The
  seed now creates a two-file book and the capture opens a session against it. **Wave 2 is blocked
  until that fixture exists**, because computing a global position from an unverified offset is exactly
  what 22.5 forbids.

One accident worth keeping: the capture synced a position past the fixture book's duration, and the
server clamped it and marked the book finished. That records a real behaviour — **a session sync can
mark a book finished without being asked** — and the reason turned out to be a library setting we had
captured in Phase 1 and never read. See wave 3.

### Still to capture

| Route | Blocks |
| --- | --- |
| `POST /api/items/{id}/play` on the two-file book | Wave 2 — `startOffset` is still unverified |
| `POST /api/session/local` | Wave 3 — the offline outbox |
| `POST /api/session/local-all` | Wave 3 — the batch drain |
| `GET /api/session/{id}` | Resuming an open session rather than opening a second one |

## Wave 1 — the vertical slice: one book, one track, play and pause

The smallest thing that is genuinely Phase 2 rather than scaffolding.

- `MediaLibraryService` with the media-playback foreground-service type and the permissions PLAY-001
  names. **One** session, enforced structurally rather than by convention.
- ExoPlayer behind the gateway's session, streaming a single-file book.
- Media notification with cover, title, author, progress and transport controls.
- Audio focus through Media3, default-to-pause on transient loss (PLAY-002), and the becoming-noisy
  receiver — headphones out pauses, and playback never jumps to the phone speaker.
- Progress journaled locally every five seconds (PLAY-004).

Deliberately deferred out of wave 1: multi-track, chapters, speed, sleep timer. Each is a real
requirement and none of them is needed to prove the service works.

## Wave 2 — the global timeline

PLAY-003, and the wave with the most arithmetic in it. **Blocked on the multi-track fixture** — see
wave 0.

- Multi-file books in server track order, with excluded tracks skipped.
- Seeking across a track boundary while keeping the global book position.
- Chapter navigation that is independent of file boundaries — a chapter can start mid-file and a file
  can hold several.
- The unit tests that belong here rather than on a device: offset conversion both ways, boundary
  seeking, malformed and absent chapter data.

## Wave 3 — progress, and not losing it

PLAY-004 and PLAY-005. The requirements product priority 2 exists for. **Revised after reading the
server's session manager** — the original plan had a structural hole in it.

### The hole, and what fills it

The plan had the outbox retrying `POST /api/session/{id}/sync` per session. That cannot work for an
offline session: the route needs an id the *server* issued, and a session recorded on a train has never
been to the server. There was no route by which an offline session could ever have been uploaded.

`POST /api/session/local` is the one built for it — the **client** generates the id, and the server
treats an unseen id as a new session. That is what PLAY-005's "every offline listening session has a
UUIDv4 identifier" is for, and it is what makes a retry idempotent: the second attempt carries the same
id and is recognised as the same session rather than duplicated.

`POST /api/session/local-all` takes `{"sessions": [...]}` and answers with a per-session result, so an
outbox drains in one request rather than N.

### What wave 3 builds

- Remote sync every ~30 s plus on pause, seek completion, chapter change, book change, timer stop,
  service shutdown and background transition — over `POST /api/session/{id}/sync` while online.
- Position survives process death with under 10 s lost.
- Session outbox: UUIDv4 per session, drained through `/api/session/local-all`, retried until the
  per-session result says `success`, seven-day retention, then compaction.
- **Conflict resolution is the server's**, and the app's job is not to fight it. The server takes the
  newer `updatedAt` and lets progress move backwards, which is exactly PLAY-004's "never blindly
  chooses the maximum position". So the app must send an honest `updatedAt` and must **not** clamp its
  own position to the maximum before sending, which would defeat a rule the server implements
  correctly.
- Clock-skew detection stops being hygiene and becomes load-bearing: the server trusts `updatedAt`, so
  a device five minutes fast wins every conflict it takes part in.

### The finished threshold needs a decision, not an implementation

PLAY-004 fixes the app's threshold at 95%, configurable 90–99%. The **server** marks a book finished
from the library's own `markAsFinishedTimeRemaining` and `markAsFinishedPercentComplete` — already in
the committed `libraries.json`, at ten seconds remaining and null respectively.

Ten seconds remaining on a ten-hour book is 99.97%. The two rules will disagree constantly, and the
symptom a user sees is a book that will not stay finished. The recommendation is to read the library's
thresholds and prefer them, with the app's setting as the fallback for a server reporting none — but
that is a deviation from PLAY-004's literal wording and wants an ADR before it is coded.

## Wave 4 — the controls a listener expects

PLAY-006 through PLAY-009, each small, each independently testable.

- Speed 0.5×–3.0× in 0.05 steps, pitch preserved, per-book override, persisting across local and
  streamed copies.
- Skip back/forward, independently configurable 5–120 s, defaulting to 15 and 30.
- Buffer presets, remote streams only, with the invalid combinations rejected rather than clamped.
- Sleep timer with end-of-chapter, fade-out, notification extension, surviving recreation.
- Auto-rewind buckets, off by default, never crossing a chapter or book start, undoable.

## Wave 5 — the exit criteria

Hardware. A delta test script per build, as in Phase 1, plus the soak.

## What is carried in from Phase 1

- **Cover art** is already fetched through the authenticated client, so the notification has artwork
  available without new plumbing.
- **Progress is already written and read** by `SyncAccountUseCase` and `DefaultLibraryRepository`, with
  the rule that an unsynced local write always wins. Wave 3 extends that path rather than inventing one.
- **The gateway pattern, `AppResult`, redacted logging and the contract-test harness** all apply
  unchanged. A playback endpoint is a gateway method with a fixture behind it, like every other.

## Risks worth naming now

1. **The capture may not answer everything.** If `item-play` comes back transcoding rather than direct,
   or the URLs need a token this app will not put in a URL (PRODUCT_SPEC 14.5), wave 1's design changes.
   That is the reason wave 0 exists.
2. **`targetSdk` 37's background-audio hardening** is not in force at target 36, so wave 1 will pass on
   Android 17 devices under the old rules. ADR-0011 records that the eventual bump is a validation pass
   — provided PLAY-001 and PLAY-002 are built to spec, which is exactly what wave 1 does.
3. **The soak is unforgiving.** Two hours of streaming exposes leaks, wake-lock mistakes and buffer
   mismanagement that nothing shorter does. It should be run early and often, not saved for the end.
