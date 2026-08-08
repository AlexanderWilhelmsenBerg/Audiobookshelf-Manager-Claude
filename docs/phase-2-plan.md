# Phase 2 — the streaming player, in waves

Written against `PRODUCT_SPEC` PLAY-001 … PLAY-009 and the Phase 2 exit criteria, with Phase 1 merged.

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

## Wave 0 — the capture ✅ **run on 2026-08-07**

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
mark a book finished without being asked** — which collides with PLAY-004's own 95% threshold, and wave
3 has to decide which wins.

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

PLAY-004 and PLAY-005. The requirements product priority 2 exists for.

- Remote sync every ~30 s plus on pause, seek completion, chapter change, book change, timer stop,
  service shutdown and background transition.
- Position survives process death with under 10 s lost.
- Session outbox: UUIDv4 per session, idempotent retry, seven-day retention, then compaction.
- Conflict resolution that **never blindly takes the maximum position** — an intentional rewind is data,
  not noise. Clock skew over five minutes is detected and surfaced in diagnostics.
- Finished threshold at 95%, configurable 90–99%.

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
